package com.betterstreamflix.app.ui

import android.content.Context
import com.betterstreamflix.app.BetterStreamflixApp
import com.betterstreamflix.app.utils.DeviceCapabilities
import com.betterstreamflix.app.utils.UserPreferences

/**
 * Fire Stick / leanback energy mode — fewer parallel provider scrapes,
 * reduced UI motion, and (separately from SW decode) constrained work.
 *
 * Unset preference auto-enables on constrained Fire Stick-class devices
 * (legacy Fire OS, low-RAM, known Stick SKUs) — not Cube / high-end Fire TV
 * Edition by default. Users can toggle in Settings → Appearance.
 *
 * Coordinates with [com.betterstreamflix.app.player.CodecComfort]: Energy Mode
 * is the Fire Stick power policy; Codec Comfort is learned from HW decode
 * failures. SW Auto preference is driven by Comfort / fragile-OEM heuristics,
 * not by Energy alone (SE-013).
 */
object StickEnergyMode {

    /** Explicit preference key (matches settings XML / UserPreferences.Key). */
    const val PREF_KEY = "STICK_ENERGY_MODE"

    /**
     * Effective on/off. When the preference was never written, constrained
     * Fire Stick-class devices default to on; Cube / high-RAM Fire boxes stay
     * off until the user enables it.
     */
    fun isEnabled(context: Context? = null): Boolean {
        UserPreferences.stickEnergyModeExplicit?.let { return it }
        val ctx = context
            ?: runCatching { BetterStreamflixApp.instance.applicationContext }.getOrNull()
            ?: return false
        return DeviceCapabilities.shouldAutoEnableStickEnergy(ctx)
    }

    /**
     * Cap for concurrent provider / enrich / resolve work.
     * Energy mode forces serial (1); otherwise [default] is unchanged.
     */
    fun providerParallelism(default: Int, context: Context? = null): Int {
        val cappedDefault = default.coerceAtLeast(1)
        return if (isEnabled(context)) 1 else cappedDefault
    }

    /**
     * Whether player Auto should prefer software / extension renderers first.
     * Learned Codec Comfort and static fragile-device heuristics — Energy Mode
     * alone does not force SW (serial scrapes + less UI are enough for power).
     */
    fun preferSoftwareDecoder(context: Context): Boolean =
        DeviceCapabilities.shouldPreferSoftwareDecoder(context)

    /**
     * Ken-burns, Featured advance, focus zoom, Glide transitions, etc.
     * Delegates to [DeviceCapabilities.shouldReduceHomeEffects], which folds
     * Stick Energy Mode in (but not Codec Comfort — see CC-003).
     *
     * Ambient is special-cased: Stick Energy alone does not hard-off enter
     * ([AmbientModePolicy.highEndOk]); Ambient uses reduced Ken Burns instead.
     */
    fun shouldReduceUiMotion(context: Context): Boolean =
        DeviceCapabilities.shouldReduceHomeEffects(context)

    /** Warm radius for IPTV neighbor prefetch (±N). Energy → 0–1. */
    fun zapWarmRadius(default: Int = 2, context: Context? = null): Int {
        val base = default.coerceAtLeast(0)
        return if (isEnabled(context)) base.coerceAtMost(1) else base
    }

    /** Pure helpers for unit tests (no SharedPreferences / Context). */
    fun resolveEnabled(
        explicit: Boolean?,
        autoOn: Boolean,
    ): Boolean = explicit ?: autoOn

    fun resolveProviderParallelism(enabled: Boolean, default: Int): Int =
        if (enabled) 1 else default.coerceAtLeast(1)

    fun resolveZapWarmRadius(enabled: Boolean, default: Int): Int =
        if (enabled) default.coerceAtMost(1).coerceAtLeast(0) else default.coerceAtLeast(0)
}
