package com.dskja.betterstreamflix.remotefind

import androidx.fragment.app.FragmentActivity
import androidx.navigation.fragment.NavHostFragment
import com.dskja.betterstreamflix.R
import java.lang.ref.WeakReference

/**
 * Ensures TV Search is open when the phone starts typing.
 */
object RemoteFindNavBridge {
    @Volatile
    private var activityRef: WeakReference<FragmentActivity>? = null

    fun attach(activity: FragmentActivity) {
        activityRef = WeakReference(activity)
    }

    fun detach(activity: FragmentActivity) {
        if (activityRef?.get() === activity) {
            activityRef = null
        }
    }

    fun openSearch() {
        val activity = activityRef?.get() ?: return
        activity.runOnUiThread {
            val navHost =
                activity.supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
                    ?: return@runOnUiThread
            val nav = navHost.navController
            if (nav.currentDestination?.id != R.id.search) {
                runCatching { nav.navigate(R.id.search) }
            }
        }
    }
}
