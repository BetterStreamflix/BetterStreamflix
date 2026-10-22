package com.betterstreamflix.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlayerCleanExitTest {

    @Before
    fun clearState() {
        // Drain any leftover restore token from prior tests.
        PlayerCleanExit.consumeHomeFocusRestore()
        PlayerCleanExit.consumeLeftPlayer()
    }

    @Test
    fun silenceSteps_muteBeforePause() {
        val steps = PlayerCleanExit.silenceSteps
        assertTrue(steps.indexOf("mute_volume") < steps.indexOf("pause_playback"))
        assertTrue(steps.contains("silence_trailers"))
        assertTrue(steps.contains("release_media_session"))
    }

    @Test
    fun rememberAndConsume_homeFocusToken() {
        PlayerCleanExit.rememberHomeFocus("ep-42", rowHint = "continue_watching")
        assertTrue(PlayerCleanExit.shouldRestoreHomeFocus())
        assertEquals("ep-42", PlayerCleanExit.peekHomeFocusToken()?.itemKey)
        val token = PlayerCleanExit.consumeHomeFocusRestore()
        assertEquals("ep-42", token?.itemKey)
        assertEquals("continue_watching", token?.rowHint)
        assertFalse(PlayerCleanExit.shouldRestoreHomeFocus())
        assertNull(PlayerCleanExit.consumeHomeFocusRestore())
    }

    @Test
    fun noteLeavingPlayer_marksLeftFlag() {
        PlayerCleanExit.noteLeavingPlayer(returningToHome = true)
        assertTrue(PlayerCleanExit.consumeLeftPlayer())
        assertFalse(PlayerCleanExit.consumeLeftPlayer())
    }

    @Test
    fun noteLeavingPlayer_toDetailClearsHomeRestore() {
        PlayerCleanExit.rememberHomeFocus("ep-9")
        PlayerCleanExit.noteLeavingPlayer(returningToHome = false)
        assertFalse(PlayerCleanExit.shouldRestoreHomeFocus())
        assertNull(PlayerCleanExit.consumeHomeFocusRestore())
    }

    @Test
    fun noteLeavingPlayer_toHomeKeepsRestore() {
        PlayerCleanExit.rememberHomeFocus("ep-9")
        PlayerCleanExit.noteLeavingPlayer(returningToHome = true)
        assertTrue(PlayerCleanExit.shouldRestoreHomeFocus())
        assertEquals("ep-9", PlayerCleanExit.consumeHomeFocusRestore()?.itemKey)
    }

    @Test
    fun rememberHomeFocus_ignoresBlankKeys() {
        PlayerCleanExit.rememberHomeFocus("  ")
        PlayerCleanExit.rememberHomeFocus(null)
        assertFalse(PlayerCleanExit.shouldRestoreHomeFocus())
    }

    @Test
    fun featuredWatchKey_isStable() {
        PlayerCleanExit.rememberHomeFocus(PlayerCleanExit.FEATURED_WATCH_KEY)
        assertEquals(
            PlayerCleanExit.FEATURED_WATCH_KEY,
            PlayerCleanExit.consumeHomeFocusRestore()?.itemKey,
        )
    }
}
