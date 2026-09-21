package com.dskja.betterstreamflix.utils

import android.app.Activity
import android.content.Context
import com.dskja.betterstreamflix.BuildConfig
import com.dskja.betterstreamflix.R
import com.google.android.material.color.DynamicColors

/**
 * Nocturne — the single app-wide design language.
 *
 * Matte canvas, hairline frames, Fraunces/Syne/Outfit type. Always on for
 * mobile, TV, support, gate and player. Accents (copper / violet / moss / ink)
 * and optional OLED black are the only remaining look variants.
 * The React/Vite home shell stays a nested DEBUG opt-in.
 */
object ExperimentalMobileDesign {

    enum class Accent(val key: String) {
        COPPER("copper"),
        VIOLET("violet"),
        MOSS("moss"),
        INK("ink");

        companion object {
            fun fromKey(raw: String?): Accent = when (raw?.lowercase()) {
                COPPER.key, "crimson" -> COPPER
                VIOLET.key, "ember" -> VIOLET
                MOSS.key, "aurora" -> MOSS
                INK.key, "slate" -> INK
                else -> COPPER
            }
        }
    }

    fun isAvailable(): Boolean = true

    fun enabled(): Boolean = true

    /**
     * React home is a separate DEBUG opt-in. Native Featured carousel is default.
     */
    fun useReactShell(): Boolean =
        BuildConfig.DEBUG && UserPreferences.experimentalReactHome

    /** Call once at app start: keep Nocturne on, fold legacy skins into it. */
    fun enforceAvailabilityGate() {
        UserPreferences.experimentalNewAppDesign = true
        ThemeManager.syncSavedLook()
        if (!BuildConfig.DEBUG && UserPreferences.experimentalReactHome) {
            UserPreferences.experimentalReactHome = false
        }
    }

    fun layout(defaultRes: Int, experimentalRes: Int): Int = experimentalRes

    fun accent(): Accent = Accent.fromKey(UserPreferences.experimentalLuminaAccent)

    fun pureBlack(): Boolean =
        UserPreferences.experimentalLuminaPureBlack ||
            UserPreferences.selectedTheme == ThemeManager.NERO_AMOLED_OLED

    fun dynamicColors(): Boolean = UserPreferences.experimentalLuminaDynamicColors

    fun navAutoHide(): Boolean = UserPreferences.experimentalLuminaNavAutoHide

    fun heroParallax(): Boolean = UserPreferences.experimentalLuminaHeroParallax

    /** Flatter matte panels (legacy pref key: reduced glass). */
    fun reducedGlass(): Boolean = UserPreferences.experimentalLuminaReducedGlass

    fun reducedAtmosphere(): Boolean = reducedGlass()

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
     * Walk [root] and swap hairline panels to solid flats when reduced atmosphere is on.
     */
    fun applyReducedGlass(root: android.view.View?) {
        if (root == null || !reducedGlass()) return
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

    /** Theme resource for [Activity.setTheme]. */
    fun themeRes(): Int {
        val black = pureBlack()
        return when (accent()) {
            Accent.COPPER -> if (black) R.style.AppTheme_Mobile_Experimental_PureBlack
            else R.style.AppTheme_Mobile_Experimental
            Accent.VIOLET -> if (black) R.style.AppTheme_Mobile_Experimental_Ember_PureBlack
            else R.style.AppTheme_Mobile_Experimental_Ember
            Accent.MOSS -> if (black) R.style.AppTheme_Mobile_Experimental_Aurora_PureBlack
            else R.style.AppTheme_Mobile_Experimental_Aurora
            Accent.INK -> if (black) R.style.AppTheme_Mobile_Experimental_Slate_PureBlack
            else R.style.AppTheme_Mobile_Experimental_Slate
        }
    }

    /** CSS hex for out-of-process surfaces (TV bypass landing HTML). */
    fun accentCssHex(): String = when (accent()) {
        Accent.COPPER -> "#D08A4A"
        Accent.VIOLET -> "#9B7EC8"
        Accent.MOSS -> "#6FA37A"
        Accent.INK -> "#A8B4C0"
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
        val accentLabel = when (accent()) {
            Accent.COPPER -> context.getString(R.string.exp_accent_copper)
            Accent.VIOLET -> context.getString(R.string.exp_accent_violet)
            Accent.MOSS -> context.getString(R.string.exp_accent_moss)
            Accent.INK -> context.getString(R.string.exp_accent_ink)
        }
        val extras = buildList {
            if (useReactShell()) add(context.getString(R.string.exp_opt_react_shell_short))
            if (pureBlack()) add(context.getString(R.string.exp_opt_pure_black_short))
            if (dynamicColors()) add(context.getString(R.string.exp_opt_dynamic_short))
        }.joinToString(" · ")
        return if (extras.isBlank()) {
            context.getString(R.string.settings_experimental_lumina_active_summary, accentLabel)
        } else {
            context.getString(
                R.string.settings_experimental_lumina_active_summary_extra,
                accentLabel,
                extras,
            )
        }
    }
}
