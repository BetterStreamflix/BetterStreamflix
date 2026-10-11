package com.dskja.betterstreamflix.sync

import android.net.Uri

object SharedWatchlistLinks {
    const val SCHEME = "betterstreamflix"
    const val HOST = "list"
    private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")
    private val LINK_PATTERN = Regex(
        "^(?:betterstreamflix|streamflix)://list(?:/([^?&#]+)|\\?token=([^&#]+))",
        RegexOption.IGNORE_CASE,
    )

    fun buildUri(token: String): String =
        "$SCHEME://$HOST/${token.trim()}"

    fun parseToken(uri: Uri?): String? =
        parseToken(uri?.toString())

    /** JVM-safe parser for deep links (also used by unit tests). */
    fun parseToken(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val match = LINK_PATTERN.find(raw.trim()) ?: return null
        val token = match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
        return token.takeIf { TOKEN_PATTERN.matches(it) }
    }

    fun isValidToken(token: String?): Boolean =
        !token.isNullOrBlank() && TOKEN_PATTERN.matches(token.trim())
}
