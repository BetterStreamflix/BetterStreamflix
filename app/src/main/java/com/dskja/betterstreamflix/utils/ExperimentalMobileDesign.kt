package com.dskja.betterstreamflix.utils

import android.app.Activity
import android.content.Context
import com.dskja.betterstreamflix.BuildConfig
import com.dskja.betterstreamflix.R
import com.google.android.material.color.DynamicColors

/**
 * Lumina — off-by-default cinematic mobile shell.
 *
 * DEBUG builds can enable the React/Vite shell (assets/experimental) plus the
 * legacy XML glass layouts for screens that are not yet ported.
 */
object ExperimentalMobileDesign {

    enum class Accent(val key: String) {
        CRIMSON("crimson"),
        EMBER("ember"),
        AURORA("aurora"),
        SLATE("slate");

        companion object {
            fun fromKey(raw: String?): Accent =
                entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: CRIMSON
        }
    }

    fun isAvailable(): Boolean = BuildConfig.DEBUG

    fun enabled(): Boolean = isAvailable() && UserPreferences.experimentalNewAppDesign

    /** React Lumina shell for Home (and future surfaces). */
    fun useReactShell(): Boolean = enabled()

    /** Call once at app start to clear stale Lumina prefs in release builds. */
    fun enforceAvailabilityGate() {
        if (!isAvailable() && UserPreferences.experimentalNewAppDesign) {
            UserPreferences.experimentalNewAppDesign = false
        }
    }
    fun layout(defaultRes: Int, experimentalRes: Int): Int =
        if (enabled()) experimentalRes else defaultRes

    fun accent(): Accent = Accent.fromKey(UserPreferences.experimentalLuminaAccent)

    fun pureBlack(): Boolean = enabled() && UserPreferences.experimentalLuminaPureBlack

    fun dynamicColors(): Boolean = enabled() && UserPreferences.experimentalLuminaDynamicColors

    fun navAutoHide(): Boolean = enabled() && UserPreferences.experimentalLuminaNavAutoHide

    fun heroParallax(): Boolean = enabled() && UserPreferences.experimentalLuminaHeroParallax

    fun reducedGlass(): Boolean = enabled() && UserPreferences.experimentalLuminaReducedGlass

    fun glassCardBackground(): Int =
        if (reducedGlass()) R.drawable.bg_exp_glass_card_flat else R.drawable.bg_exp_glass_card

    fun bottomSheetBackground(): Int =
        if (reducedGlass()) R.drawable.bg_exp_bottom_sheet_flat else R.drawable.bg_exp_bottom_sheet

    fun castMiniBackground(): Int =
        if (reducedGlass()) R.drawable.bg_exp_cast_mini_flat else R.drawable.bg_exp_cast_mini

    fun navPillBackground(): Int =
        if (reducedGlass()) R.drawable.bg_exp_nav_pill_flat else R.drawable.bg_exp_nav_pill

    fun dialogBackground(): Int =
        if (reducedGlass()) R.drawable.bg_exp_dialog_flat else R.drawable.bg_exp_dialog

    fun metaPillBackground(): Int =
        if (reducedGlass()) R.drawable.bg_exp_meta_pill_flat else R.drawable.bg_exp_meta_pill

    fun searchFieldBackground(): Int =
        if (reducedGlass()) R.drawable.bg_exp_search_field_flat else R.drawable.bg_exp_search_field

    fun chipBackground(): Int =
        if (reducedGlass()) R.drawable.bg_exp_chip_flat else R.drawable.bg_exp_chip

    fun optionItemBackground(): Int =
        if (reducedGlass()) R.drawable.bg_exp_option_item_flat else R.drawable.bg_exp_option_item

    fun iconChipBackground(): Int =
        if (reducedGlass()) R.drawable.bg_exp_icon_chip_flat else R.drawable.bg_exp_icon_chip

    fun controlsPillBackground(): Int =
        if (reducedGlass()) R.drawable.bg_exp_controls_pill_flat else R.drawable.bg_exp_controls_pill

    fun liveIndicatorBackground(): Int =
        if (reducedGlass()) R.drawable.bg_exp_live_indicator_flat else R.drawable.bg_exp_live_indicator

    fun spinnerBackground(): Int =
        if (reducedGlass()) R.drawable.bg_exp_spinner_flat else R.drawable.bg_exp_spinner

    fun primaryButtonBackground(): Int = R.drawable.bg_exp_button_primary

