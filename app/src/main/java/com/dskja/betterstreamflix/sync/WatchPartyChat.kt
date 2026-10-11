package com.dskja.betterstreamflix.sync

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

    fun isAllowedReaction(emoji: String?): Boolean =
        !emoji.isNullOrBlank() && emoji.trim() in REACTIONS

    fun sanitizeChatBody(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return trimmed.take(MAX_BODY_CHARS)
    }

    fun sanitizeDisplayName(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
            .replace(Regex("[\\p{Cntrl}]"), "")
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
