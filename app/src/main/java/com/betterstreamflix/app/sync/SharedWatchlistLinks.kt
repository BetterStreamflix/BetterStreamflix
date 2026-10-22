package com.betterstreamflix.app.sync

import android.net.Uri

object SharedWatchlistLinks {
    const val SCHEME = "betterstreamflix"
    const val HOST = "list"
    /** Shareable https host (App Links / chat apps); custom scheme still accepted. */
    const val HTTPS_HOST = "betterstreamflix.app"
    private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")
    // Prefer betterstreamflix://; keep streamflix:// for legacy deep links already in the wild.
    private val LINK_PATTERN = Regex(
        "^(?:(?:betterstreamflix|streamflix)://list(?:/([^?&#]+)|\\?token=([^&#]+))|" +
            "https?://(?:www\\.)?betterstreamflix\\.app/list(?:/([^?&#]+)|\\?token=([^&#]+)))",
        RegexOption.IGNORE_CASE,
    )

    /** Prefer https so messages/email can open the app via VIEW filters. */
    fun buildUri(token: String): String =
        "https://$HTTPS_HOST/list/${token.trim()}"

    fun buildCustomSchemeUri(token: String): String =
        "$SCHEME://$HOST/${token.trim()}"

    fun parseToken(uri: Uri?): String? =
        parseToken(uri?.toString())

    /** JVM-safe parser for deep links (also used by unit tests). */
    fun parseToken(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val match = LINK_PATTERN.find(raw.trim()) ?: return null
        val token = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return token.takeIf { TOKEN_PATTERN.matches(it) }
    }

    fun isValidToken(token: String?): Boolean =
        !token.isNullOrBlank() && TOKEN_PATTERN.matches(token.trim())
}