    /**
     * Walk [root] and swap glossy Lumina glass/sheet/nav shells to flat variants
     * when [reducedGlass] is on. Safe no-op when the experiment is off.
     */
    fun applyReducedGlass(root: android.view.View?) {
        if (root == null || !enabled() || !reducedGlass()) return
        val ctx = root.context
        fun matches(view: android.view.View, resId: Int): Boolean {
            val bg = view.background ?: return false
            val expected = androidx.core.content.ContextCompat.getDrawable(ctx, resId) ?: return false
            return bg.constantState != null && bg.constantState == expected.constantState
        }
        fun walk(view: android.view.View) {
            when {
                matches(view, R.drawable.bg_exp_glass_card) ->
                    view.setBackgroundResource(R.drawable.bg_exp_glass_card_flat)
                matches(view, R.drawable.bg_exp_bottom_sheet) ->
                    view.setBackgroundResource(R.drawable.bg_exp_bottom_sheet_flat)
                matches(view, R.drawable.bg_exp_cast_mini) ->
                    view.setBackgroundResource(R.drawable.bg_exp_cast_mini_flat)
                matches(view, R.drawable.bg_exp_nav_pill) ->
                    view.setBackgroundResource(R.drawable.bg_exp_nav_pill_flat)
                matches(view, R.drawable.bg_exp_provider_card) ->
                    view.setBackgroundResource(R.drawable.bg_exp_glass_card_flat)
                matches(view, R.drawable.bg_exp_controls_pill) ->
                    view.setBackgroundResource(R.drawable.bg_exp_controls_pill_flat)
                matches(view, R.drawable.bg_exp_dialog) ->
                    view.setBackgroundResource(R.drawable.bg_exp_dialog_flat)
                matches(view, R.drawable.bg_exp_meta_pill) ->
                    view.setBackgroundResource(R.drawable.bg_exp_meta_pill_flat)
                matches(view, R.drawable.bg_exp_search_field) ->
                    view.setBackgroundResource(R.drawable.bg_exp_search_field_flat)
                matches(view, R.drawable.bg_exp_chip) ->
                    view.setBackgroundResource(R.drawable.bg_exp_chip_flat)
                matches(view, R.drawable.bg_exp_option_item) ->
                    view.setBackgroundResource(R.drawable.bg_exp_option_item_flat)
                matches(view, R.drawable.bg_exp_icon_chip) ->
                    view.setBackgroundResource(R.drawable.bg_exp_icon_chip_flat)
                matches(view, R.drawable.bg_exp_live_indicator) ->
                    view.setBackgroundResource(R.drawable.bg_exp_live_indicator_flat)
                matches(view, R.drawable.bg_exp_spinner) ->
                    view.setBackgroundResource(R.drawable.bg_exp_spinner_flat)
            }
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
    }

    /** Theme resource for [Activity.setTheme] when Lumina is on. */
    fun themeRes(): Int {
        val black = pureBlack()
        return when (accent()) {
            Accent.CRIMSON -> if (black) R.style.AppTheme_Mobile_Experimental_PureBlack
            else R.style.AppTheme_Mobile_Experimental
            Accent.EMBER -> if (black) R.style.AppTheme_Mobile_Experimental_Ember_PureBlack
            else R.style.AppTheme_Mobile_Experimental_Ember
            Accent.AURORA -> if (black) R.style.AppTheme_Mobile_Experimental_Aurora_PureBlack
            else R.style.AppTheme_Mobile_Experimental_Aurora
            Accent.SLATE -> if (black) R.style.AppTheme_Mobile_Experimental_Slate_PureBlack
            else R.style.AppTheme_Mobile_Experimental_Slate
        }
    }

    /** CSS hex for out-of-process surfaces (TV bypass landing HTML). */
    fun accentCssHex(): String = when (accent()) {
        Accent.CRIMSON -> "#E50914"
        Accent.EMBER -> "#E85A2A"
        Accent.AURORA -> "#2BB8A6"
        Accent.SLATE -> "#7A8B9A"
    }

    /**
     * Apply wallpaper-driven Material You tint when the user opted in.
     * Call after [Activity.setTheme] / [Activity.setContentView].
     */
    fun applyDynamicColors(activity: Activity) {
        if (!dynamicColors()) return
        runCatching { DynamicColors.applyToActivityIfAvailable(activity) }
    }

    fun summary(context: Context): String {
        if (!isAvailable()) {
            return context.getString(R.string.settings_experimental_broken_summary)
        }
        if (!enabled()) {
            return context.getString(R.string.settings_experimental_new_design_summary)
        }
        val accentLabel = when (accent()) {
            Accent.CRIMSON -> context.getString(R.string.exp_accent_crimson)
            Accent.EMBER -> context.getString(R.string.exp_accent_ember)
            Accent.AURORA -> context.getString(R.string.exp_accent_aurora)
            Accent.SLATE -> context.getString(R.string.exp_accent_slate)
        }
        val extras = buildList {
            add(context.getString(R.string.exp_opt_react_shell_short))
            if (pureBlack()) add(context.getString(R.string.exp_opt_pure_black_short))
            if (dynamicColors()) add(context.getString(R.string.exp_opt_dynamic_short))
        }.joinToString(" · ")
        return context.getString(
            R.string.settings_experimental_lumina_active_summary_extra,
            accentLabel,
            extras,
        )
    }
}
