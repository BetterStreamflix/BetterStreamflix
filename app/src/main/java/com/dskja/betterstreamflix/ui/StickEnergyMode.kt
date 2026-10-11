package com.dskja.betterstreamflix.ui

import android.content.Context
import com.dskja.betterstreamflix.BetterStreamflixApp
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Fire Stick / leanback energy mode — fewer parallel provider scrapes,
 * software-decoder bias, and reduced UI motion for high-end Stick polish.
 *
 * Unset preference auto-enables on Amazon Fire TV; users can toggle in
 * Settings → Appearance.
 */
object StickEnergyMode {

    /** Explicit preference key (matches settings XML / UserPreferences.Key). */
    const val PREF_KEY = "STICK_ENERGY_MODE"

    /**
     * Effective on/off. When the preference was never written, Amazon Fire TV
     * defaults to on; other devices stay off until the user enables it.
     */
    fun isEnabled(context: Context? = null): Boolean {
        UserPreferences.stickEnergyModeExplicit?.let { return it }
        val ctx = context
            ?: runCatching { BetterStreamflixApp.instance.applicationContext }.getOrNull()
            ?: return false
        return DeviceCapabilities.isAmazonFireTv(ctx)
    }

    /**
     * Cap for concurrent provider / enrich / resolve work.
     * Energy mode forces serial (1); otherwise [default] is unchanged.
     */
    fun providerParallelism(default: Int, context: Context? = null): Int {
        val cappedDefault = default.coerceAtLeast(1)
        return if (isEnabled(context)) 1 else cappedDefault
    }

    /** Whether player Auto should prefer software / extension renderers first. */
    fun preferSoftwareDecoder(context: Context): Boolean =
        isEnabled(context) || DeviceCapabilities.shouldPreferSoftwareDecoder(context)

    /**
     * Ken-burns, Featured advance, focus zoom, Glide transitions, etc.
     * Delegates to [DeviceCapabilities.shouldReduceHomeEffects], which already
     * folds Stick Energy Mode in.
     */
    fun shouldReduceUiMotion(context: Context): Boolean =
        DeviceCapabilities.shouldReduceHomeEffects(context)

    /** Pure helpers for unit tests (no SharedPreferences / Context). */
    fun resolveEnabled(
        explicit: Boolean?,
        isAmazonFireTv: Boolean,
    ): Boolean = explicit ?: isAmazonFireTv

    fun resolveProviderParallelism(enabled: Boolean, default: Int): Int =
        if (enabled) 1 else default.coerceAtLeast(1)
}
