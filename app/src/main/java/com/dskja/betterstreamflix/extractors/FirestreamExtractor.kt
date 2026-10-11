package com.dskja.betterstreamflix.extractors

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.DnsResolver
import com.dskja.betterstreamflix.utils.JsUnpacker
import com.dskja.betterstreamflix.utils.MeinecloudEmbedHelper
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import java.net.URI
import java.net.URL

/**
 * Dedicated extractor for firestream.* DE wrappers (Filmpalast "Firestream HD").
 *
 * Path 1 (fork): expand Meinecloud-style mirror lists, then packed JS.
 * Path 2 (additive): native firestream.to token-blob / signed URL resolve when
 * mirrors are empty — does not replace the Meinecloud path.
 */
class FirestreamExtractor : Extractor() {

    override val name = "Firestream"
    override val mainUrl = "https://firestream.site"
    override val aliasUrls = listOf(
        "https://firestream.to",
        "https://firestream.click",
        "https://firestream.cam",
        "https://firestream.online",
        "https://firestream.live",
        "https://firestream.xyz",
        "https://www.firestream.site",
    )
    override val rotatingDomain = listOf(
        Regex("""(?i)(^|\.)firestream(\.|/)"""),
        Regex("""(?i)^firestream[.\-/]"""),
    )

    override suspend fun extract(link: String): Video {
        val mirrors = MeinecloudEmbedHelper.expandToServers(link)
            .map { it.src }
            .filter { it.isNotBlank() }
            .distinct()
            .filterNot { MeinecloudEmbedHelper.isFirestreamUrl(it) }

        var lastError: Exception? = null
        for (mirror in mirrors.take(6)) {
            try {
                return Extractor.extract(mirror, maxAttempts = 1)
            } catch (e: Exception) {
                lastError = e
            }
        }

        runCatching { extractPacked(link) }.getOrNull()?.let { return it }

        // Additive dual-path: native token-blob resolve (firestream.to API).
        runCatching { extractTokenBlob(link) }.getOrNull()?.let { return it }

        throw lastError
            ?: Exception("Firestream: no playable mirrors for $link")
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
            ?: throw Exception("Firestream packed source not found")

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

    private data class PageLoad(
        val html: String,
        val cookies: String,
    )

    /**
     * Upstream-style resolve: read #video-data / #token-blob and POST
     * `/api/videos/{slug}/resolve`. Kept additive behind the Meinecloud path.
     */
    private fun extractTokenBlob(link: String): Video {
        val parsed = URL(link)
        val baseUrl = "${parsed.protocol}://${parsed.host}"
        val slug = parsed.path
            .trim('/')
            .substringAfterLast('/')
        if (slug.isBlank()) {
            throw Exception("Firestream slug not found")
        }

        val client = OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val pageRequest = Request.Builder()
            .url(link)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Referer", "$baseUrl/")
            .build()

        val page = client.newCall(pageRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Firestream embed HTTP ${response.code}")
            }
            PageLoad(
                html = response.body?.string().orEmpty(),
                cookies = response.headers("Set-Cookie")
                    .map { it.substringBefore(';') }
                    .filter { it.isNotBlank() }
                    .joinToString("; "),
            )
        }
        if (page.html.isBlank()) {
            throw Exception("Firestream embed page is empty")
        }

        val document = Jsoup.parse(page.html, baseUrl)
        val videoDataRaw =
            document.getElementById("video-data")
                ?.data()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: document.getElementById("video-data")
                    ?.html()
                    ?.trim()
                    .orEmpty()
        if (videoDataRaw.isBlank()) {
            throw Exception("Firestream video-data not found")
        }

        val root = JsonParser.parseString(videoDataRaw).asJsonObject
        val video = root.get("video")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: throw Exception("Firestream video object not found")

        fun stringValue(obj: JsonObject, key: String): String? =
            obj.get(key)
                ?.takeIf { !it.isJsonNull }
                ?.runCatching { asString }
                ?.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        var source = stringValue(video, "signedVideoUrl")
            ?: stringValue(video, "signedVideoSdUrl")

        if (source.isNullOrBlank()) {
            val tokenBlob =
                document.getElementById("token-blob")
                    ?.data()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: document.getElementById("token-blob")
                        ?.html()
                        ?.trim()
                        .orEmpty()
            if (tokenBlob.isBlank()) {
                throw Exception("Firestream token-blob not found")
            }

            val resolveJson = JsonObject().apply {
                addProperty("blob", tokenBlob)
            }
            val resolveRequest = Request.Builder()
                .url("$baseUrl/api/videos/$slug/resolve")
                .post(
                    resolveJson.toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType()),
                )
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Origin", baseUrl)
                .header("Referer", link)
                .apply {
                    if (page.cookies.isNotBlank()) {
                        header("Cookie", page.cookies)
                    }
                }
                .build()

            source = client.newCall(resolveRequest).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw Exception(
                        "Firestream resolve HTTP ${response.code}: ${raw.take(250)}",
                    )
                }
                val resolved = JsonParser.parseString(raw).asJsonObject
                stringValue(resolved, "signedVideoUrl")
                    ?: stringValue(resolved, "signedVideoSdUrl")
            }
        }

        if (source.isNullOrBlank()) {
            throw Exception("Firestream signed video URL not found")
        }

        val subtitles =
            video.get("subtitles")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { element ->
                    val obj = element.asJsonObject
                    val rawUrl = stringValue(obj, "url") ?: return@mapNotNull null
                    val subtitleUrl = runCatching {
                        URI("$baseUrl/").resolve(rawUrl).toString()
                    }.getOrDefault(rawUrl)
                    val label = stringValue(obj, "label")
                        ?: stringValue(obj, "language")
                        ?: "Subtitle"
                    Video.Subtitle(
                        label = label,
                        file = subtitleUrl,
                        default = obj.get("default")
                            ?.takeIf { !it.isJsonNull }
                            ?.runCatching { asBoolean }
                            ?.getOrNull()
                            ?: false,
                    )
                }
                ?: emptyList()

        return Video(
            source = source,
            subtitles = subtitles,
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
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
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
