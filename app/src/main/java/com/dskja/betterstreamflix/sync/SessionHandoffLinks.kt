package com.dskja.betterstreamflix.sync

import android.net.Uri

object SessionHandoffLinks {
    const val SCHEME = "betterstreamflix"
    const val HOST = "handoff"
    const val HTTPS_HOST = "betterstreamflix.app"
    private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")
    private val LINK_PATTERN = Regex(
        "^(?:(?:betterstreamflix|streamflix)://handoff(?:/([^?&#]+)|\\?token=([^&#]+)|\\?code=([^&#]+))|" +
            "https?://(?:www\\.)?betterstreamflix\\.app/handoff(?:/([^?&#]+)|\\?token=([^&#]+)|\\?code=([^&#]+)))",
        RegexOption.IGNORE_CASE,
    )

    fun buildUri(token: String): String =
        "https://$HTTPS_HOST/handoff/${token.trim()}"

    fun buildCodeUri(code: String): String {
        val cleaned = SessionHandoffCodes.normalize(code) ?: code.trim()
        return "https://$HTTPS_HOST/handoff?code=$cleaned"
    }

    fun buildCustomSchemeUri(token: String): String =
        "$SCHEME://$HOST/${token.trim()}"

    fun parseToken(uri: Uri?): String? = parseToken(uri?.toString())

    fun parseToken(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val match = LINK_PATTERN.find(raw.trim()) ?: return null
        val token = match.groupValues.drop(1).firstOrNull {
            it.isNotBlank() && TOKEN_PATTERN.matches(it.trim())
        }?.trim()
        return token
    }

    fun parseCode(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        SessionHandoffCodes.normalize(raw)?.let { return it }
        val match = LINK_PATTERN.find(raw.trim()) ?: return null
        val code = match.groupValues.drop(1).firstOrNull {
            it.isNotBlank() && SessionHandoffCodes.normalize(it) != null
        }
        return SessionHandoffCodes.normalize(code)
    }

    fun isValidToken(token: String?): Boolean =
        !token.isNullOrBlank() && TOKEN_PATTERN.matches(token.trim())
}
