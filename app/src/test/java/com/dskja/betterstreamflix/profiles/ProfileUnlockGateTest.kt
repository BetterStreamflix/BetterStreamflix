package com.dskja.betterstreamflix.profiles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProfileUnlockGateTest {

    @Before
    fun reset() {
        ProfileUnlockGate.clear()
    }

    @Test
    fun requiresGate_forPinLockedProfile() {
        val profile = sample(pinHash = "abc")
        assertTrue(ProfileUnlockGate.requiresGate(profile))
    }

    @Test
    fun requiresGate_forKidsProfile() {
        val profile = sample(isKids = true)
        assertTrue(ProfileUnlockGate.requiresGate(profile))
    }

    @Test
    fun requiresGate_falseAfterUnlock() {
        val profile = sample(pinHash = "abc", isKids = true)
        ProfileUnlockGate.markUnlocked(profile.id)
        assertFalse(ProfileUnlockGate.requiresGate(profile))
    }

    @Test
    fun requiresGate_falseForOpenAdultProfile() {
        assertFalse(ProfileUnlockGate.requiresGate(sample()))
    }

    private fun sample(
        id: String = "p1",
        isKids: Boolean = false,
        pinHash: String? = null,
    ) = UserProfile(
        id = id,
        displayName = "Test",
        avatarKey = "crimson",
        isKids = isKids,
        pinHash = pinHash,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )
}
