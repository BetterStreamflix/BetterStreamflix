package com.dskja.betterstreamflix.extractors

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.DecryptHelper
import com.dskja.betterstreamflix.utils.DnsResolver
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.withExtractorTimeouts
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import java.net.URL

class VoeExtractor : Extractor() {

    override val name = "VOE"
    override val mainUrl = "https://voe.sx/"
    override val aliasUrls = listOf(
        "https://jilliandescribecompany.com",
        "https://mikaylaarealike.com",
        "https://christopheruntilpoint.com",
        "https://walterprettytheir.com",
        "https://crystaltreatmenteast.com",
        "https://lauradaydo.com",
        "https://lancewhosedifficult.com",
        "https://dianaavoidthey.com",
        "https://jefferycontrolmodel.com",
        "https://charlestoughrace.com",
        "https://richardquestionbuilding.com",
        "https://jessicayeahcatch.com",
        "https://juliewomanwish.com",
        "https://rebeccapracticeloss.com",
        "https://johnbeyondnation.com",
        "https://voe.sx",
        "https://voe-unblock.com",
        "https://voeunblock.com",
        "https://voeun-block.net",
        "https://unblockvoe.net",
        "https://voe.bar",
        "https://voe.li",
    )
    // VOE rotates random English domains; catch canonical + unblock mirrors by host stem.
    override val rotatingDomain = listOf(
        Regex("""(?i)^voe[.\-/]"""),
        Regex("""(?i)(^|\.)voe-?(un)?block"""),
        Regex("""(?i)(^|\.)unblockvoe"""),
    )

    override suspend fun extract(link: String): Video {
        try {
            val service = VoeExtractorService.build(mainUrl, link)

            // Extract path from original link (handles both mainUrl and alias URLs)
            val parsedUrl = URL(link)
            val originalPath = parsedUrl.path + if (parsedUrl.query != null) "?${parsedUrl.query}" else ""

            val source = service.getSource(originalPath)
            val scriptTag = source.selectFirst("script[type=application/json]")
            val encodedStringInScriptTag = scriptTag?.data()?.trim().orEmpty()
            val encodedString = DecryptHelper.findEncodedRegex(source.html())
            val decryptedContent = if (encodedString != null) {
                DecryptHelper.decrypt(encodedString)
            } else {
                DecryptHelper.decrypt(encodedStringInScriptTag)
            }

            val m3u8 = decryptedContent.get("source")?.asString.orEmpty()
            if (m3u8.isBlank()) {
                throw Exception("VOE source not found")
            }

            val baseSubtitleScript = source.selectFirst("script")?.data() ?: ""
            var baseSubtitle = ""
            if (baseSubtitleScript.isNotBlank()) {
                val regex = Regex("""var\s+base\s*=\s*['"]([^'"]+)['"]""")
                baseSubtitle = regex.find(baseSubtitleScript)?.groupValues?.get(1) ?: ""
            }

            val captions = decryptedContent.getAsJsonArray("captions")
            val subtitles = if (captions != null) {
                captions.map { caption ->
                    val obj = caption.asJsonObject
                    val file = obj.get("file").asString
                    Video.Subtitle(
                        file = if (file.startsWith("http")) file else baseSubtitle + file,
                        label = obj.get("label").asString,
                        initialDefault = obj.get("default").asBoolean,
                        default = if (UserPreferences.serverAutoSubtitlesDisabled) false else obj.get("default").asBoolean
                    )
                }
            } else {
                emptyList()
            }
            return Video(
                source = m3u8,
                subtitles = subtitles,
                useServerSubtitleSetting = true,
                headers = mapOf(
                    "Referer" to link,
                    "Origin" to "${parsedUrl.protocol}://${parsedUrl.host}",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                ),
            )
        } catch (e: Exception) {
            // Dead VOE embeds (HTTP 404 / megakino stale links) — soft-fail so failover
            // can hop to the next hoster without burning a second extract retry.
            val httpCode = (e as? retrofit2.HttpException)?.code()
            val looks404 = httpCode == 404 ||
                e.message?.contains("404") == true ||
                e.message?.contains("Not Found", ignoreCase = true) == true
            if (looks404) {
                throw Exception("VOE source not found (404)", e)
            }
            throw e
        }
    }


    private interface VoeExtractorService {

        companion object {
            suspend fun build(baseUrl: String, originalLink: String): VoeExtractorService {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .withExtractorTimeouts()
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("Referer", originalLink)
                            .build()
                        chain.proceed(request)
                    }
                    .build()

                val retrofitVOE = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(JsoupConverterFactory.create())

                    .build()
                val retrofitVOEBuiled = retrofitVOE.create(VoeExtractorService::class.java)

                // Extract path from original link (handles both mainUrl and alias URLs)
                val relativePath = if (originalLink.startsWith(baseUrl)) {
                    originalLink.replace(baseUrl, "")
                } else {
                    // If link doesn't start with baseUrl, extract path directly (alias URL)
                    val parsedUrl = URL(originalLink)
                    parsedUrl.path + if (parsedUrl.query != null) "?${parsedUrl.query}" else ""
                }

                val retrofitVOEhtml = retrofitVOEBuiled.getSource(relativePath).html()

                val regex = Regex("""https://([a-zA-Z0-9.-]+)(?:/[^'"]*)?""")
                val match = regex.find(retrofitVOEhtml)
                val redirectBaseUrl = if (match != null) {
                    "https://${match.groupValues[1]}/"
                } else {
                    throw Exception("Base url not found for VOE")
                }

                val retrofitRedirected = Retrofit.Builder()
                    .baseUrl(redirectBaseUrl)
                    .client(client)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                return retrofitRedirected.create(VoeExtractorService::class.java)
            }
        }

        @GET
        @Headers(
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language: it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "X-Requested-With: XMLHttpRequest"
        )
        suspend fun getSource(@Url url: String): Document
    }
}