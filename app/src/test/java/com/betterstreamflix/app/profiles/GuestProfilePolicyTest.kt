package com.betterstreamflix.app.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestProfilePolicyTest {

    private fun guest(
        createdAt: Long,
        expiresAt: Long? = GuestProfilePolicy.expiresAtMillis(createdAt),
    ) = UserProfile(
        id = "guest_abc",
        displayName = "Guest",
        avatarKey = GuestProfilePolicy.AVATAR_KEY,
        isGuest = true,
        expiresAtMillis = expiresAt,
        createdAtMillis = createdAt,
        updatedAtMillis = createdAt,
    )

    @Test
    fun ttlIsTwoHours() {
        assertEquals(2L * 60L * 60L * 1000L, GuestProfilePolicy.TTL_MS)
    }

    @Test
    fun expiresAtIsCreatedPlusTtl() {
        val created = 1_000_000L
        assertEquals(created + GuestProfilePolicy.TTL_MS, GuestProfilePolicy.expiresAtMillis(created))
    }

    @Test
    fun isExpiredUsesExpiresAtMillis() {
        val created = 1_000L
        val profile = guest(created, expiresAt = created + 50)
        assertFalse(GuestProfilePolicy.isExpired(profile, nowMillis = created + 49))
        assertTrue(GuestProfilePolicy.isExpired(profile, nowMillis = created + 50))
        assertTrue(GuestProfilePolicy.isExpired(profile, nowMillis = created + 5_000))
    }

    @Test
    fun isExpiredFallsBackToCreatedPlusTtl() {
        val created = 10_000L
        val profile = guest(created, expiresAt = null)
        assertFalse(
            GuestProfilePolicy.isExpired(
                profile,
                nowMillis = created + GuestProfilePolicy.TTL_MS - 1,
            ),
        )
        assertTrue(
            GuestProfilePolicy.isExpired(
                profile,
                nowMillis = created + GuestProfilePolicy.TTL_MS,
            ),
        )
    }

    @Test
    fun nonGuestNeverExpires() {
        val profile = UserProfile(
            id = "default",
            displayName = "Default",
            avatarKey = "crimson",
            isGuest = false,
            expiresAtMillis = 1L,
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        )
        assertFalse(GuestProfilePolicy.isExpired(profile, nowMillis = 9_999_999L))
        assertEquals(0L, GuestProfilePolicy.remainingMillis(profile, nowMillis = 9_999_999L))
    }

    @Test
    fun remainingAndFormat() {
        val created = 0L
        val profile = guest(created)
        val remaining = GuestProfilePolicy.remainingMillis(profile, nowMillis = 30L * 60L * 1000L)
        assertEquals(GuestProfilePolicy.TTL_MS - 30L * 60L * 1000L, remaining)
        assertEquals("1h 30m", GuestProfilePolicy.formatRemaining(remaining))
        assertEquals("ending", GuestProfilePolicy.formatRemaining(0L))
        assertEquals("12m", GuestProfilePolicy.formatRemaining(11 * 60_000L + 1))
    }
}
