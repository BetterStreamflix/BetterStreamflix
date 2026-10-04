package com.dskja.betterstreamflix.profiles

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

/**
 * Forces Who's Watching on cold start when the active profile is PIN-locked
 * or a kids profile. Once the household unlocks a profile for this process, the
 * gate stays open until the process dies or the unlocked profile changes.
 */
object ProfileUnlockGate {

    @Volatile
    private var unlockedProfileId: String? = null

    fun markUnlocked(profileId: String) {
        unlockedProfileId = profileId
    }

    fun clear() {
        unlockedProfileId = null
    }

    fun isUnlockedFor(profileId: String): Boolean =
        unlockedProfileId == profileId

    fun requiresGate(profile: UserProfile?): Boolean {
        if (profile == null) return false
        if (isUnlockedFor(profile.id)) return false
        return profile.pinHash != null || profile.isKids
    }

    fun requiresGateForActive(): Boolean =
        requiresGate(ProfileManager.activeProfile())

    /**
     * Show a non-dismissible Who's Watching picker until the active (or newly
     * selected) profile is unlocked for this session.
     */
    fun maybeShow(
        activity: FragmentActivity,
        hostFragmentProvider: () -> Fragment?,
        onUnlocked: (() -> Unit)? = null,
    ) {
        if (!requiresGateForActive()) {
            ProfileManager.activeProfile()?.id?.let(::markUnlocked)
            return
        }
        val host = hostFragmentProvider() ?: return
        if (host.childFragmentManager.findFragmentByTag(ProfilePickerDialog.TAG) != null) return
        ProfilePickerDialog.show(
            fragment = host,
            onSwitched = {
                markUnlocked(ProfileManager.activeProfileId)
                onUnlocked?.invoke()
            },
            requireUnlock = true,
        )
    }
}
