package com.dskja.betterstreamflix.utils

import com.dskja.betterstreamflix.R

/**
 * Retired "Lumina" shell — removed in favour of the single Dark look.
 * Kept as a no-op shim so call sites compile; every gate returns false and
 * every visual getter maps onto the classic Cinema-Ink drawable. Do not add
 * new references — delete dead branches when touching a call site.
 */
@Deprecated("Lumina shell removed; Dark theme is the only look")
object ExperimentalMobileDesign {

    fun isAvailable(): Boolean = false

    fun enabled(): Boolean = false

    fun useReactShell(): Boolean = false

    /** Clear prefs for retired surfaces only. */
    fun enforceAvailabilityGate() {
        if (UserPreferences.experimentalReactHome) {
            UserPreferences.experimentalReactHome = false
        }
    }

    fun layout(defaultRes: Int, @Suppress("UNUSED_PARAMETER") experimentalRes: Int): Int = defaultRes

    fun pureBlack(): Boolean = false

    fun dynamicColors(): Boolean = false

    fun navAutoHide(): Boolean = false

    fun heroParallax(): Boolean = false

    fun reducedGlass(): Boolean = false

    fun glassCardBackground(): Int = R.drawable.bg_cinema_glass_card

    fun bottomSheetBackground(): Int = R.drawable.bg_profile_dialog

    fun castMiniBackground(): Int = R.drawable.bg_cinema_glass_card

    fun navPillBackground(): Int = R.drawable.bg_meta_pill

    fun dialogBackground(): Int = R.drawable.bg_profile_dialog

    fun metaPillBackground(): Int = R.drawable.bg_meta_pill

    fun searchFieldBackground(): Int = R.drawable.bg_search_edittext

    fun chipBackground(): Int = R.drawable.bg_outlined_chip

    fun optionItemBackground(): Int = R.drawable.bg_outlined_chip

    fun iconChipBackground(): Int = R.drawable.bg_detail_icon_chip

    fun controlsPillBackground(): Int = R.drawable.bg_meta_pill

    fun liveIndicatorBackground(): Int = R.drawable.bg_meta_pill

    fun spinnerBackground(): Int = R.drawable.bg_profile_dialog

    fun primaryButtonBackground(): Int = R.drawable.bg_button_mobile

    /** Walk [root] — retired no-op kept for call-site compat. */
    fun applyReducedGlass(root: android.view.View?) = Unit

    /** Classic accent for out-of-process surfaces (TV bypass landing HTML). */
    fun accentCssHex(): String = "#5B8CFF"
}
