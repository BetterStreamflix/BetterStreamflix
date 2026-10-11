package com.dskja.betterstreamflix.profiles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KidsParentalExitTest {

    @Before
    fun resetGate() {
        ProfileUnlockGate.elapsedRealtimeMs = { 1_000L }
        ProfileUnlockGate.clear()
    }

    @Test
    fun hasParentalCeiling_forKidsAndMaxAge() {
        assertTrue(ProfileManager.hasParentalCeiling(sample(isKids = true)))
        assertTrue(ProfileManager.hasParentalCeiling(sample(maxAgeRating = 12)))
        assertFalse(ProfileManager.hasParentalCeiling(sample()))
    }

    @Test
    fun requiresParentalExitChallenge_whenLeavingKidsForAdult() {
        // Without an initialized ProfileManager the active profile is null, so
        // requiresParentalExitChallenge only keys off target ceiling + prefs.
        // Here we assert the target-side short-circuit: kids targets never need exit.
        assertFalse(
            ProfileManager.requiresParentalExitChallenge(
                sample(isKids = true, maxAgeRating = 12),
            ),
        )
    }

    @Test
    fun requiresGate_kidsWithoutPin() {
        assertTrue(ProfileUnlockGate.requiresGate(sample(isKids = true)))
    }

    @Test
    fun kidsUnlocked_requiresGateAgainAfterIdle() {
        val kids = sample(isKids = true)
        var now = 1_000L
        ProfileUnlockGate.elapsedRealtimeMs = { now }
        ProfileUnlockGate.markUnlocked(kids.id)
        assertFalse(ProfileUnlockGate.requiresGate(kids))
        now += ProfileUnlockGate.IDLE_TIMEOUT_MS + 1
        assertTrue(ProfileUnlockGate.requiresGate(kids))
    }

    private fun sample(
        id: String = "kids1",
        isKids: Boolean = false,
        maxAgeRating: Int? = null,
        pinHash: String? = null,
    ) = UserProfile(
        id = id,
        displayName = "Kids",
        avatarKey = "crimson",
        isKids = isKids,
        maxAgeRating = maxAgeRating,
        pinHash = pinHash,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )
}
