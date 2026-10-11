package com.dskja.betterstreamflix.ui

import android.view.KeyEvent
import android.view.View
import androidx.fragment.app.Fragment
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.ThemeManager
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Cinema Room coordinator for TV Home — brand entry over Ambient idle,
 * Looks / Trailer / Skip / End-Card / Clean Exit stay composed via
 * [CinemaRoomPolicy] (not reimplemented here).
 *
 * Remote long-idle (= Ambient 90s) is the living-room “power” moment.
 */
class CinemaRoomController(
    fragment: Fragment,
    root: View,
    homeContent: View,
    homeChrome: List<View>,
    modalBlocking: () -> Boolean = { false },
) {
    private val ambient = AmbientModeController(
        fragment = fragment,
        root = root,
        homeContent = homeContent,
        homeChrome = homeChrome,
        modalBlocking = modalBlocking,
    )

    fun isEnabled(): Boolean =
        CinemaRoomPolicy.resolveEnabled(UserPreferences.cinemaRoomEnabled)

    fun isAmbientActive(): Boolean = ambient.isActive()

    fun onUserActivity() {
        ambient.onUserActivity()
    }

    fun resetIdleClock() {
        ambient.resetIdleClock()
    }

    fun recheckGatesWhileActive() {
        ambient.recheckGatesWhileActive()
    }

    fun onKeyEvent(event: KeyEvent): Boolean = ambient.onKeyEvent(event)

    fun onCategoriesBound(categories: List<Category>) {
        ambient.onCategoriesBound(categories)
    }

    fun onStart() {
        if (isEnabled()) {
            applyLooksHintForActiveProfile()
        }
        ambient.onStart()
    }

    fun onStop() {
        ambient.onStop()
    }

    fun destroy() {
        ambient.destroy()
    }

    companion object {
        /**
         * Apply [CinemaRoomPolicy.looksHint] for the active profile class.
         * Kids → Kids Bright; adult keeps a locked look or hints Cinema Ink.
         */
        fun applyLooksHintForActiveProfile() {
            val kids = ProfileManager.isKidsActive()
            val hinted = CinemaRoomPolicy.looksHint(UserPreferences.selectedTheme, kids)
            val resolved = ThemeManager.resolveLook(hinted)
            if (UserPreferences.selectedTheme != resolved) {
                UserPreferences.selectedTheme = resolved
            }
        }

        /** Call when the user turns Cinema Room on in Settings. */
        fun onCinemaRoomEnabled() {
            applyLooksHintForActiveProfile()
        }
    }
}
