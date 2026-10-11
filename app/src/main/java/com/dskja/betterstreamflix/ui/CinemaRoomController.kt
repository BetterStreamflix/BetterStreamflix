package com.dskja.betterstreamflix.ui

import android.view.KeyEvent
import android.view.View
import androidx.fragment.app.Fragment
import com.dskja.betterstreamflix.models.Category
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
) {
    private val ambient = AmbientModeController(
        fragment = fragment,
        root = root,
        homeContent = homeContent,
        homeChrome = homeChrome,
    )

    fun isEnabled(): Boolean = UserPreferences.cinemaRoomEnabled

    fun isAmbientActive(): Boolean = ambient.isActive()

    fun onUserActivity() {
        ambient.onUserActivity()
    }

    fun onKeyEvent(event: KeyEvent): Boolean = ambient.onKeyEvent(event)

    fun onCategoriesBound(categories: List<Category>) {
        ambient.onCategoriesBound(categories)
    }

    fun onStart() {
        ambient.onStart()
    }

    fun onStop() {
        ambient.onStop()
    }

    fun destroy() {
        ambient.destroy()
    }
}
