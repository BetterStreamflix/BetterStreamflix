package com.dskja.betterstreamflix.support

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
        assertFalse(ThanksAfterBingePolicy.isSeasonFinale(currentSeasonNumber = null, nextSeasonNumber = 2))
        assertFalse(ThanksAfterBingePolicy.isSeasonFinale(currentSeasonNumber = null, nextSeasonNumber = null))
    }
}
