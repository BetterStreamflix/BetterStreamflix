package com.dskja.betterstreamflix.remotefind

import android.net.Uri
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Deep links for Remote Find Phone.
 *
 * Preferred QR (custom scheme, no long-lived token):
 * `betterstreamflix://remotefind?ws=…&code=…`
 *
 * HTTPS mirror is code-only (phone discovers WS on LAN):
 * `https://betterstreamflix.app/remotefind?code=…`
 *
 * Legacy links with `token=` are still parsed when present.
 */
object RemoteFindLinks {
    const val SCHEME = "betterstreamflix"
    const val HOST = "remotefind"
    const val HTTPS_HOST = "betterstreamflix.app"
    private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")
    private val LINK_PATTERN = Regex(
        "^(?:(?:betterstreamflix|streamflix)://remotefind\\?([^#]+)|" +
            "https?://(?:www\\.)?betterstreamflix\\.app/remotefind\\?([^#]+))",
        RegexOption.IGNORE_CASE,
    )

    data class Target(
        val wsUrl: String?,
        val token: String?,
        val code: String?,
    )

    fun buildDeepLink(wsUrl: String, code: String?): String {
        return buildString {
            append("$SCHEME://$HOST?")
            append("ws=").append(encode(wsUrl.trim()))
            val normalized = RemoteFindCode.normalize(code)
            if (normalized != null) {
                append("&code=").append(encode(normalized))
            }
        }
    }

    /** HTTPS App Link: code only — never embed the session token. */
    fun buildHttpsLink(code: String?): String {
        val normalized = RemoteFindCode.normalize(code) ?: return "https://$HTTPS_HOST/$HOST"
        return "https://$HTTPS_HOST/$HOST?code=${encode(normalized)}"
    }

    @Deprecated("Use buildDeepLink(wsUrl, code) — token must not appear in QR/UDP")
    fun buildDeepLink(wsUrl: String, token: String, code: String?): String =
        buildDeepLink(wsUrl, code)

    @Deprecated("Use buildHttpsLink(code) — token must not appear in HTTPS mirrors")
    fun buildHttpsLink(wsUrl: String, token: String, code: String?): String =
        buildHttpsLink(code)

    fun parse(uri: Uri?): Target? = parse(uri?.toString())

    fun parse(raw: String?): Target? {
        if (raw.isNullOrBlank()) return null
        val match = LINK_PATTERN.find(raw.trim()) ?: return null
        val query = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
        val params = parseQuery(query)
        val ws = params["ws"]?.trim()?.takeIf { it.isNotBlank() }
        val token = params["token"]?.trim()?.takeIf { TOKEN_PATTERN.matches(it) }
        val code = RemoteFindCode.normalize(params["code"])
        if (ws == null && code == null && token == null) return null
        if (ws != null && !ws.startsWith("ws://") && !ws.startsWith("wss://")) return null
        return Target(wsUrl = ws, token = token, code = code)
    }

    fun isValidToken(token: String?): Boolean =
        !token.isNullOrBlank() && TOKEN_PATTERN.matches(token.trim())

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = decode(part.substring(0, idx))
            val value = decode(part.substring(idx + 1))
            key to value
        }.toMap()
    }
}
