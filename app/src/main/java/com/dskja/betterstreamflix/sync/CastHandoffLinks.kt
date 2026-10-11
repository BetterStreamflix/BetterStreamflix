package com.dskja.betterstreamflix.sync

import android.net.Uri

object CastHandoffLinks {
    const val SCHEME = "betterstreamflix"
    /** Distinct from Session Handoff (`handoff`) — this is TV→Phone Cast Hand-off. */
    const val HOST = "casthand"
    const val HTTPS_HOST = "betterstreamflix.app"
    private val LINK_PATTERN = Regex(
        "^(?:(?:betterstreamflix|streamflix)://casthand(?:/([^?&#]+)|\\?code=([^&#]+))|" +
            "https?://(?:www\\.)?betterstreamflix\\.app/casthand(?:/([^?&#]+)|\\?code=([^&#]+)))",
        RegexOption.IGNORE_CASE,
    )

    fun buildUri(code: String): String =
        "https://$HTTPS_HOST/$HOST/${normalizeShortCode(code) ?: code.trim().uppercase()}"

    fun buildCustomSchemeUri(code: String): String =
        "$SCHEME://$HOST/${normalizeShortCode(code) ?: code.trim().uppercase()}"

    fun parseShortCode(uri: Uri?): String? =
        parseShortCode(uri?.toString())

    fun parseShortCode(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        normalizeShortCode(trimmed)?.let { return it }
        val match = LINK_PATTERN.find(trimmed) ?: return null
        val code = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return normalizeShortCode(code)
    }

    fun normalizeShortCode(raw: String?): String? =
        CompanionShortCodes.normalize(raw)

    fun isValidShortCode(code: String?): Boolean =
        normalizeShortCode(code) != null

    /** Short codes: A–Z and 2–9 (no 0/1/O/I/L confusion on the wire). */
    fun newShortCode(random: (() -> Int)? = null): String =
        if (random != null) CompanionShortCodes.newShortCode(random)
        else CompanionShortCodes.newShortCode()
}
