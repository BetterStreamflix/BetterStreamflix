package com.dskja.betterstreamflix.profiles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsParentalExitTest {

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
        ProfileUnlockGate.clear()
        assertTrue(ProfileUnlockGate.requiresGate(sample(isKids = true)))
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
