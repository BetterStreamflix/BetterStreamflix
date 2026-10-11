package com.dskja.betterstreamflix.download

/**
 * Rewrites an HLS playlist so every media reference points at a local proxy.
 * Relative segment URIs are resolved against [baseUrl] first.
 *
 * DASH/MPD offline share / Play-with is intentionally unsupported: there is no
 * MPD rewrite path. [looksLikePlaylist] may accept MPD markers in the future,
 * but [OfflineCacheProxy] still fails closed until a real rewrite exists (DL-P2-G).
 */
object OfflinePlaylistRewrite {
    private val uriAttrDouble = Regex("""URI="([^"]+)"""")
    private val uriAttrSingle = Regex("""URI='([^']+)'""")

    fun rewrite(
        playlist: String,
        baseUrl: String,
        mapAbsolute: (String) -> String,
    ): String {
        val out = StringBuilder()
        for (raw in playlist.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> out.append('\n')
                // Any #EXT… tag that carries URI="…" / URI='…' (KEY, MAP, MEDIA,
                // SESSION-KEY, PRELOAD-HINT, PART, I-FRAME-STREAM-INF, …).
                line.startsWith("#EXT") && (line.contains("URI=\"") || line.contains("URI='")) -> {
                    out.append(rewriteTagUris(line, baseUrl, mapAbsolute))
                    out.append('\n')
                }
                line.startsWith("#") -> {
                    out.append(raw)
                    out.append('\n')
                }
                else -> {
                    out.append(mapAbsolute(resolve(baseUrl, line)))
                    out.append('\n')
                }
            }
        }
        return out.toString()
    }

    fun resolve(baseUrl: String, ref: String): String {
        val trimmed = ref.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        return runCatching { java.net.URI(baseUrl).resolve(trimmed).toString() }
            .getOrDefault(trimmed)
    }

    /**
     * True for HLS playlists only. DASH/MPD is intentionally not accepted here
     * until a real MPD rewrite exists (Play-with / share stay fail-closed).
     */
    fun looksLikePlaylist(body: String): Boolean =
        body.trimStart().startsWith("#EXTM3U") || body.trimStart().startsWith("#EXT-X-")

    private fun rewriteTagUris(
        line: String,
        baseUrl: String,
        mapAbsolute: (String) -> String,
    ): String {
        var rewritten = uriAttrDouble.replace(line) { match ->
            val absolute = resolve(baseUrl, match.groupValues[1])
            "URI=\"${mapAbsolute(absolute)}\""
        }
        rewritten = uriAttrSingle.replace(rewritten) { match ->
            val absolute = resolve(baseUrl, match.groupValues[1])
            "URI='${mapAbsolute(absolute)}'"
        }
        return rewritten
    }
}
