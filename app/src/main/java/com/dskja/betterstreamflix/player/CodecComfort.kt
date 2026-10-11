package com.dskja.betterstreamflix.player

import android.content.Context
import android.os.Build
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Codec Comfort — remember sticks / boxes that repeatedly hit HW decoder trouble
 * and default earlier to software decode + constrained (stable) buffer profiles.
 *
 * Complements [com.dskja.betterstreamflix.ui.StickEnergyMode]: Energy Mode is a
 * Fire Stick power/RAM policy; Codec Comfort is learned from real decode failures
 * on this device fingerprint and applies even when Energy Mode is off.
 */
object CodecComfort {

    /** Decoder capability crashes before we latch SW/stable defaults. */
    const val FAILURES_BEFORE_ACTIVE = 2

    const val MAX_FAILURES_TRACKED = 24

    /** Pure helper for unit tests. */
    fun resolveActive(failureCount: Int, threshold: Int = FAILURES_BEFORE_ACTIVE): Boolean =
        failureCount >= threshold

    fun deviceFingerprint(
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        model: String = Build.MODEL.orEmpty(),
        device: String = Build.DEVICE.orEmpty(),
    ): String {
        val parts = listOf(manufacturer, model, device)
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
        if (resolveActive(next) && UserPreferences.codecComfortActivatedAtMs <= 0L) {
            UserPreferences.codecComfortActivatedAtMs = System.currentTimeMillis()
        }
    }

    /** Optional decay when HW Auto plays cleanly for a long stretch — not used to flip off quickly. */
    fun noteStableHardwarePlayback() {
        ensureFingerprintBound()
        if (!isActive()) return
        // Soft decay: one success nudges the counter down but never below the latch threshold
        // once activated — comfort stays on so the stick does not re-crash.
        val count = UserPreferences.codecComfortFailureCount
        if (count > FAILURES_BEFORE_ACTIVE) {
            UserPreferences.codecComfortFailureCount = count - 1
        }
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
            // Different stick / ROM — reset learning for the new hardware.
            UserPreferences.codecComfortDeviceFingerprint = current
            UserPreferences.codecComfortFailureCount = 0
            UserPreferences.codecComfortActivatedAtMs = 0L
        }
    }
}
