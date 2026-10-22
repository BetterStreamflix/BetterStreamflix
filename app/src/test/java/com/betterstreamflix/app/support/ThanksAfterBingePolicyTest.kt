package com.betterstreamflix.app.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ThanksAfterBingePolicyTest {

    @Test
    fun cooldownIsSixtyDays() {
        assertEquals(TimeUnit.DAYS.toMillis(60), ThanksAfterBingePolicy.COOLDOWN_MS)
        assertEquals(60L * 24 * 60 * 60 * 1000, ThanksAfterBingePolicy.COOLDOWN_MS)
    }

    @Test
    fun maxShowsIsSmallSoftCap() {
        assertTrue(ThanksAfterBingePolicy.MAX_SHOWS in 1..5)
    }

    @Test
    fun notFinaleWhenNextUnknown() {
        assertFalse(ThanksAfterBingePolicy.isSeasonFinale(currentSeasonNumber = 2, nextSeasonNumber = null))
    }

    @Test
    fun notFinaleWhenNextIsSpecialSeasonZero() {
        assertFalse(ThanksAfterBingePolicy.isSeasonFinale(currentSeasonNumber = 2, nextSeasonNumber = 0))
    }

    @Test
    fun seasonFinaleWhenNextSeasonDiffers() {
        assertTrue(ThanksAfterBingePolicy.isSeasonFinale(currentSeasonNumber = 1, nextSeasonNumber = 2))
        assertTrue(ThanksAfterBingePolicy.isSeasonFinale(currentSeasonNumber = 3, nextSeasonNumber = 1))
    }

    @Test
    fun notFinaleMidSeason() {
        assertFalse(ThanksAfterBingePolicy.isSeasonFinale(currentSeasonNumber = 2, nextSeasonNumber = 2))
    }

    @Test
    fun notFinaleWithoutCurrentSeason() {
        // TB-008: null season metadata must not count as a finale.
        assertFalse(ThanksAfterBingePolicy.isSeasonFinale(currentSeasonNumber = null, nextSeasonNumber = 2))
        assertFalse(ThanksAfterBingePolicy.isSeasonFinale(currentSeasonNumber = null, nextSeasonNumber = null))
    }

    /** TB-006: persistence/cooldown/cap without Android prefs. */
    @Test
    fun shouldShowState_respectsCooldownDismissAndCap() {
        val now = 1_000_000L
        assertTrue(
            ThanksAfterBingePolicy.shouldShowState(
                isKids = false,
                isGuest = false,
                neverShow = false,
                showCount = 0,
                lastShownAtMs = 0L,
                lastDismissedAtMs = 0L,
                nowMs = now,
            ),
        )
        assertFalse(
            ThanksAfterBingePolicy.shouldShowState(
                isKids = false,
                isGuest = false,
                neverShow = false,
                showCount = 0,
                lastShownAtMs = now - 1_000L,
                lastDismissedAtMs = 0L,
                nowMs = now,
            ),
        )
        assertTrue(
            ThanksAfterBingePolicy.shouldShowState(
                isKids = false,
                isGuest = false,
                neverShow = false,
                showCount = 1,
                lastShownAtMs = now - ThanksAfterBingePolicy.COOLDOWN_MS,
                lastDismissedAtMs = 0L,
                nowMs = now,
            ),
        )
        assertFalse(
            ThanksAfterBingePolicy.shouldShowState(
                isKids = false,
                isGuest = false,
                neverShow = true,
                showCount = 0,
                lastShownAtMs = 0L,
                lastDismissedAtMs = 0L,
                nowMs = now,
            ),
        )
        assertFalse(
            ThanksAfterBingePolicy.shouldShowState(
                isKids = false,
                isGuest = false,
                neverShow = false,
                showCount = ThanksAfterBingePolicy.MAX_SHOWS,
                lastShownAtMs = 0L,
                lastDismissedAtMs = 0L,
                nowMs = now,
            ),
        )
        assertFalse(
            ThanksAfterBingePolicy.shouldShowState(
                isKids = false,
                isGuest = true,
                neverShow = false,
                showCount = 0,
                lastShownAtMs = 0L,
                lastDismissedAtMs = 0L,
                nowMs = now,
            ),
        )
    }
}
