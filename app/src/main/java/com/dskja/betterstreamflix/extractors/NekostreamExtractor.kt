package com.dskja.betterstreamflix.extractors

import android.net.Uri
import androidx.media3.common.MimeTypes
import com.google.gson.Gson
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.DnsResolver
import com.dskja.betterstreamflix.utils.withExtractorTimeouts
import okhttp3.OkHttpClient
import okhttp3.Request

class NekostreamExtractor : Extractor() {

    override val name = "Nekostream"
    override val mainUrl = "https://vidtube.site"
    override val aliasUrls = listOf(
        "https://megaplay.buzz",
        "https://vidwish.live",
        "https://megaplay.live",
        "https://aniplaynow.live",
        "https://megaplay.io",
        "https://vidtube.to",
    )
    override val rotatingDomain = listOf(
        Regex("""(?i)^vidtube[.\-/]"""),
        Regex("""(?i)(^|\.)megaplay(\.|/)"""),
        Regex("""(?i)^vidwish[.\-/]"""),
        Regex("""(?i)^aniplaynow[.\-/]"""),
    )

    private val client = OkHttpClient.Builder()
        .dns(DnsResolver.doh)
        .withExtractorTimeouts()
        .build()

    override suspend fun extract(link: String): Video {
        val streamPageUrl = normalizeStreamPageUrl(link)
        val pageUri = Uri.parse(streamPageUrl)
        val origin = "${pageUri.scheme}://${pageUri.host}"
        val pageBody = getText(
            url = streamPageUrl,
            referer = when {
                streamPageUrl.contains("megaplay", ignoreCase = true) -> "https://anikoto.net/"
                else -> "$origin/"
            },
            origin = origin,
            accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )

        val fileId = extractPlayerFileId(pageBody)
            ?: throw Exception("Nekostream player file id not found")

        val streamType = Regex("""type:\s*['"]([^'"]+)['"]""")
            .find(pageBody)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""/stream/[^/]+/\d+/(sub|dub|raw)\b""", RegexOption.IGNORE_CASE)
                .find(streamPageUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?.lowercase()

        // HD-1 mirrors pass ?s=tcdn (CDN selector). megaplay's page JS appends this to
        // getSources XHRs; OkHttp must do the same or some servers return empty files.
        val sourceHint = Uri.parse(streamPageUrl).getQueryParameter("s")
            ?.takeIf { it.isNotBlank() }

        // Prefer getSourcesNew; both endpoints now commonly return encrypted `enc`
        // instead of plaintext sources.file. Decrypt is handled below.
        val typeQuery = streamType?.let { "&type=$it" }.orEmpty()
        val sourceQuery = sourceHint?.let { "&s=$it" }.orEmpty()
        val sourcesCandidates = listOf(
            "$origin/stream/getSourcesNew?id=$fileId$typeQuery$sourceQuery",
            "$origin/stream/getSources?id=$fileId$typeQuery$sourceQuery",
            "$origin/stream/getSourcesNew?id=$fileId$typeQuery",
            "$origin/stream/getSources?id=$fileId$typeQuery",
        )

        val aesKey = resolveAesKey(pageBody)
        val aesIv = resolveAesIv(pageBody)

        var lastError: Exception? = null
        for (sourcesUrl in sourcesCandidates) {
            try {
                val sourcesBody = getText(
                    url = sourcesUrl,
                    referer = streamPageUrl,
                    origin = origin,
                    accept = "application/json, text/javascript, */*; q=0.01",
                    requestedWith = true,
                )
                val sources = runCatching {
                    Gson().fromJson(sourcesBody, SourcesResponse::class.java)
                }.getOrNull()
                if (sources == null) {
                    // Empty / non-JSON body — try next candidate URL.
                    if (sourcesBody.isBlank() || sourcesBody.trimStart().startsWith("<")) {
                        lastError = Exception("Nekostream empty or HTML sources body")
                    }
                    continue
                }
                val source = resolveSourceFile(sources, aesKey, aesIv)
                if (source.isNullOrBlank()) {
                    lastError = Exception("Nekostream source not found — try another server")
                    continue
                }

                return Video(
                    source = source,
                    subtitles = sources.tracks.orEmpty()
                        .filter { it.kind == null || it.kind == "captions" }
                        .mapNotNull {
                            Video.Subtitle(
                                label = it.label?.ifBlank { null } ?: "Subtitle",
                                file = it.file ?: return@mapNotNull null,
                                default = it.default == true,
                            )
                        },
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$origin/",
                        "Origin" to origin,
                        "Accept" to "*/*",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "Connection" to "keep-alive",
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "cross-site",
                        "Sec-GPC" to "1",
                    ),
                    type = MimeTypes.APPLICATION_M3U8,
                )
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: Exception("Nekostream source not found — try another server")
    }

    private fun normalizeStreamPageUrl(link: String): String {
        val base = link.substringBefore("?")
        return if (link.contains("?")) {
            link
        } else {
            "$base?autostart=true"
        }
    }

    private fun extractPlayerFileId(pageBody: String): String? {
        Regex(
            """id=["']megaplay-player["'][\s\S]*?data-id=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(pageBody)?.groupValues?.getOrNull(1)?.let { return it }

        Regex(
            """data-id=["']([^"']+)["'][\s\S]*?id=["']megaplay-player["']""",
            RegexOption.IGNORE_CASE,
        ).find(pageBody)?.groupValues?.getOrNull(1)?.let { return it }

        Regex(
            """<title>\s*File\s+(\d+)\s+-""",
            RegexOption.IGNORE_CASE,
        ).find(pageBody)?.groupValues?.getOrNull(1)?.let { return it }

        // Fallback: first data-id near the player container.
        return Regex(
            """class=["'][^"']*form-area[^"']*["'][^>]*data-id=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(pageBody)?.groupValues?.getOrNull(1)
    }

    private fun resolveSourceFile(
        sources: SourcesResponse,
        aesKey: String,
        aesIv: String,
    ): String? {
        sources.sources?.file?.takeIf { it.isNotBlank() }?.let { return it }
        sources.sources?.list?.firstOrNull { !it.file.isNullOrBlank() }?.file?.let { return it }

        val enc = sources.enc?.takeIf { it.isNotBlank() } ?: return null
        val decrypted = MegaPlayEncDecrypt.decrypt(enc, aesKey, aesIv) ?: return null
        val parsed = runCatching {
            Gson().fromJson(decrypted, EncodedSourcePayload::class.java)
        }.getOrNull()
        return parsed?.file?.takeIf { it.isNotBlank() }
            ?: Regex(""""file"\s*:\s*"([^"]+)"""")
                .find(decrypted)
                ?.groupValues
                ?.getOrNull(1)
    }

    private fun resolveAesKey(pageBody: String): String {
        return Regex(
            """(?:trustAesKey|TRUST_AES_KEY)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(pageBody)?.groupValues?.getOrNull(1)
            ?: MegaPlayEncDecrypt.DEFAULT_AES_KEY
    }

