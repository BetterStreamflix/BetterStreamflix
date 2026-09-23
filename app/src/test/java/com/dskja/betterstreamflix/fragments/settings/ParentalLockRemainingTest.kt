package com.dskja.betterstreamflix.fragments.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ParentalLockRemainingTest {

    @Test
    fun roundsUpPartialMinute() {
        assertEquals(1, ParentalLockRemaining.minutes(1L))
        assertEquals(1, ParentalLockRemaining.minutes(59_999L))
        assertEquals(2, ParentalLockRemaining.minutes(60_001L))
    }

    @Test
    fun neverReturnsZero() {
        assertEquals(1, ParentalLockRemaining.minutes(0L))
    }
}
