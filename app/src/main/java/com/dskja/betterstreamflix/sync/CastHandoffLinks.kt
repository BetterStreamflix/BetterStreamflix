package com.dskja.betterstreamflix.sync

import android.net.Uri

object CastHandoffLinks {
    const val SCHEME = "betterstreamflix"
    /** Distinct from Session Handoff (`handoff`) — this is TV→Phone Cast Hand-off. */
    const val HOST = "casthand"
    const val HTTPS_HOST = "betterstreamflix.app"
    private val SHORT_CODE_PATTERN = Regex("^[A-Z2-9]{6}$")
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

    fun normalizeShortCode(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().uppercase()
            .replace('0', 'O')
            .replace('1', 'I')
            .replace('L', 'I')
        return cleaned.takeIf { SHORT_CODE_PATTERN.matches(it) }
    }

    fun isValidShortCode(code: String?): Boolean =
        normalizeShortCode(code) != null

    /** Short codes: A–Z and 2–9 (no 0/1/O/I/L confusion on the wire). */
    fun newShortCode(random: () -> Int = { kotlin.random.Random.nextInt(0, 32) }): String {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return buildString(6) {
            repeat(6) { append(alphabet[random() % alphabet.length]) }
        }
    }
}
