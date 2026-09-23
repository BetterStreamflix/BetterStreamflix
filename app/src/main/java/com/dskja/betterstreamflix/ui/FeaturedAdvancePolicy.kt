package com.dskja.betterstreamflix.ui

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.dskja.betterstreamflix.utils.DeviceCapabilities

/**
 * Single owner for Featured auto-advance pause policy (Mobile + TV).
 *
 * Gates: touch-exploration / TalkBack, reduce-motion / animator scale,
 * low-RAM home effects, active user drag, and TV chrome focus.
 */
object FeaturedAdvancePolicy {

    fun shouldAutoAdvance(context: Context): Boolean =
        shouldAutoAdvance(
            touchExploration = isTouchExplorationEnabled(context),
            reduceMotion = shouldReduceMotion(context),
            reduceHomeEffects = DeviceCapabilities.shouldReduceHomeEffects(context),
        )

    /** Pure gate used by [shouldAutoAdvance] and unit tests. */
    fun shouldAutoAdvance(
        touchExploration: Boolean,
        reduceMotion: Boolean,
        reduceHomeEffects: Boolean,
    ): Boolean {
        if (touchExploration) return false
        if (reduceMotion) return false
        if (reduceHomeEffects) return false
        return true
    }

    /**
     * Whether Home TV should post the next auto-rotate tick.
     * When [shouldAutoAdvance] is false the schedule is skipped entirely;
     * pin/chrome focus only defer while advancing is otherwise allowed.
     */
    fun shouldScheduleFeaturedAdvance(
        shouldAutoAdvance: Boolean,
        isBackgroundPinned: Boolean,
        featuredChromeFocused: Boolean,
    ): Boolean {
        if (!shouldAutoAdvance) return false
        if (isBackgroundPinned || featuredChromeFocused) return false
        return true
    }

    fun shouldPlayPageMotion(context: Context): Boolean =
        shouldAutoAdvance(context)

    fun shouldHapticOnPageChange(context: Context): Boolean =
        shouldPlayPageMotion(context)

    fun isTouchExplorationEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        return am.isEnabled && am.isTouchExplorationEnabled
    }

    fun shouldReduceMotion(context: Context): Boolean {
        if (DeviceCapabilities.shouldReduceHomeEffects(context)) return true
        return runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}
