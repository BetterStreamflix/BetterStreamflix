package com.dskja.betterstreamflix.profiles

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.dskja.betterstreamflix.fragments.settings.ProfilesSettingsController
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Forces Who's Watching on cold start when the active profile is PIN-locked
 * or a kids profile. Once the household unlocks a profile for this process, the
 * gate stays open until the process dies, the unlocked profile changes, or
 * idle / background timeout (15 minutes) clears the unlock.
 *
 * Also re-checks on [maybeShow] from Activity.onStart so process death with a
 * restored [android.os.Bundle] cannot skip the PIN/kids challenge.
 */
object ProfileUnlockGate {

    const val IDLE_TIMEOUT_MS: Long = 15L * 60L * 1000L

    @Volatile
    private var unlockedProfileId: String? = null

    @Volatile
    private var lastUnlockElapsedRealtime: Long = 0L

    @Volatile
    private var backgroundedAtElapsedRealtime: Long? = null

    /**
     * Overridable clock for unit tests (Android [android.os.SystemClock] is
     * unavailable on pure JVM test runs).
     */
    internal var elapsedRealtimeMs: () -> Long = {
        try {
            android.os.SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.nanoTime() / 1_000_000L
        }
    }

    fun markUnlocked(profileId: String) {
        unlockedProfileId = profileId
        lastUnlockElapsedRealtime = elapsedRealtimeMs()
        backgroundedAtElapsedRealtime = null
    }

    fun clear() {
        unlockedProfileId = null
        lastUnlockElapsedRealtime = 0L
        backgroundedAtElapsedRealtime = null
    }

    /** Call from Activity.onStop — starts the background idle clock. */
    fun markBackgrounded() {
        if (unlockedProfileId == null) return
        if (backgroundedAtElapsedRealtime == null) {
            backgroundedAtElapsedRealtime = elapsedRealtimeMs()
        }
    }

    /**
     * Call from Activity.onStart. If the app was backgrounded longer than
     * [IDLE_TIMEOUT_MS], clears the unlock so the gate shows again.
     */
    fun markForeground() {
        val bgAt = backgroundedAtElapsedRealtime
        backgroundedAtElapsedRealtime = null
        if (bgAt != null && elapsedRealtimeMs() - bgAt > IDLE_TIMEOUT_MS) {
            clear()
        }
    }

    fun isUnlockedFor(profileId: String): Boolean {
        expireIfNeeded()
        return unlockedProfileId == profileId
    }

    fun requiresGate(profile: UserProfile?): Boolean {
        expireIfNeeded()
        // Corrupt / unreadable profiles: force Who's Watching repair, never skip PIN.
        if (ProfileManager.initFailed) return true
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
        markForeground()
        // Do not re-markUnlocked here — that would reset the idle clock on every onStart.
        if (!requiresGateForActive()) return
        val host = hostFragmentProvider() ?: return
        if (host.childFragmentManager.findFragmentByTag(ProfilePickerDialog.TAG) != null) return
        runCatching {
            ProfilePickerDialog.show(
                fragment = host,
                onSwitched = {
                    val active = ProfileManager.activeProfile()
                    // Kids without a profile PIN must always challenge the household
                    // parental PIN (or refuse unlock if none is set) — KF-002.
                    // fortressHealthy / kidsUnlockAllowed refuse silent unlock (KF-006/007).
                    val needsParentalForKids = active?.isKids == true && active.pinHash == null
                    if (needsParentalForKids) {
                        // Leave unlockedProfileId null until parental PIN succeeds.
                        // Cancel / missing PIN keeps the gate closed; onStart re-prompts.
                        // Idle clear also re-enters here for kids-without-PIN (KF-019).
                        ProfilesSettingsController.challengeKidsUnlock(activity) {
                            markUnlocked(ProfileManager.activeProfileId)
                            onUnlocked?.invoke()
                        }
                    } else if (active?.isKids == true &&
                        !com.dskja.betterstreamflix.kids.KidsFortress.fortressHealthy(active)
                    ) {
                        ProfilesSettingsController.challengeKidsUnlock(activity) {
                            markUnlocked(ProfileManager.activeProfileId)
                            onUnlocked?.invoke()
                        }
                    } else {
                        markUnlocked(ProfileManager.activeProfileId)
                        onUnlocked?.invoke()
                    }
                },
                requireUnlock = true,
            )
        }.onFailure {
            android.util.Log.e("ProfileUnlockGate", "Failed to show unlock picker", it)
        }
    }

    /**
     * Clears unlock when idle since last unlock exceeds [IDLE_TIMEOUT_MS],
     * or when still backgrounded past that timeout.
     */
    private fun expireIfNeeded() {
        if (unlockedProfileId == null) return
        val now = elapsedRealtimeMs()
        if (lastUnlockElapsedRealtime > 0L && now - lastUnlockElapsedRealtime > IDLE_TIMEOUT_MS) {
            clear()
            return
        }
        val bgAt = backgroundedAtElapsedRealtime
        if (bgAt != null && now - bgAt > IDLE_TIMEOUT_MS) {
            clear()
        }
    }
}
