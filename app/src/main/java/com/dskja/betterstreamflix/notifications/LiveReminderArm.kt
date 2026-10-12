package com.dskja.betterstreamflix.notifications

import android.app.Activity
import android.content.Context
import androidx.fragment.app.Fragment
import com.dskja.betterstreamflix.kids.KidsFortressPolicy
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.LiveHubCatalog

/**
 * Shared UI arming for Live reminder opt-in (Settings + Live Command).
 * Resets baseline, requests POST_NOTIFICATIONS, and starts the check pipeline.
 */
object LiveReminderArm {

    /** True when Kids Fortress or parental age-gate must suppress Live alerts. */
    fun deliveryBlocked(): Boolean {
        if (LiveHubCatalog.isAgeGated()) return true
        return !KidsFortressPolicy.liveHubAllowed(ProfileManager.isKidsActive())
    }

    fun armFromUi(context: Context, activity: Activity? = null) {
        LiveFavoriteStartingChecker.resetBaseline()
        activity?.let { NotificationPermission.maybeRequest(it) }
        val app = context.applicationContext
        NotificationCheckScheduler.sync(app)
        NotificationCheckScheduler.enqueueImmediate(app)
    }

    fun armFromUi(fragment: Fragment) {
        armFromUi(fragment.requireContext(), fragment.activity)
    }

    /** Turn off Live reminder opt-in, cancel OneTime schedules, and resync the check worker. */
    fun disarmFromUi(context: Context) {
        com.dskja.betterstreamflix.utils.UserPreferences.notifyLiveFavoriteStarting = false
        LiveReminderScheduler.cancelAll(context)
        NotificationCheckScheduler.sync(context.applicationContext)
    }
}