    private fun resolveAesIv(pageBody: String): String {
        return Regex(
            """(?:trustAesIv|TRUST_AES_IV)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(pageBody)?.groupValues?.getOrNull(1)
            ?: MegaPlayEncDecrypt.DEFAULT_AES_IV
    }

    private fun getText(
        url: String,
        referer: String,
        origin: String,
        accept: String,
        requestedWith: Boolean = false,
    ): String {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", referer)
            .header("Origin", origin)
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")

        if (requestedWith) {
            requestBuilder.header("X-Requested-With", "XMLHttpRequest")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 404 || response.code == 410) {
                throw Exception("Nekostream source not found (404)")
            }
            if (!response.isSuccessful) {
                throw Exception("Nekostream request failed ${response.code}: $url")
            }
            return response.body?.string().orEmpty()
        }
    }

    private data class SourcesResponse(
        val sources: Sources? = null,
        val tracks: List<Track>? = null,
        val enc: String? = null,
    ) {
        data class Sources(
            val file: String? = null,
            val list: List<SourceItem>? = null,
        )

        data class SourceItem(
            val file: String? = null,
            val type: String? = null,
        )

        data class Track(
            val file: String? = null,
            val label: String? = null,
            val kind: String? = null,
            val default: Boolean? = null,
        )
    }

    private data class EncodedSourcePayload(
        val file: String? = null,
    )

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
