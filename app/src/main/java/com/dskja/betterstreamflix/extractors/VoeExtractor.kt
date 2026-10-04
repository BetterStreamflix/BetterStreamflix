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
            val parsedUrl = URL(link)
            val originalPath = parsedUrl.path + if (parsedUrl.query != null) "?${parsedUrl.query}" else ""
            val client = VoeExtractorService.client(link)
            val firstService = VoeExtractorService.create(mainUrl, client)
            val first = firstService.getSource(originalPath)
            val origin = "${parsedUrl.protocol}://${parsedUrl.host}"
            parsePlayer(first, link, origin)?.let { return it }

            val redirectBase = VoeRedirect.baseUrl(first.html(), parsedUrl.host)
                ?: throw Exception("VOE source not found")
            if (redirectBase.trimEnd('/').equals(origin, ignoreCase = true)) {
                throw Exception("VOE source not found")
            }
            val second = VoeExtractorService.create(redirectBase, client).getSource(originalPath)
            return parsePlayer(second, redirectBase, redirectBase.trimEnd('/'))
                ?: throw Exception("VOE source not found")
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

    private fun parsePlayer(source: Document, referer: String, origin: String): Video? {
        val scriptJson = source.selectFirst("script[type=application/json]")?.data()?.trim().orEmpty()
        val decryptedContent = DecryptHelper.firstPlayable(
            listOfNotNull(
                DecryptHelper.findEncodedRegex(source.html()),
                scriptJson.takeIf { it.isNotBlank() },
            ),
        ) ?: return null
        val m3u8 = decryptedContent.get("source")?.asString.orEmpty()
        if (m3u8.isBlank()) return null

        val baseSubtitleScript = source.selectFirst("script")?.data() ?: ""
        val baseSubtitle = Regex("""var\s+base\s*=\s*['"]([^'"]+)['"]""")
            .find(baseSubtitleScript)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

        val captions = decryptedContent.getAsJsonArray("captions")
        val subtitles = captions?.mapNotNull { caption ->
            val obj = caption.asJsonObject
            val file = obj.get("file")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val label = obj.get("label")?.takeIf { !it.isJsonNull }?.asString ?: "Subtitle"
            val isDefault = obj.get("default")?.takeIf { !it.isJsonNull }?.asBoolean == true
            Video.Subtitle(
                file = if (file.startsWith("http")) file else baseSubtitle + file,
                label = label,
                initialDefault = isDefault,
                default = if (UserPreferences.serverAutoSubtitlesDisabled) false else isDefault,
            )
        }.orEmpty()

        val originBase = origin.trimEnd('/')
        val refererHeader = referer.ifBlank { "$originBase/" }
        return Video(
            source = m3u8,
            subtitles = subtitles,
            useServerSubtitleSetting = true,
            headers = mapOf(
                "Referer" to refererHeader,
                "Origin" to originBase,
                "User-Agent" to VoeExtractorService.USER_AGENT,
            ),
        )
    }


    private interface VoeExtractorService {

        companion object {
            const val USER_AGENT =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            fun client(originalLink: String): OkHttpClient {
                return OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .withExtractorTimeouts()
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("Referer", originalLink)
                            .header("User-Agent", USER_AGENT)
                            .build()
                        chain.proceed(request)
                    }
                    .build()
            }

            fun create(baseUrl: String, httpClient: OkHttpClient): VoeExtractorService {
                val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                return Retrofit.Builder()
                    .baseUrl(normalized)
                    .client(httpClient)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                    .create(VoeExtractorService::class.java)
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