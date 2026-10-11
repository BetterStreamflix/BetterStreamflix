package com.dskja.betterstreamflix.sync

import android.net.Uri

object WatchPartyLinks {
    const val SCHEME = "betterstreamflix"
    const val HOST = "party"
    const val HTTPS_HOST = "betterstreamflix.app"
    private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")
    private val LINK_PATTERN = Regex(
        "^(?:(?:betterstreamflix|streamflix)://party(?:/([^?&#]+)|\\?token=([^&#]+))|" +
            "https?://(?:www\\.)?betterstreamflix\\.app/party(?:/([^?&#]+)|\\?token=([^&#]+)))",
        RegexOption.IGNORE_CASE,
    )

    fun buildUri(token: String): String =
        "https://$HTTPS_HOST/party/${token.trim()}"

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

    fun newShortCode(random: () -> Int = { kotlin.random.Random.nextInt(0, 32) }): String =
        CastRoomQueue.newShortCode(random)
}
