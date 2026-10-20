package com.dskja.betterstreamflix.utils

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.dskja.betterstreamflix.R

/**
 * Single locked look — "Dark" (Cinema-Ink near-black shell).
 * Every surface, chrome, and gradient comes from this one look.
 */
object ThemeManager {
    /** Dark — near-black shell (formerly "Cinema-Ink Dark"). */
    const val CINEMA_INK = "cinema_ink"

    /** The only look — kept for signature compat with sync/lookup code. */
    val LOCKED_LOOKS: List<String> = listOf(CINEMA_INK)

    // Legacy preference values — mapped by [resolveLook], never offered in UI.
    const val DEFAULT = "default"
    const val CINEMA_INK_LIGHT = "cinema_ink_light"
    const val KIDS_BRIGHT = "kids_bright"
    const val NERO_AMOLED_OLED = "nero_amoled_oled"
    const val SUNSET_CINEMA = "sunset_cinema"
    const val STEEL_BLUE = "steel_blue"
    const val FOREST_NIGHT = "forest_night"
    const val CRIMSON_NOIR = "crimson_noir"
    const val MIDNIGHT_VIOLET = "midnight_violet"
    const val NORD_FROST = "nord_frost"
    const val EMERALD_LUXE = "emerald_luxe"
    const val RETRO_NEON = "retro_neon"

    fun defaultThemeForKids(@Suppress("UNUSED_PARAMETER") isKids: Boolean): String = CINEMA_INK

    /** Single Dark look — kids profiles render Dark as well. */
    fun clampLookForKids(look: String, @Suppress("UNUSED_PARAMETER") isKids: Boolean): String =
        resolveLook(look)

    /** Collapse any stored theme (retired palettes / Light / Kids / OLED) onto Dark. */
    fun resolveLook(theme: String?): String = CINEMA_INK

    fun isLockedLook(theme: String?): Boolean = theme == CINEMA_INK

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
    fun mobileThemeRes(theme: String): Int = R.style.AppTheme_Mobile_CinemaInk

    @StyleRes
    fun tvThemeRes(theme: String): Int = R.style.AppTheme_CinemaInk

    @StringRes
    fun titleRes(theme: String): Int = R.string.theme_dark

    fun palette(theme: String): Palette {
        val base = Palette(
            mobileNavBackground = color("#0A0A0C"),
            mobileNavActive = color("#F7F7F8"),
            mobileNavInactive = color("#A1A1AA"),
            systemBar = color("#0A0A0C"),
            tvNavBackground = color("#0A0A0C"),
            tvHeaderPrimary = color("#F7F7F8"),
            tvHeaderSecondary = color("#A1A1AA"),
        )
        // High contrast a11y overlay sits on top of the look (text/chrome only).
        return if (UserPreferences.highContrast) {
            base.copy(
                mobileNavBackground = color("#000000"),
                mobileNavActive = color("#FFFFFF"),
                mobileNavInactive = color("#F0F0F0"),
                systemBar = color("#000000"),
                tvNavBackground = color("#000000"),
                tvHeaderPrimary = color("#FFFFFF"),
                tvHeaderSecondary = color("#F0F0F0"),
            )
        } else {
            base
        }
    }

    @ColorInt
    private fun color(hex: String): Int = Color.parseColor(hex)
}
