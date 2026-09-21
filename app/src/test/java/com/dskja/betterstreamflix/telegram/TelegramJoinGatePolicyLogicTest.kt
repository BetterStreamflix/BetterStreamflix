package com.dskja.betterstreamflix.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure policy math tests that do not touch SharedPreferences / Android.
 */
class TelegramJoinGatePolicyLogicTest {

    @Test
    fun channelVersionSevenForcesRepromptForOlderDismissals() {
        assertTrue(TelegramJoinGatePolicy.CHANNEL_VERSION >= 7)
        assertTrue(6 < TelegramJoinGatePolicy.CHANNEL_VERSION)
    }

    @Test
    fun dwellWindowOptionalInvite() {
        assertEquals(0L, TelegramJoinGatePolicy.MIN_OPEN_DWELL_MS)
    }

    @Test
    fun urlsPointAtBetterStreamflixChannel() {
        assertTrue(TelegramJoinGatePolicy.WEB_URL.contains("BetterStreamflix"))
        assertTrue(TelegramJoinGatePolicy.APP_URL.contains("BetterStreamflix"))
        assertFalse(TelegramJoinGatePolicy.WEB_URL.contains(" "))
        assertTrue(TelegramJoinGatePolicy.DISCORD_URL.contains("discord.gg"))
    }
}
