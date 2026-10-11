package com.dskja.betterstreamflix.extractors

import androidx.media3.common.MimeTypes
import com.dskja.betterstreamflix.models.Video
import org.json.JSONObject
import java.net.URI

object VidzeeDirectStream {

    data class ServerLinkParams(
        val tmdbId: String,
        val season: String?,
        val episode: String?,
        /** Optional labeled-mirror index from `sr=` (Nflix=0, Duke=1, …). */
        val sr: Int? = null,
    ) {
        val isEpisode: Boolean
            get() = season != null && episode != null
    }

    /*
     * Live checks 2026-09-14:
     *
     * Movies:
     *   dcloud  -> 200
     *   tik     -> 200
     *   ipcloud -> may return 502
     *
     * TV:
     *   ipcloud -> 200
     *   dcloud  -> may return 502
     *   tik     -> may return 502
     *
     * Do not hard-fail on one backend. Try the currently preferred
     * backend first and automatically continue on server-side errors.
     */
    fun parseServerLink(link: String): ServerLinkParams? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        val query = uri.rawQuery.orEmpty()
        if (query.isBlank()) return null

        val params = query.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null
            else {
                val key = part.substring(0, idx)
                val value = part.substring(idx + 1)
                key to value
            }
        }.toMap()

        val tmdbId = params["id"]?.takeIf { it.isNotBlank() } ?: return null
        val season = params["ss"]?.takeIf { it.isNotBlank() }
        val episode = params["ep"]?.takeIf { it.isNotBlank() }
        val sr = params["sr"]?.toIntOrNull()

        return ServerLinkParams(
            tmdbId = tmdbId,
            season = season,
            episode = episode,
            sr = sr,
        )
    }

    /**
     * Map labeled `sr` mirrors onto preferred direct backends when possible.
     * Unknown / default (`sr=0`) keeps the historical cascade order.
     */
    fun preferredDirectServers(sr: Int?, isEpisode: Boolean): List<String> {
        val default = if (isEpisode) {
            listOf("ipcloud", "dcloud", "tik")
        } else {
            listOf("dcloud", "tik", "ipcloud")
        }
        val preferred = when (sr) {
            null, 0 -> null // Nflix / default — full cascade
            1 -> "dcloud" // Duke
            2, 3, 4, 5, 6 -> "tik"
            7, 8 -> "ipcloud"
            // Language mirrors (Hindi…): prefer ipcloud then cascade
            in 9..13 -> "ipcloud"
            else -> null
        } ?: return default
        return listOf(preferred) + default.filter { it != preferred }
    }

    fun directStreamEndpoints(
        coreApi: String,
        tmdbId: String,
        season: String?,
        episode: String?,
        sr: Int? = null,
    ): List<Pair<String, String>> {
        val isEpisode = season != null && episode != null
        val directServers = preferredDirectServers(sr, isEpisode)

        return directServers.map { directServer ->
            val endpoint = if (isEpisode) {
                "$coreApi/streams/tv/" +
                    "$tmdbId/$season/$episode" +
                    "?s=$directServer&e=0"
            } else {
                "$coreApi/streams/movie/" +
                    "$tmdbId" +
                    "?s=$directServer&e=0"
            }
            directServer to endpoint
        }
    }

    fun parseDirectStreamResponse(
        raw: String,
        mainUrl: String,
    ): Video? {
        if (raw.isBlank()) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null

        val source = json
            .optString("url")
            .trim()

        if (source.isBlank()) {
            return null
        }

        val headers =
            linkedMapOf(
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl,
                "User-Agent" to
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/140.0.0.0 Safari/537.36",
            )

        json.optJSONObject("headers")
            ?.let { sourceHeaders ->
                val keys = sourceHeaders.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = sourceHeaders.optString(key).trim()
                    if (value.isNotBlank()) {
                        headers[key] = value
                    }
                }
            }

        return Video(
            source = source,
            headers = headers,
            type = if (
                source
                    .substringBefore("?")
                    .endsWith(".m3u8", ignoreCase = true)
            ) {
                MimeTypes.APPLICATION_M3U8
            } else {
                MimeTypes.VIDEO_MP4
            },
        )
    }
}
