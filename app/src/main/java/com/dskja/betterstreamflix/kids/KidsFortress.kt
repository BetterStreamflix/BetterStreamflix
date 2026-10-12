package com.dskja.betterstreamflix.kids

import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.profiles.UserProfile
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Runtime Kids Fortress facade — wires [KidsFortressPolicy] to live profile prefs.
 */
object KidsFortress {

    fun sessionKind(): KidsFortressPolicy.SessionKind =
        KidsFortressPolicy.sessionKind(
            isKids = ProfileManager.isKidsActive(),
            isGuest = ProfileManager.isGuestActive(),
        )

    fun isKidsSession(): Boolean = sessionKind() == KidsFortressPolicy.SessionKind.KIDS

    fun parentDashboardEligible(): Boolean =
        KidsFortressPolicy.parentDashboardEligible(
            isKidsActive = ProfileManager.isKidsActive(),
            isGuestActive = ProfileManager.isGuestActive(),
        )

    fun downloadsNavVisible(): Boolean =
        KidsFortressPolicy.downloadsNavVisible(ProfileManager.isKidsActive())

    fun liveHubAllowed(): Boolean =
        KidsFortressPolicy.liveHubAllowed(ProfileManager.isKidsActive())

    fun spoilerForced(): Boolean =
        KidsFortressPolicy.spoilerForced(ProfileManager.isKidsActive())

    fun ageFilteringActive(): Boolean =
        KidsFortressPolicy.ageFilteringActive(
            isKids = ProfileManager.isKidsActive(),
            parentalControlActive = UserPreferences.isParentalControlActive,
        )

    fun effectiveCeiling(): Int? =
        KidsFortressPolicy.effectiveCeiling(
            isKids = ProfileManager.isKidsActive(),
            profileMaxAge = ProfileManager.activeProfile()?.maxAgeRating,
            parentalMaxAge = UserPreferences.parentalControlMaxAge,
        )

    fun kidsProfiles(): List<UserProfile> =
        ProfileManager.profiles().filter { it.isKids && !it.isGuest }

    /**
     * Exit PIN available for [profile]: household parental PIN and/or that
     * profile's own [UserProfile.pinHash].
     */
    fun hasExitPin(profile: UserProfile? = ProfileManager.activeProfile()): Boolean {
        profile ?: return false
        val household = UserPreferences.parentalControlPin.isNotBlank()
        val profilePin = !profile.pinHash.isNullOrBlank()
        return KidsFortressPolicy.kidsUnlockAllowed(household, profilePin)
    }

    fun fortressHealthy(profile: UserProfile): Boolean {
        if (!profile.isKids) return true
        val ceiling = KidsFortressPolicy.effectiveCeiling(
            isKids = true,
            profileMaxAge = profile.maxAgeRating,
            parentalMaxAge = null,
        )
        return KidsFortressPolicy.fortressHealthy(
            isKids = true,
            hasExitPin = hasExitPin(profile),
            ceiling = ceiling,
        )
    }

    /** Spoiler is always on for kids — same as [spoilerForced]. */
    fun spoilerActive(): Boolean = spoilerForced()
}
