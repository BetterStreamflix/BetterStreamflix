package com.betterstreamflix.app.player

import android.content.Context
import android.os.Build
import com.betterstreamflix.app.utils.UserPreferences

/**
 * Codec Comfort — remember sticks / boxes that repeatedly hit HW decoder trouble
 * and default earlier to software decode + constrained (stable) buffer profiles.
 *
 * Complements [com.betterstreamflix.app.ui.StickEnergyMode]: Energy Mode is a
 * Fire Stick power/RAM policy; Codec Comfort is learned from real decode failures
 * on this device fingerprint and applies even when Energy Mode is off.
 *
 * Does **not** kill Ambient / Featured home motion (CC-003) — only decoder/buffers.
 */
object CodecComfort {

    /** Decoder capability crashes before we latch SW/stable defaults. */
    const val FAILURES_BEFORE_ACTIVE = 2

    const val MAX_FAILURES_TRACKED = 24

    /** Clean HW sessions before a latched Comfort can fully clear (CC-001). */
    const val STABLE_SESSIONS_TO_DECAY = 3

    /** Pure helper for unit tests. */
    fun resolveActive(failureCount: Int, threshold: Int = FAILURES_BEFORE_ACTIVE): Boolean =
        failureCount >= threshold

    fun deviceFingerprint(
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        model: String = Build.MODEL.orEmpty(),
        device: String = Build.DEVICE.orEmpty(),
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): String {
        val parts = listOf(manufacturer, model, device, "sdk$sdkInt")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        return parts.joinToString("|").ifBlank { "unknown" }
    }

    /**
     * True once this device has proven flaky enough that Auto should prefer
     * software decode and constrained buffers without waiting for another crash.
     */
    fun isActive(): Boolean {
        ensureFingerprintBound()
        return resolveActive(UserPreferences.codecComfortFailureCount)
    }

    fun preferSoftwareDecoder(): Boolean = isActive()

    fun preferStablePlaybackProfile(): Boolean = isActive()

    /**
     * Record a HW decoder capability failure. Activates after
     * [FAILURES_BEFORE_ACTIVE] hits on the same device fingerprint.
     */
    fun recordDecoderTrouble(@Suppress("UNUSED_PARAMETER") context: Context? = null) {
        ensureFingerprintBound()
        val next = (UserPreferences.codecComfortFailureCount + 1)
            .coerceAtMost(MAX_FAILURES_TRACKED)
        UserPreferences.codecComfortFailureCount = next
        UserPreferences.codecComfortStableStreak = 0
        if (resolveActive(next) && UserPreferences.codecComfortActivatedAtMs <= 0L) {
            UserPreferences.codecComfortActivatedAtMs = System.currentTimeMillis()
        }
    }

    /**
     * Soft decay when HW Auto plays cleanly (CC-001). After [STABLE_SESSIONS_TO_DECAY]
     * successes while latched, drop one failure; Comfort clears when count falls
     * below [FAILURES_BEFORE_ACTIVE].
     */
    fun noteStableHardwarePlayback() {
        ensureFingerprintBound()
        val count = UserPreferences.codecComfortFailureCount
        if (count <= 0) return
        val streak = UserPreferences.codecComfortStableStreak + 1
        if (streak < STABLE_SESSIONS_TO_DECAY) {
            UserPreferences.codecComfortStableStreak = streak
            return
        }
        UserPreferences.codecComfortStableStreak = 0
        val next = (count - 1).coerceAtLeast(0)
        UserPreferences.codecComfortFailureCount = next
        if (!resolveActive(next)) {
            UserPreferences.codecComfortActivatedAtMs = 0L
        }
    }

    /** Settings “Reset Codec Comfort” — clear latch + counters (CC-001/009). */
    fun reset() {
        UserPreferences.codecComfortDeviceFingerprint = deviceFingerprint()
        UserPreferences.codecComfortFailureCount = 0
        UserPreferences.codecComfortActivatedAtMs = 0L
        UserPreferences.codecComfortStableStreak = 0
    }

    fun failureCount(): Int {
        ensureFingerprintBound()
        return UserPreferences.codecComfortFailureCount
    }

    private fun ensureFingerprintBound() {
        val current = deviceFingerprint()
        val stored = UserPreferences.codecComfortDeviceFingerprint
        if (stored.isBlank()) {
            UserPreferences.codecComfortDeviceFingerprint = current
            return
        }
        if (stored != current) {
            // Different stick / ROM / SDK — reset learning for the new hardware (CC-010).
            UserPreferences.codecComfortDeviceFingerprint = current
            UserPreferences.codecComfortFailureCount = 0
            UserPreferences.codecComfortActivatedAtMs = 0L
            UserPreferences.codecComfortStableStreak = 0
        }
    }
}
