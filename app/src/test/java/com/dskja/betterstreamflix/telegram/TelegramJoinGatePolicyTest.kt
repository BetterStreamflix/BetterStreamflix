package com.dskja.betterstreamflix.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramJoinGatePolicyTest {

    @Test
    fun channelHandleMatchesSupportUrl() {
        assertEquals("BetterStreamflix", TelegramJoinGatePolicy.CHANNEL_HANDLE)
        assertTrue(TelegramJoinGatePolicy.WEB_URL.contains(TelegramJoinGatePolicy.CHANNEL_HANDLE))
        assertTrue(TelegramJoinGatePolicy.APP_URL.contains(TelegramJoinGatePolicy.CHANNEL_HANDLE))
    }

    @Test
    fun channelVersionForcesReprompt() {
        assertTrue(TelegramJoinGatePolicy.CHANNEL_VERSION >= 7)
    }

    @Test
    fun inviteIsOptionalNoDwell() {
        assertTrue(TelegramJoinGatePolicy.MIN_OPEN_DWELL_MS >= 0L)
        assertTrue(TelegramJoinGatePolicy.canConfirm())
    }

    @Test
    fun phaseEnumHasExpectedOrder() {
        val values = TelegramJoinGatePolicy.Phase.entries
        assertEquals(3, values.size)
        assertEquals(TelegramJoinGatePolicy.Phase.LOCKED, values[0])
        assertEquals(TelegramJoinGatePolicy.Phase.OPENED, values[1])
        assertEquals(TelegramJoinGatePolicy.Phase.UNLOCKED, values[2])
    }

    @Test
    fun urlsAreTelegramAndDiscord() {
        assertTrue(TelegramJoinGatePolicy.WEB_URL.startsWith("https://t.me/"))
        assertTrue(TelegramJoinGatePolicy.APP_URL.startsWith("tg://"))
        assertTrue(TelegramJoinGatePolicy.DISCORD_URL.contains("discord"))
    }
}
