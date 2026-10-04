package com.dskja.betterstreamflix.download

/**
 * Rewrites an HLS/DASH-ish playlist so every media reference points at a
 * local proxy. Relative segment URIs are resolved against [baseUrl] first.
 */
object OfflinePlaylistRewrite {
    private val uriAttr = Regex("""URI="([^"]+)"""")

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
                line.startsWith("#") &&
                    (line.startsWith("#EXT-X-KEY") ||
                        line.startsWith("#EXT-X-MAP") ||
                        line.startsWith("#EXT-X-MEDIA") ||
                        line.startsWith("#EXT-X-I-FRAME-STREAM-INF")) -> {
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

    fun looksLikePlaylist(body: String): Boolean =
        body.trimStart().startsWith("#EXTM3U") || body.trimStart().startsWith("#EXT-X-")

    private fun rewriteTagUris(
        line: String,
        baseUrl: String,
        mapAbsolute: (String) -> String,
    ): String {
        return uriAttr.replace(line) { match ->
            val absolute = resolve(baseUrl, match.groupValues[1])
            "URI=\"${mapAbsolute(absolute)}\""
        }
    }
}
