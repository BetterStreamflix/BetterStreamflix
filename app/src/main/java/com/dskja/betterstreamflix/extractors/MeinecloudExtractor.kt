package com.dskja.betterstreamflix.extractors

import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.MeinecloudEmbedHelper
import com.dskja.betterstreamflix.utils.JsUnpacker
import com.dskja.betterstreamflix.utils.DnsResolver
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import java.net.URL

/**
 * Dedicated extractor for meinecloud.* / devideosrc wrappers.
 * Expands mirror lists first, then unpacks packed JS as a last resort.
 */
class MeinecloudExtractor : Extractor() {

    override val name = "Meinecloud"
    override val mainUrl = "https://meinecloud.click"
    override val aliasUrls = listOf(
        "https://meinecloud.to",
        "https://meinecloud.cam",
        "https://meinecloud.online",
        "https://meinecloud.live",
        "https://meinecloud.site",
        "https://devideosrc.click",
        "https://devideosrc.to",
    )
    override val rotatingDomain = listOf(
        Regex("""(?i)(^|\.)meinecloud(\.|/)"""),
        Regex("""(?i)(^|\.)devideosrc(\.|/)"""),
    )

    override suspend fun extract(link: String): Video {
        val mirrors = MeinecloudEmbedHelper.expandToServers(link)
            .map { it.src }
            .filter { it.isNotBlank() }
            .distinct()
            .filterNot { MeinecloudEmbedHelper.isMeinecloudUrl(it) }

        var lastError: Exception? = null
        for (mirror in mirrors) {
            try {
                return Extractor.extract(mirror)
            } catch (e: Exception) {
                lastError = e
            }
        }

        // Last resort: unpack player JS on the wrapper itself.
        runCatching { extractPacked(link) }.getOrNull()?.let { return it }

        throw lastError
            ?: Exception("Meinecloud: no playable mirrors for $link")
    }

    private suspend fun extractPacked(link: String): Video {
        val baseUrl = URL(link).let { "${it.protocol}://${it.host}" }
        val service = Service.build(baseUrl)
        val document = service.get(
            url = link,
            referer = "$baseUrl/",
            userAgent = USER_AGENT,
        )
        val html = document.html()
        val source = findSource(html)
            ?: document.select("script")
                .asSequence()
                .mapNotNull { JsUnpacker(it.html()).unpack() }
                .mapNotNull { findSource(it) }
                .firstOrNull()
            ?: throw Exception("Meinecloud packed source not found")

        return Video(
            source = source,
            headers = mapOf(
                "Referer" to "$baseUrl/",
                "Origin" to baseUrl,
                "User-Agent" to USER_AGENT,
            ),
            type = StreamMime.infer(source),
        )
    }

    private fun findSource(text: String): String? {
        val decoded = text
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\x26", "&")
        val patterns = listOf(
            Regex("""["']?file["']?\s*[:=]\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']?src["']?\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""", RegexOption.IGNORE_CASE),
        )
        return patterns.asSequence()
            .mapNotNull { it.find(decoded)?.groupValues?.getOrNull(1) }
            .firstOrNull { it.isNotBlank() }
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()
                return Retrofit.Builder()
                    .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
                    .client(client)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                    .create(Service::class.java)
            }
        }

        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("User-Agent") userAgent: String,
        ): Document
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
