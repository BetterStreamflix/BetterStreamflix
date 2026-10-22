package com.betterstreamflix.app.download

import com.betterstreamflix.app.profiles.ProfileManager

/** Helpers for profile-scoped download rows (PS-002). */
object DownloadProfileScope {
    fun activeId(): String =
        runCatching { ProfileManager.activeProfileId }
            .getOrDefault(ProfileManager.DEFAULT_PROFILE_ID)
            .ifBlank { ProfileManager.DEFAULT_PROFILE_ID }

    /**
     * Legacy rows (`profileId` empty) are visible only when the active profile is default.
     * New rows match their stamped [profileId] exactly.
     */
    fun matchesActive(profileId: String?): Boolean {
        val active = activeId()
        val stored = profileId.orEmpty()
        return if (active == ProfileManager.DEFAULT_PROFILE_ID) {
            stored.isEmpty() || stored == ProfileManager.DEFAULT_PROFILE_ID
        } else {
            stored == active
        }
    }
}
