package com.dskja.betterstreamflix.utils

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.dskja.betterstreamflix.R

/**
 * Appearance looks for Nocturne. Legacy 10-skin keys still resolve, but they all
 * share the same canvas — only accent (and optional OLED black) changes.
 */
object ThemeManager {
    const val DEFAULT = "default"
    const val NERO_AMOLED_OLED = "nero_amoled_oled"
    const val SUNSET_CINEMA = "sunset_cinema"
    const val STEEL_BLUE = "steel_blue"
    const val FOREST_NIGHT = "forest_night"
    const val CRIMSON_NOIR = "crimson_noir"
    const val MIDNIGHT_VIOLET = "midnight_violet"
    const val NORD_FROST = "nord_frost"
    const val EMERALD_LUXE = "emerald_luxe"
    const val RETRO_NEON = "retro_neon"

    private const val CANVAS = "#0C0A08"
    private const val CANVAS_OLED = "#000000"
    private const val IVORY = "#F3EBDD"
    private const val MUTED = "#A89B8C"
    private const val COPPER = "#D08A4A"
    private const val VIOLET = "#9B7EC8"
    private const val MOSS = "#6FA37A"
    private const val INK = "#A8B4C0"

    data class Palette(
        @ColorInt val mobileNavBackground: Int,
        @ColorInt val mobileNavActive: Int,
        @ColorInt val mobileNavInactive: Int,
        @ColorInt val systemBar: Int,
        @ColorInt val tvNavBackground: Int,
        @ColorInt val tvHeaderPrimary: Int,
        @ColorInt val tvHeaderSecondary: Int,
    )

    fun accentKey(theme: String): String = when (theme) {
        MIDNIGHT_VIOLET, RETRO_NEON -> ExperimentalMobileDesign.Accent.VIOLET.key
        FOREST_NIGHT, EMERALD_LUXE -> ExperimentalMobileDesign.Accent.MOSS.key
        STEEL_BLUE, NORD_FROST -> ExperimentalMobileDesign.Accent.INK.key
        else -> ExperimentalMobileDesign.Accent.COPPER.key
    }

    fun canonicalize(theme: String): String = when (theme) {
        MIDNIGHT_VIOLET, RETRO_NEON -> MIDNIGHT_VIOLET
        FOREST_NIGHT, EMERALD_LUXE -> FOREST_NIGHT
        STEEL_BLUE, NORD_FROST -> STEEL_BLUE
        NERO_AMOLED_OLED -> DEFAULT
        SUNSET_CINEMA, CRIMSON_NOIR, DEFAULT -> DEFAULT
        else -> DEFAULT
    }

    fun themeForAccent(accent: ExperimentalMobileDesign.Accent): String = when (accent) {
        ExperimentalMobileDesign.Accent.VIOLET -> MIDNIGHT_VIOLET
        ExperimentalMobileDesign.Accent.MOSS -> FOREST_NIGHT
        ExperimentalMobileDesign.Accent.INK -> STEEL_BLUE
        ExperimentalMobileDesign.Accent.COPPER -> DEFAULT
    }

    /** Fold leftover 10-skin prefs into Nocturne copper/violet/moss/ink + OLED. */
    fun syncSavedLook() {
        val raw = UserPreferences.selectedTheme
        if (raw == NERO_AMOLED_OLED) {
            UserPreferences.experimentalLuminaPureBlack = true
        }
        val canonical = canonicalize(raw)
        val lumina = ExperimentalMobileDesign.Accent.fromKey(UserPreferences.experimentalLuminaAccent)
        if (canonical == DEFAULT && lumina != ExperimentalMobileDesign.Accent.COPPER) {
            UserPreferences.selectedTheme = themeForAccent(lumina)
            return
        }
        if (canonical != raw) {
            UserPreferences.selectedTheme = canonical
        }
        UserPreferences.experimentalLuminaAccent = accentKey(canonical)
    }

