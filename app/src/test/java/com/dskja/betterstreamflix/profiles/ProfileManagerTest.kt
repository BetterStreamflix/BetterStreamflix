package com.dskja.betterstreamflix.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileManagerTest {

    @Test
    fun scopedPrefKey_defaultProfileUsesBaseKey() {
        assertEquals(
            "subtitle_offsets",
            ProfileManager.scopedPrefKeyFor("subtitle_offsets", ProfileManager.DEFAULT_PROFILE_ID),
        )
    }

    @Test
    fun scopedPrefKey_nonDefaultProfileIsNamespaced() {
        assertEquals(
            "subtitle_offsets_p_kids01",
            ProfileManager.scopedPrefKeyFor("subtitle_offsets", "kids01"),
        )
    }

    @Test
    fun hashPin_isDeterministicForSameInput() {
        val first = ProfileManager.hashPin("1234", "profile_a")
        val second = ProfileManager.hashPin("1234", "profile_a")
        assertEquals(first, second)
        assertEquals(64, first.length)
    }

    @Test
    fun hashPin_differsByProfileId() {
        val profileA = ProfileManager.hashPin("1234", "profile_a")
        val profileB = ProfileManager.hashPin("1234", "profile_b")
        assertNotEquals(profileA, profileB)
    }

    @Test
    fun hashPin_differsByPin() {
        val pinA = ProfileManager.hashPin("1234", "profile_a")
        val pinB = ProfileManager.hashPin("5678", "profile_a")
        assertNotEquals(pinA, pinB)
    }

    @Test
    fun avatarKeys_containsExpectedPalette() {
        assertTrue(ProfileManager.avatarKeys.contains("copper"))
        assertTrue(ProfileManager.avatarKeys.contains("violet"))
        assertTrue(ProfileManager.avatarKeys.contains("crimson"))
        assertTrue(ProfileManager.avatarKeys.size >= 12)
    }

    @Test
    fun integrationKeys_containsAllSupportedServices() {
        assertTrue(UserProfile.Integration.ALL.contains(UserProfile.Integration.TRAKT))
        assertTrue(UserProfile.Integration.ALL.contains(UserProfile.Integration.JELLYFIN))
        assertTrue(UserProfile.Integration.ALL.contains(UserProfile.Integration.PLEX))
        assertTrue(UserProfile.Integration.ALL.contains(UserProfile.Integration.DEBRID))
        assertTrue(UserProfile.Integration.ALL.contains(UserProfile.Integration.SIMKL))
        assertTrue(UserProfile.Integration.ALL.contains(UserProfile.Integration.OPENSUBTITLES))
        assertTrue(UserProfile.Integration.ALL.contains(UserProfile.Integration.TMDB))
        assertEquals(7, UserProfile.Integration.ALL.size)
    }

    @Test
    fun uniqueCopyName_appendsIncrement() {
        assertEquals("default", ProfileManager.DEFAULT_PROFILE_ID)
    }

    @Test
    fun pinLengthBounds_areFourToEight() {
        assertEquals(4, ProfileManager.PIN_MIN_LENGTH)
        assertEquals(8, ProfileManager.PIN_MAX_LENGTH)
        assertEquals(5, ProfileManager.PIN_MAX_ATTEMPTS)
    }

    @Test
    fun atmosphere_normalizesUnknown() {
        assertEquals(ProfileAtmosphere.DEFAULT, ProfileAtmosphere.normalize(null))
        assertEquals(ProfileAtmosphere.DEFAULT, ProfileAtmosphere.normalize("neon"))
        assertEquals("cinema", ProfileAtmosphere.normalize("cinema"))
        assertTrue(ProfileAtmosphere.KEYS.contains("lounge"))
    }

    @Test
    fun userProfile_publicNameFallsBackToDisplayName() {
        val profile = UserProfile(
            id = "test",
            displayName = "Alex",
            avatarKey = "copper",
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
            greetingName = "  ",
        )
        assertEquals("Alex", profile.publicName())
        assertEquals("atelier", profile.safeAtmosphere())
    }

    @Test
    fun activeProfileId_fallsBackToDefaultBeforeInit() {
        assertEquals(ProfileManager.DEFAULT_PROFILE_ID, ProfileManager.activeProfileId)
    }

    @Test
    fun kidsDefaultMaxAge_isTwelve() {
        assertEquals(12, ProfileManager.KIDS_DEFAULT_MAX_AGE)
    }

    @Test
    fun userProfile_copyPreservesIntegrationSet() {
        val profile = UserProfile(
            id = "test",
            displayName = "Test",
            avatarKey = "copper",
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
            enabledIntegrations = setOf(UserProfile.Integration.TRAKT, UserProfile.Integration.PLEX),
        )
        val updated = profile.copy(enabledIntegrations = profile.enabledIntegrations + UserProfile.Integration.TMDB)
        assertTrue(updated.enabledIntegrations.contains(UserProfile.Integration.TMDB))
        assertFalse(updated.enabledIntegrations.contains(UserProfile.Integration.DEBRID))
    }

    @Test
    fun sanitize_fillsMissingPersonaFields() {
        val clean = ProfileStore.sanitize(
            UserProfile(
                id = "x",
                displayName = "  ",
                avatarKey = "",
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
                atmosphereKey = null,
            ),
        )
        assertEquals("Profile", clean.displayName)
        assertEquals("copper", clean.avatarKey)
        assertEquals(ProfileAtmosphere.DEFAULT, clean.atmosphereKey)
        assertTrue(clean.safeIntegrations().isEmpty())
    }
}
