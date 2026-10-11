package com.dskja.betterstreamflix.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndCardPolicyTest {

    @Test
    fun showsEndCardWhenFinishedAndEnded() {
        assertTrue(
            EndCardPolicy.shouldShowEndCard(
                reallyFinished = true,
                stateEnded = true,
                isLive = false,
                isCasting = false,
                resolveInFlight = false,
            ),
        )
    }

    @Test
    fun hidesEndCardForLiveCastOrResolve() {
        assertFalse(
            EndCardPolicy.shouldShowEndCard(
                reallyFinished = true,
                stateEnded = true,
                isLive = true,
                isCasting = false,
                resolveInFlight = false,
            ),
        )
        assertFalse(
            EndCardPolicy.shouldShowEndCard(
                reallyFinished = true,
                stateEnded = true,
                isLive = false,
                isCasting = true,
                resolveInFlight = false,
            ),
        )
        assertFalse(
            EndCardPolicy.shouldShowEndCard(
                reallyFinished = true,
                stateEnded = true,
                isLive = false,
                isCasting = false,
                resolveInFlight = true,
            ),
        )
    }

    @Test
    fun autoplayCountdownNeedsNextAndNotCancelled() {
        assertTrue(
            EndCardPolicy.shouldAutoplayAfterCountdown(
                autoplayEnabled = true,
                hasNextEpisode = true,
                countdownCancelled = false,
            ),
        )
        assertFalse(
            EndCardPolicy.shouldAutoplayAfterCountdown(
                autoplayEnabled = true,
                hasNextEpisode = false,
                countdownCancelled = false,
            ),
        )
        assertFalse(
            EndCardPolicy.shouldAutoplayAfterCountdown(
                autoplayEnabled = true,
                hasNextEpisode = true,
                countdownCancelled = true,
            ),
        )
        assertFalse(
            EndCardPolicy.shouldAutoplayAfterCountdown(
                autoplayEnabled = true,
                hasNextEpisode = true,
                countdownCancelled = false,
                bedtimeBlocks = true,
            ),
        )
    }

    @Test
    fun countdownLabelNeverNegative() {
        assertEquals(0, EndCardPolicy.countdownLabelSec(-3))
        assertEquals(8, EndCardPolicy.countdownLabelSec(8))
    }
}
