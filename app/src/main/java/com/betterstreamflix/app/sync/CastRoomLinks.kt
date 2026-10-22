package com.betterstreamflix.app.sync

import android.net.Uri

object CastRoomLinks {
    const val SCHEME = "betterstreamflix"
    const val HOST = "cast"
    const val HTTPS_HOST = "betterstreamflix.app"
    private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")
    private val LINK_PATTERN = Regex(
        "^(?:(?:betterstreamflix|streamflix)://cast(?:/([^?&#]+)|\\?token=([^&#]+))|" +
            "https?://(?:www\\.)?betterstreamflix\\.app/cast(?:/([^?&#]+)|\\?token=([^&#]+)))",
        RegexOption.IGNORE_CASE,
    )

    fun buildUri(token: String): String =
        "https://$HTTPS_HOST/cast/${token.trim()}"

    fun buildCustomSchemeUri(token: String): String =
        "$SCHEME://$HOST/${token.trim()}"

    fun parseToken(uri: Uri?): String? =
        parseToken(uri?.toString())

    fun parseToken(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val match = LINK_PATTERN.find(raw.trim()) ?: return null
        val token = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return token.takeIf { TOKEN_PATTERN.matches(it) }
    }

    fun isValidToken(token: String?): Boolean =
        !token.isNullOrBlank() && TOKEN_PATTERN.matches(token.trim())

    fun normalizeShortCode(raw: String?): String? =
        CompanionShortCodes.normalize(raw)

    fun isValidShortCode(code: String?): Boolean =
        normalizeShortCode(code) != null
}