    fun applyLook(theme: String) {
        if (theme == NERO_AMOLED_OLED) {
            UserPreferences.experimentalLuminaPureBlack = true
        }
        val canonical = canonicalize(theme)
        UserPreferences.selectedTheme = canonical
        UserPreferences.experimentalLuminaAccent = accentKey(canonical)
        UserPreferences.experimentalNewAppDesign = true
    }

    fun applyAccent(accentKey: String) {
        val accent = ExperimentalMobileDesign.Accent.fromKey(accentKey)
        UserPreferences.experimentalLuminaAccent = accent.key
        UserPreferences.selectedTheme = themeForAccent(accent)
        UserPreferences.experimentalNewAppDesign = true
    }

    @StyleRes
    fun mobileThemeRes(theme: String): Int {
        val accent = ExperimentalMobileDesign.Accent.fromKey(accentKey(theme))
        val black = theme == NERO_AMOLED_OLED || UserPreferences.experimentalLuminaPureBlack
        return when (accent) {
            ExperimentalMobileDesign.Accent.COPPER ->
                if (black) R.style.AppTheme_Mobile_Experimental_PureBlack
                else R.style.AppTheme_Mobile_Experimental
            ExperimentalMobileDesign.Accent.VIOLET ->
                if (black) R.style.AppTheme_Mobile_Experimental_Ember_PureBlack
                else R.style.AppTheme_Mobile_Experimental_Ember
            ExperimentalMobileDesign.Accent.MOSS ->
                if (black) R.style.AppTheme_Mobile_Experimental_Aurora_PureBlack
                else R.style.AppTheme_Mobile_Experimental_Aurora
            ExperimentalMobileDesign.Accent.INK ->
                if (black) R.style.AppTheme_Mobile_Experimental_Slate_PureBlack
                else R.style.AppTheme_Mobile_Experimental_Slate
        }
    }

    @StyleRes
    fun tvThemeRes(theme: String): Int = when (canonicalize(theme)) {
        MIDNIGHT_VIOLET -> R.style.AppTheme_MidnightViolet
        FOREST_NIGHT -> R.style.AppTheme_ForestNight
        STEEL_BLUE -> R.style.AppTheme_SteelBlue
        else -> if (theme == NERO_AMOLED_OLED || UserPreferences.experimentalLuminaPureBlack) {
            R.style.AppTheme_NeroAmoledOled
        } else {
            R.style.AppTheme_Tv
        }
    }

    @StringRes
    fun titleRes(theme: String): Int = when (canonicalize(theme)) {
        MIDNIGHT_VIOLET -> R.string.theme_midnight_violet
        FOREST_NIGHT -> R.string.theme_forest_night
        STEEL_BLUE -> R.string.theme_steel_blue
        else -> if (theme == NERO_AMOLED_OLED) R.string.theme_nero_amoled_oled
        else R.string.theme_default
    }

    fun palette(theme: String): Palette {
        val black = theme == NERO_AMOLED_OLED || UserPreferences.experimentalLuminaPureBlack
        val canvas = if (black) CANVAS_OLED else CANVAS
        val accent = when (ExperimentalMobileDesign.Accent.fromKey(accentKey(theme))) {
            ExperimentalMobileDesign.Accent.VIOLET -> VIOLET
            ExperimentalMobileDesign.Accent.MOSS -> MOSS
            ExperimentalMobileDesign.Accent.INK -> INK
            ExperimentalMobileDesign.Accent.COPPER -> COPPER
        }
        return Palette(
            mobileNavBackground = color(canvas),
            mobileNavActive = color(accent),
            mobileNavInactive = color(MUTED),
            systemBar = color(canvas),
            tvNavBackground = color(canvas),
            tvHeaderPrimary = color(IVORY),
            tvHeaderSecondary = color(MUTED),
        )
    }

    @ColorInt
    private fun color(hex: String): Int = Color.parseColor(hex)
}
