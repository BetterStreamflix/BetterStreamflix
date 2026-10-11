package com.dskja.betterstreamflix.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchPartyChatTest {

    @Test
    fun allowsCuratedReactionsOnly() {
        assertTrue(WatchPartyChat.isAllowedReaction("🔥"))
        assertTrue(WatchPartyChat.isAllowedReaction("❤️"))
        assertFalse(WatchPartyChat.isAllowedReaction("🚀"))
        assertFalse(WatchPartyChat.isAllowedReaction(""))
        assertFalse(WatchPartyChat.isAllowedReaction(null))
    }

    @Test
    fun sanitizesChatBody() {
        assertEquals("hello", WatchPartyChat.sanitizeChatBody("  hello  "))
        assertNull(WatchPartyChat.sanitizeChatBody("   "))
        assertEquals(
            "a".repeat(WatchPartyChat.MAX_BODY_CHARS),
            WatchPartyChat.sanitizeChatBody("a".repeat(WatchPartyChat.MAX_BODY_CHARS + 20)),
        )
    }

    @Test
    fun sanitizesDisplayName() {
        assertEquals("Guest", WatchPartyChat.sanitizeDisplayName(null))
        assertEquals("Guest", WatchPartyChat.sanitizeDisplayName("   "))
        assertEquals("Ada", WatchPartyChat.sanitizeDisplayName(" Ada "))
        assertEquals(
            "x".repeat(WatchPartyChat.MAX_DISPLAY_NAME),
            WatchPartyChat.sanitizeDisplayName("x".repeat(80)),
        )
    }

    @Test
    fun formatsMessageLine() {
        val chat = WatchPartyMessagePayload(
            displayName = "Ada",
            kind = WatchPartyChat.KIND_CHAT,
            body = "hi",
        )
        assertEquals("Ada: hi", WatchPartyChat.formatMessageLine(chat))
        val react = WatchPartyMessagePayload(
            displayName = "Bob",
            kind = WatchPartyChat.KIND_REACT,
            body = "🔥",
        )
        assertEquals("Bob: 🔥", WatchPartyChat.formatMessageLine(react))
    }
}
