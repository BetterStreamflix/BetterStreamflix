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
    fun scopedPrefKey_themeIsNamespacedPerProfile() {
        assertEquals(
            "SELECTED_THEME",
            ProfileManager.scopedPrefKeyFor("SELECTED_THEME", ProfileManager.DEFAULT_PROFILE_ID),
        )
        assertEquals(
            "SELECTED_THEME_p_kids01",
            ProfileManager.scopedPrefKeyFor("SELECTED_THEME", "kids01"),
        )
    }

    @Test
    fun hashPin_isDeterministicForSameInput() {
        val first = ProfileManager.hashPin("1234", "profile_a")
        val second = ProfileManager.hashPin("1234", "profile_a")
        assertEquals(first, second)
        assertTrue(first.startsWith(PinHasher.V2_PREFIX))
        assertEquals(64, first.removePrefix(PinHasher.V2_PREFIX).length)
    }

    @Test
    fun hashPin_writesV2Prefix() {
        val hash = ProfileManager.hashPin("1234", "profile_a")
        assertTrue(hash.startsWith("v2:"))
        assertNotEquals(
            PinHasher.legacySha256("1234", "profile_a"),
            hash,
        )
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
    fun pinHasher_verifyAcceptsLegacySha256() {
        val legacy = PinHasher.legacySha256("1234", "profile_a")
        assertTrue(PinHasher.verify("1234", legacy, "profile_a"))
        assertFalse(PinHasher.verify("9999", legacy, "profile_a"))
    }

    @Test
    fun pinHasher_verifyAcceptsV2() {
        val v2 = PinHasher.hash("1234", "profile_a")
        assertTrue(PinHasher.verify("1234", v2, "profile_a"))
        assertFalse(PinHasher.verify("9999", v2, "profile_a"))
    }

    @Test
    fun pinHasher_looksLikePlaintextPin() {
        assertTrue(PinHasher.looksLikePlaintextPin("1234"))
        assertTrue(PinHasher.looksLikePlaintextPin("12345678"))
        assertFalse(PinHasher.looksLikePlaintextPin("123"))
        assertFalse(PinHasher.looksLikePlaintextPin("abcd"))
        assertFalse(PinHasher.looksLikePlaintextPin(PinHasher.hash("1234", "parental_default")))
    }

    @Test
    fun parentalPinSalt_isStable() {
        assertEquals("parental_kids01", ProfileManager.parentalPinSalt("kids01"))
        assertEquals("admin_kids01", ProfileManager.adminPinSalt("kids01"))
    }

    @Test
    fun avatarKeys_matchesIllustratedCatalog() {
        assertEquals(
            ProfileAvatarStyle.all().map { it.key },
            ProfileManager.avatarKeys,
        )
        assertEquals(20, ProfileManager.avatarKeys.size)
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
    fun defaultProfileId_isStableLiteral() {
        assertEquals("default", ProfileManager.DEFAULT_PROFILE_ID)
        assertEquals("default", ProfileStore.DEFAULT_PROFILE_ID)
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
            avatarKey = "crimson",
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
            enabledIntegrations = setOf(UserProfile.Integration.TRAKT, UserProfile.Integration.PLEX),
        )
        val updated = profile.copy(enabledIntegrations = profile.enabledIntegrations + UserProfile.Integration.TMDB)
        assertTrue(updated.enabledIntegrations.contains(UserProfile.Integration.TMDB))
        assertFalse(updated.enabledIntegrations.contains(UserProfile.Integration.DEBRID))
    }

    @Test
    fun isIntegrationEnabled_emptySetMeansAllOn() {
        val profile = UserProfile(
            id = "test",
            displayName = "Test",
            avatarKey = "crimson",
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
            enabledIntegrations = emptySet(),
        )
        assertTrue(ProfileManager.isIntegrationEnabled(profile, UserProfile.Integration.TRAKT))
        assertTrue(ProfileManager.isIntegrationEnabled(profile, UserProfile.Integration.TMDB))
    }

    @Test
    fun isIntegrationEnabled_respectsExplicitAllowList() {
        val profile = UserProfile(
            id = "test",
            displayName = "Test",
            avatarKey = "crimson",
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
            enabledIntegrations = setOf(UserProfile.Integration.TMDB),
        )
        assertTrue(ProfileManager.isIntegrationEnabled(profile, UserProfile.Integration.TMDB))
        assertFalse(ProfileManager.isIntegrationEnabled(profile, UserProfile.Integration.TRAKT))
    }

    @Test
    fun hasParentalCeiling_trueForKidsOrMaxAge() {
        val kids = UserProfile(
            id = "k",
            displayName = "Kids",
            avatarKey = "crimson",
            isKids = true,
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        )
        val capped = UserProfile(
            id = "a",
            displayName = "Adult",
            avatarKey = "crimson",
            maxAgeRating = 16,
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        )
        val open = UserProfile(
            id = "o",
            displayName = "Open",
            avatarKey = "crimson",
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        )
        assertTrue(ProfileManager.hasParentalCeiling(kids))
        assertTrue(ProfileManager.hasParentalCeiling(capped))
        assertFalse(ProfileManager.hasParentalCeiling(open))
    }
}
