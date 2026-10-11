package com.dskja.betterstreamflix.utils

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.dskja.betterstreamflix.R

/**
 * Looks Locked — exactly three complete Cinema-Ink shells. No OLED/accent half-mix:
 * every surface, chrome, and gradient comes from the resolved look alone.
 */
object ThemeManager {
    /** Cinema-Ink Dark — near-black adult shell. */
    const val CINEMA_INK = "cinema_ink"
    /** Cinema-Ink Light — cool paper surfaces, ink text, blue accent. */
    const val CINEMA_INK_LIGHT = "cinema_ink_light"
    /** Cinema-Ink Kids — brighter navy + warm accent. */
    const val KIDS_BRIGHT = "kids_bright"

    /** The only looks offered in Settings. */
    val LOCKED_LOOKS: List<String> = listOf(CINEMA_INK, CINEMA_INK_LIGHT, KIDS_BRIGHT)

    // Legacy preference values — mapped by [resolveLook], never offered in UI.
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

    fun defaultThemeForKids(isKids: Boolean): String =
        if (isKids) KIDS_BRIGHT else CINEMA_INK

    /**
     * Collapse any stored theme (including retired palettes / OLED) onto one locked look.
     */
    fun resolveLook(theme: String?): String = when (theme) {
        CINEMA_INK, CINEMA_INK_LIGHT, KIDS_BRIGHT -> theme
        // True-black OLED → Dark (same family, no accent leftover).
        NERO_AMOLED_OLED -> CINEMA_INK
        // All retired dark accent themes → Cinema-Ink Dark.
        DEFAULT,
        SUNSET_CINEMA,
        STEEL_BLUE,
        FOREST_NIGHT,
        CRIMSON_NOIR,
        MIDNIGHT_VIOLET,
        NORD_FROST,
        EMERALD_LUXE,
        RETRO_NEON,
        null,
        -> CINEMA_INK
        else -> CINEMA_INK
    }

    fun isLockedLook(theme: String?): Boolean =
        theme != null && LOCKED_LOOKS.contains(theme)

    data class Palette(
        @ColorInt val mobileNavBackground: Int,
        @ColorInt val mobileNavActive: Int,
        @ColorInt val mobileNavInactive: Int,
        @ColorInt val systemBar: Int,
        @ColorInt val tvNavBackground: Int,
        @ColorInt val tvHeaderPrimary: Int,
        @ColorInt val tvHeaderSecondary: Int,
    )

    /**
     * OLED half-mix is retired. Always false so chrome cannot diverge from the look.
     */
    @Deprecated("Looks Locked — OLED overlay removed; use resolveLook()", ReplaceWith("false"))
    fun isOledActive(theme: String = UserPreferences.selectedTheme): Boolean = false

    @StyleRes
    fun mobileThemeRes(theme: String): Int = when (resolveLook(theme)) {
        CINEMA_INK_LIGHT -> R.style.AppTheme_Mobile_CinemaInkLight
        KIDS_BRIGHT -> R.style.AppTheme_Mobile_KidsBright
        else -> R.style.AppTheme_Mobile_CinemaInk
    }

    @StyleRes
    fun tvThemeRes(theme: String): Int = when (resolveLook(theme)) {
        CINEMA_INK_LIGHT -> R.style.AppTheme_CinemaInkLight
        KIDS_BRIGHT -> R.style.AppTheme_KidsBright
        else -> R.style.AppTheme_CinemaInk
    }

    @StringRes
    fun titleRes(theme: String): Int = when (resolveLook(theme)) {
        CINEMA_INK_LIGHT -> R.string.theme_cinema_ink_light
        KIDS_BRIGHT -> R.string.theme_kids_bright
        else -> R.string.theme_cinema_ink
    }

    fun palette(theme: String): Palette {
        val base = when (resolveLook(theme)) {
            CINEMA_INK_LIGHT -> Palette(
                mobileNavBackground = color("#EEF0F4"),
                mobileNavActive = color("#1A4DFF"),
                mobileNavInactive = color("#5C6478"),
                systemBar = color("#EEF0F4"),
                tvNavBackground = color("#EEF0F4"),
                tvHeaderPrimary = color("#121218"),
                tvHeaderSecondary = color("#5C6478"),
            )
            KIDS_BRIGHT -> Palette(
                mobileNavBackground = color("#243B63"),
                mobileNavActive = color("#FFB347"),
                mobileNavInactive = color("#C9D6F0"),
                systemBar = color("#243B63"),
                tvNavBackground = color("#243B63"),
                tvHeaderPrimary = color("#FFF8E7"),
                tvHeaderSecondary = color("#FFE08A"),
            )
            else -> Palette(
                mobileNavBackground = color("#0A0A0C"),
                mobileNavActive = color("#F7F7F8"),
                mobileNavInactive = color("#A1A1AA"),
                systemBar = color("#0A0A0C"),
                tvNavBackground = color("#0A0A0C"),
                tvHeaderPrimary = color("#F7F7F8"),
                tvHeaderSecondary = color("#A1A1AA"),
            )
        }
        // High contrast a11y overlay sits on top of the locked look (text/chrome only).
        // Kids Bright keeps warm navy + accent tint so HC does not wipe the kids shell.
        // Light keeps cool paper surfaces with ink text rather than a black nav wipe.
        return if (UserPreferences.highContrast) {
            when (resolveLook(theme)) {
                KIDS_BRIGHT -> base.copy(
                    mobileNavActive = color("#FFB347"),
                    mobileNavInactive = color("#FFFFFF"),
                    tvHeaderPrimary = color("#FFFFFF"),
                    tvHeaderSecondary = color("#FFE08A"),
                )
                CINEMA_INK_LIGHT -> base.copy(
                    mobileNavActive = color("#000000"),
                    mobileNavInactive = color("#1A1A1A"),
                    tvHeaderPrimary = color("#000000"),
                    tvHeaderSecondary = color("#1A1A1A"),
                )
                else -> base.copy(
                    mobileNavBackground = color("#000000"),
                    mobileNavActive = color("#FFFFFF"),
                    mobileNavInactive = color("#F0F0F0"),
                    systemBar = color("#000000"),
                    tvNavBackground = color("#000000"),
                    tvHeaderPrimary = color("#FFFFFF"),
                    tvHeaderSecondary = color("#F0F0F0"),
                )
            }
        } else {
            base
        }
    }

    @ColorInt
    private fun color(hex: String): Int = Color.parseColor(hex)
}
