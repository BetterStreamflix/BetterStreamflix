package com.dskja.betterstreamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCoverResolverTest {

    @Test
    fun resolveSync_keepsExistingPoster() {
        val out = ChannelCoverResolver.resolveSync(
            channelName = "ESPN",
            existing = "https://example.com/espn.png",
            providerFallback = "https://fallback",
        )
        assertEquals("https://example.com/espn.png", out)
    }

    @Test
    fun resolveSync_fallsBackToInitialsAvatar() {
        val out = ChannelCoverResolver.resolveSync(
            channelName = "Mystery Channel",
            existing = null,
            providerFallback = null,
        )
        assertTrue(out.contains("ui-avatars.com"))
        assertTrue(out.contains("name="))
    }

    @Test
    fun normalize_collapsesPunctuation() {
        assertEquals("sky sports 1 hd", ChannelCoverResolver.normalize("Sky Sports 1 HD!"))
    }
}
