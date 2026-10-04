package com.dskja.betterstreamflix.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileKidsCeilingTest {

    @Test
    fun becomingKids_tightensOpenCeiling() {
        assertEquals(
            12,
            ProfileManager.kidsCeilingToApply(
                becomingKids = true,
                previousWasKids = false,
                currentMaxAge = null,
                kidsCeiling = 12,
            ),
        )
    }

    @Test
    fun becomingKids_keepsStricterCeiling() {
        assertEquals(
            7,
            ProfileManager.kidsCeilingToApply(
                becomingKids = true,
                previousWasKids = false,
                currentMaxAge = 7,
                kidsCeiling = 12,
            ),
        )
    }

    @Test
    fun leavingKids_liftsOnlyTheKidsCeiling() {
        assertNull(
            ProfileManager.kidsCeilingToApply(
                becomingKids = false,
                previousWasKids = true,
                currentMaxAge = 12,
                kidsCeiling = 12,
            ),
        )
    }

    @Test
    fun leavingKids_keepsADifferentAdultCeiling() {
        assertEquals(
            16,
            ProfileManager.kidsCeilingToApply(
                becomingKids = false,
                previousWasKids = true,
                currentMaxAge = 16,
                kidsCeiling = 12,
            ),
        )
    }

    @Test
    fun adultToAdult_leavesCeilingUntouched() {
        assertEquals(
            16,
            ProfileManager.kidsCeilingToApply(
                becomingKids = false,
                previousWasKids = false,
                currentMaxAge = 16,
                kidsCeiling = 12,
            ),
        )
    }
}
