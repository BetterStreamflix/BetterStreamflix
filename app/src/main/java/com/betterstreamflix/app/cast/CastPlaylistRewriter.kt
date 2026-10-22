package com.betterstreamflix.app.cast

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Pure HLS/DASH playlist rewriter for Cast proxy (unit-testable without NanoHTTPD).
 */
object CastPlaylistRewriter {
    /** Double-quoted, single-quoted, or unquoted URI= attribute values. */
    private val URI_ATTR_REGEX =
        Regex("""URI\s*=\s*(?:"([^"]+)"|'([^']+)'|([^\s,>]+))""", RegexOption.IGNORE_CASE)

    /** Absolute http(s) URLs embedded in MPD attributes. */
    private val MPD_HTTP_ATTR_REGEX =
        Regex("""((?:media|initialization|sourceURL|href)\s*=\s*")(https?://[^"]+)(")""", RegexOption.IGNORE_CASE)

    /**
     * Rewrites absolute and relative segment / URI= references to flow through [proxyBase]/p?u=…
     * When [sessionToken] is non-blank, appends `&t=` so playlist hops require the same auth as [wrap].
     * [onAbsoluteUrl] is invoked for every rewritten absolute URL so callers can allowlist hosts.
     */
    fun rewrite(
        playlistText: String,
        playlistUrl: String,
        proxyBase: String,
        sessionToken: String = "",
        onAbsoluteUrl: ((String) -> Unit)? = null,
    ): String {
        if (!playlistText.contains("#EXTM3U")) return playlistText
        val base = proxyBase.trimEnd('/')
        return playlistText.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() || trimmed.startsWith("#") ->
                    rewriteTagUris(line, playlistUrl, base, sessionToken, onAbsoluteUrl)
                else -> {
                    val absolute = resolveAgainst(playlistUrl, trimmed)
                    onAbsoluteUrl?.invoke(absolute)
                    proxyUrl(base, absolute, sessionToken)
                }
            }
        }
    }

    /** Rewrite absolute http(s) media URLs inside an MPD so they flow through the Cast proxy. */
    fun rewriteMpd(
        mpdText: String,
        playlistUrl: String,
        proxyBase: String,
        sessionToken: String = "",
        onAbsoluteUrl: ((String) -> Unit)? = null,
    ): String {
        val base = proxyBase.trimEnd('/')
        var out = MPD_HTTP_ATTR_REGEX.replace(mpdText) { match ->
            val absolute = match.groupValues[2]
            onAbsoluteUrl?.invoke(absolute)
            match.groupValues[1] + proxyUrl(base, absolute, sessionToken) + match.groupValues[3]
        }
        // Relative media= / initialization= without scheme — resolve then proxy.
        val relativeAttr = Regex(
            """((?:media|initialization|sourceURL)\s*=\s*")(?!https?://|data:)([^"]+)(")""",
            RegexOption.IGNORE_CASE,
        )
        out = relativeAttr.replace(out) { match ->
            val absolute = resolveAgainst(playlistUrl, match.groupValues[2])
            onAbsoluteUrl?.invoke(absolute)
            match.groupValues[1] + proxyUrl(base, absolute, sessionToken) + match.groupValues[3]
        }
        return out
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
        onAbsoluteUrl: ((String) -> Unit)?,
    ): String {
        if (!line.contains("URI=", ignoreCase = true)) return line
        return URI_ATTR_REGEX.replace(line) { match ->
            val raw = match.groupValues[1].ifBlank {
                match.groupValues[2].ifBlank { match.groupValues[3] }
            }
            val absolute = resolveAgainst(playlistUrl, raw)
            onAbsoluteUrl?.invoke(absolute)
            "URI=\"${proxyUrl(base, absolute, sessionToken)}\""
        }
    }
}
