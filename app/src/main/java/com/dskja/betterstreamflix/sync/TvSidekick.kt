package com.dskja.betterstreamflix.sync

/**
 * TV Sidekick Phone — phone is scrubber / keyboard / party-chat; TV stays the screen.
 * Builds on Cast Room transport (token RPCs, no stream relay).
 */
object TvSidekick {

    const val SEEK_STEP_SHORT_MS: Long = 10_000L
    const val SEEK_STEP_LONG_MS: Long = 30_000L
    const val MAX_KEYBOARD_CHARS: Int = 80
    const val MAX_CHAT_CHARS: Int = 280
    const val MAX_DISPLAY_NAME: Int = 40

    const val KIND_CHAT: String = "chat"
    const val KIND_REACT: String = "react"

    val REACTIONS: List<String> = listOf("😂", "🔥", "👏", "😮", "❤️", "👍")

    fun sanitizeKeyboard(raw: String?): String? {
        val cleaned = raw?.trim().orEmpty()
            .replace(Regex("[\\p{Cntrl}]"), "")
            .take(MAX_KEYBOARD_CHARS)
        return cleaned.takeIf { it.isNotEmpty() }
    }

    private val URL_PATTERN = Regex(
        "(?i)\\b(?:https?://|www\\.|betterstreamflix://|streamflix://)\\S+",
    )
    private val CONTROL_CHARS = Regex("[\\p{Cntrl}]")

    fun sanitizeChatBody(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
            .replace(CONTROL_CHARS, "")
            .replace(URL_PATTERN, "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (trimmed.isEmpty()) return null
        return trimmed.take(MAX_CHAT_CHARS)
    }

    fun sanitizeDisplayName(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
            .replace(Regex("[\\p{Cntrl}]"), "")
            .take(MAX_DISPLAY_NAME)
        return trimmed.ifBlank { "Guest" }
    }

    fun isAllowedReaction(emoji: String?): Boolean =
        !emoji.isNullOrBlank() && emoji.trim() in REACTIONS

    fun clampSeek(positionMs: Long, durationMs: Long? = null): Long {
        val floor = 0L
        val ceiling = durationMs?.takeIf { it > 0L } ?: Long.MAX_VALUE
        return positionMs.coerceIn(floor, ceiling)
    }

    fun relativeSeek(currentMs: Long, deltaMs: Long, durationMs: Long? = null): Long =
        clampSeek(currentMs + deltaMs, durationMs)

    fun formatPosition(positionMs: Long): String {
        val totalSec = (positionMs.coerceAtLeast(0L) / 1000L).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%d:%02d", m, s)
        }
    }

    /**
     * Parse scrubber input. Bare digits are seconds (e.g. `90` → 90s).
     * `m:ss` / `h:mm:ss` are also supported. Result is milliseconds.
     */
    fun parsePositionInput(raw: String?, durationMs: Long? = null): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parsed = if (text.all { it.isDigit() }) {
            val seconds = text.toLongOrNull() ?: return null
            (seconds.coerceAtLeast(0L)) * 1000L
        } else {
            val parts = text.split(':').map { it.trim() }
            if (parts.any { it.toIntOrNull() == null }) return null
            when (parts.size) {
                2 -> {
                    val m = parts[0].toLong()
                    val s = parts[1].toLong()
                    if (s !in 0..59) null else (m * 60 + s) * 1000L
                }
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].toLong()
                    if (m !in 0..59 || s !in 0..59) null else ((h * 3600) + (m * 60) + s) * 1000L
                }
                else -> null
            }
        } ?: return null
        return clampSeek(parsed, durationMs)
    }

    fun formatMessageLine(displayName: String, kind: String, body: String): String =
        "$displayName: $body"

    fun shouldApplySeek(payloadSeq: Long, lastAppliedSeq: Long, seekMs: Long?): Boolean =
        seekMs != null && seekMs >= 0L && payloadSeq > lastAppliedSeq

    fun shouldApplyKeyboard(payloadSeq: Long, lastAppliedSeq: Long, text: String?): Boolean =
        !text.isNullOrBlank() && payloadSeq > lastAppliedSeq
}
