package com.betterstreamflix.app.profiles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProfileUnlockGateTest {

    private var fakeElapsed = 0L

    @Before
    fun reset() {
        fakeElapsed = 1_000L
        ProfileUnlockGate.elapsedRealtimeMs = { fakeElapsed }
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

    @Test
    fun requiresGate_trueAfterIdleTimeout() {
        val profile = sample(pinHash = "abc")
        ProfileUnlockGate.markUnlocked(profile.id)
        assertFalse(ProfileUnlockGate.requiresGate(profile))

        fakeElapsed += ProfileUnlockGate.IDLE_TIMEOUT_MS + 1
        assertTrue(ProfileUnlockGate.requiresGate(profile))
    }

    @Test
    fun requiresGate_trueAfterBackgroundTimeout() {
        val profile = sample(isKids = true)
        ProfileUnlockGate.markUnlocked(profile.id)
        ProfileUnlockGate.markBackgrounded()

        // Advance less than idle-from-unlock but past background window by
        // keeping lastUnlock fresh: bump last unlock via remount simulation —
        // background clock alone should expire when elapsed exceeds timeout.
        fakeElapsed += ProfileUnlockGate.IDLE_TIMEOUT_MS + 1
        ProfileUnlockGate.markForeground()
        assertTrue(ProfileUnlockGate.requiresGate(profile))
    }

    @Test
    fun markForeground_withinTimeoutKeepsUnlock() {
        val profile = sample(pinHash = "abc")
        ProfileUnlockGate.markUnlocked(profile.id)
        ProfileUnlockGate.markBackgrounded()
        fakeElapsed += ProfileUnlockGate.IDLE_TIMEOUT_MS / 2
        ProfileUnlockGate.markForeground()
        assertFalse(ProfileUnlockGate.requiresGate(profile))
    }

    @Test
    fun kidsWithoutPin_requiresGateAgainAfterIdleClear() {
        // KF-019: kids with no profile PIN must re-challenge after idle clears unlock.
        val kids = sample(isKids = true, pinHash = null)
        ProfileUnlockGate.markUnlocked(kids.id)
        assertFalse(ProfileUnlockGate.requiresGate(kids))
        fakeElapsed += ProfileUnlockGate.IDLE_TIMEOUT_MS + 1
        assertTrue(ProfileUnlockGate.requiresGate(kids))
        assertTrue(kids.pinHash == null && kids.isKids)
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
