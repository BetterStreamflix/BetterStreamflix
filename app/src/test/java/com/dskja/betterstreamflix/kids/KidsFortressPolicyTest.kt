package com.dskja.betterstreamflix.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsFortressPolicyTest {

    @Test
    fun sessionKind_guestWinsOverKidsFlag() {
        assertEquals(
            KidsFortressPolicy.SessionKind.GUEST,
            KidsFortressPolicy.sessionKind(isKids = true, isGuest = true),
        )
        assertEquals(
            KidsFortressPolicy.SessionKind.KIDS,
            KidsFortressPolicy.sessionKind(isKids = true, isGuest = false),
        )
        assertEquals(
            KidsFortressPolicy.SessionKind.ADULT,
            KidsFortressPolicy.sessionKind(isKids = false, isGuest = false),
        )
    }

    @Test
    fun canEnableKids_blocksGuest() {
        assertTrue(KidsFortressPolicy.canEnableKids(isGuest = false))
        assertFalse(KidsFortressPolicy.canEnableKids(isGuest = true))
    }

    @Test
    fun parentDashboardEligible_adultOnly() {
        assertTrue(
            KidsFortressPolicy.parentDashboardEligible(
                isKidsActive = false,
                isGuestActive = false,
            ),
        )
        assertFalse(
            KidsFortressPolicy.parentDashboardEligible(
                isKidsActive = true,
                isGuestActive = false,
            ),
        )
        assertFalse(
            KidsFortressPolicy.parentDashboardEligible(
                isKidsActive = false,
                isGuestActive = true,
            ),
        )
    }

    @Test
    fun downloadsNavVisible_alwaysTrueUnderFortress() {
        assertTrue(KidsFortressPolicy.downloadsNavVisible(isKids = true))
        assertTrue(KidsFortressPolicy.downloadsNavVisible(isKids = false))
    }

    @Test
    fun liveHubAllowed_hiddenForKids() {
        assertFalse(KidsFortressPolicy.liveHubAllowed(isKids = true))
        assertTrue(KidsFortressPolicy.liveHubAllowed(isKids = false))
    }

    @Test
    fun spoilerForced_andAgeFilter() {
        assertTrue(KidsFortressPolicy.spoilerForced(isKids = true))
        assertFalse(KidsFortressPolicy.spoilerForced(isKids = false))
        assertTrue(KidsFortressPolicy.ageFilteringActive(true, false))
        assertTrue(KidsFortressPolicy.ageFilteringActive(false, true))
        assertFalse(KidsFortressPolicy.ageFilteringActive(false, false))
        assertTrue(KidsFortressPolicy.hideUnratedForKids(true))
    }

    @Test
    fun effectiveCeiling_kidsDefaultsTo12() {
        assertEquals(
            12,
            KidsFortressPolicy.effectiveCeiling(
                isKids = true,
                profileMaxAge = null,
                parentalMaxAge = 16,
            ),
        )
        assertEquals(
            8,
            KidsFortressPolicy.effectiveCeiling(
                isKids = true,
                profileMaxAge = 8,
                parentalMaxAge = 16,
            ),
        )
        assertEquals(
            16,
            KidsFortressPolicy.effectiveCeiling(
                isKids = false,
                profileMaxAge = 8,
                parentalMaxAge = 16,
            ),
        )
        assertNull(
            KidsFortressPolicy.effectiveCeiling(
                isKids = false,
                profileMaxAge = null,
                parentalMaxAge = null,
            ),
        )
    }

    @Test
    fun bedtimeApplies_requiresKidsAndEnabled() {
        assertTrue(KidsFortressPolicy.bedtimeApplies(enabled = true, isKids = true))
        assertFalse(KidsFortressPolicy.bedtimeApplies(enabled = true, isKids = false))
        assertFalse(KidsFortressPolicy.bedtimeApplies(enabled = false, isKids = true))
    }

    @Test
    fun exitAndUnlockPinRules() {
        assertTrue(
            KidsFortressPolicy.exitChallengeRequired(
                leavingRestricted = true,
                hasHouseholdPin = true,
                hasProfilePin = false,
            ),
        )
        assertFalse(
            KidsFortressPolicy.exitChallengeRequired(
                leavingRestricted = false,
                hasHouseholdPin = true,
                hasProfilePin = true,
            ),
        )
        assertFalse(
            KidsFortressPolicy.kidsUnlockAllowed(
                hasHouseholdPin = false,
                hasProfilePin = false,
            ),
        )
        assertTrue(
            KidsFortressPolicy.kidsUnlockAllowed(
                hasHouseholdPin = false,
                hasProfilePin = true,
            ),
        )
    }

    @Test
    fun fortressHealthy_kidsNeedPinAndCeiling() {
        assertTrue(
            KidsFortressPolicy.fortressHealthy(
                isKids = true,
                hasExitPin = true,
                ceiling = 12,
            ),
        )
        assertFalse(
            KidsFortressPolicy.fortressHealthy(
                isKids = true,
                hasExitPin = false,
                ceiling = 12,
            ),
        )
        assertFalse(
            KidsFortressPolicy.fortressHealthy(
                isKids = true,
                hasExitPin = true,
                ceiling = null,
            ),
        )
        assertTrue(
            KidsFortressPolicy.fortressHealthy(
                isKids = false,
                hasExitPin = false,
                ceiling = null,
            ),
        )
    }

    @Test
    fun formatCardSummary_includesAgePinBedtimeCounts() {
        val line = KidsFortressPolicy.formatCardSummary(
            maxAge = 12,
            hasPin = true,
            bedtimeEnabled = true,
            bedtimeLocked = false,
            continueCount = 3,
            downloadCount = 2,
            pinLabel = "PIN",
            noPinLabel = "no PIN",
            bedtimeOnLabel = "Bed 20:00",
            bedtimeOffLabel = "Bed off",
            bedtimeLockedLabel = "Bed locked",
            continueLabel = "CW",
            downloadsLabel = "DL",
        )
        assertTrue(line.contains("≤12"))
        assertTrue(line.contains("PIN"))
        assertTrue(line.contains("Bed 20:00"))
        assertTrue(line.contains("CW 3"))
        assertTrue(line.contains("DL 2"))
    }
}
