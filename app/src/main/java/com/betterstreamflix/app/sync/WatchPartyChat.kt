package com.betterstreamflix.app.sync

/**
 * Chat + emoji reacts for Watch-Party 2.0.
 * Bodies are short text or a curated emoji — never stream URLs.
 */
object WatchPartyChat {
    const val KIND_CHAT = "chat"
    const val KIND_REACT = "react"
    const val MAX_BODY_CHARS = 280
    const val MAX_DISPLAY_NAME = 40

    val REACTIONS: List<String> = listOf("😂", "🔥", "👏", "😮", "❤️", "👍")

    private val URL_PATTERN = Regex(
        "(?i)\\b(?:https?://|www\\.|betterstreamflix://|streamflix://)\\S+",
    )
    private val CONTROL_CHARS = Regex("[\\p{Cntrl}]")

    fun isAllowedReaction(emoji: String?): Boolean =
        !emoji.isNullOrBlank() && emoji.trim() in REACTIONS

    fun sanitizeChatBody(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
            .replace(CONTROL_CHARS, "")
            .replace(URL_PATTERN, "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (trimmed.isEmpty()) return null
        return trimmed.take(MAX_BODY_CHARS)
    }

    fun sanitizeDisplayName(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
            .replace(CONTROL_CHARS, "")
            .take(MAX_DISPLAY_NAME)
        return trimmed.ifBlank { "Guest" }
    }

    fun formatMessageLine(message: WatchPartyMessagePayload): String {
        return when (message.kind) {
            KIND_REACT -> "${message.displayName}: ${message.body}"
            else -> "${message.displayName}: ${message.body}"
        }
    }
}
