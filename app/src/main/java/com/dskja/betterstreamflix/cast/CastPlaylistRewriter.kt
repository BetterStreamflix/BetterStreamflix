package com.dskja.betterstreamflix.cast

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Pure HLS playlist rewriter for Cast proxy (unit-testable without NanoHTTPD).
 */
object CastPlaylistRewriter {
    private val URI_ATTR_REGEX = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)

    /**
     * Rewrites absolute and relative segment / URI= references to flow through [proxyBase]/p?u=…
     * When [sessionToken] is non-blank, appends `&t=` so playlist hops require the same auth as [wrap].
     */
    fun rewrite(
        playlistText: String,
        playlistUrl: String,
        proxyBase: String,
        sessionToken: String = "",
    ): String {
        if (!playlistText.contains("#EXTM3U")) return playlistText
        val base = proxyBase.trimEnd('/')
        return playlistText.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() || trimmed.startsWith("#") ->
                    rewriteTagUris(line, playlistUrl, base, sessionToken)
                else -> {
                    val absolute = resolveAgainst(playlistUrl, trimmed)
                    proxyUrl(base, absolute, sessionToken)
                }
            }
        }
    }

    fun resolveAgainst(baseUrl: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return runCatching { URI(baseUrl).resolve(ref).toString() }.getOrDefault(ref)
    }

    fun proxyUrl(proxyBase: String, absoluteUrl: String, sessionToken: String = ""): String {
        val base = proxyBase.trimEnd('/')
        val encoded = URLEncoder.encode(absoluteUrl, StandardCharsets.UTF_8.name())
        return if (sessionToken.isBlank()) {
            "$base/p?u=$encoded"
        } else {
            "$base/p?u=$encoded&t=${URLEncoder.encode(sessionToken, StandardCharsets.UTF_8.name())}"
        }
    }

    private fun rewriteTagUris(
        line: String,
        playlistUrl: String,
        base: String,
        sessionToken: String,
    ): String {
        if (!line.contains("URI=", ignoreCase = true)) return line
        return URI_ATTR_REGEX.replace(line) { match ->
            val raw = match.groupValues[1]
            val absolute = resolveAgainst(playlistUrl, raw)
            "URI=\"${proxyUrl(base, absolute, sessionToken)}\""
        }
    }
}
