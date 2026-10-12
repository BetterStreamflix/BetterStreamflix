package com.dskja.betterstreamflix.fragments.settings

import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import androidx.preference.SwitchPreferenceCompat
import com.dskja.betterstreamflix.notifications.LiveFavoriteStartingChecker
import com.dskja.betterstreamflix.notifications.LiveReminderScheduler
import com.dskja.betterstreamflix.notifications.NotificationCheckScheduler
import com.dskja.betterstreamflix.notifications.NotificationPermission
import com.dskja.betterstreamflix.utils.UserPreferences

object NotificationsSettingsController {

    fun bind(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
    ) {
        bindSwitch(fragment, findPreference, "NOTIFY_WATCHLIST_NEW_EPISODE") { enabled ->
            UserPreferences.notifyWatchlistNewEpisode = enabled
        }
        bindSwitch(fragment, findPreference, "NOTIFY_LIVE_FAVORITE_STARTING") { enabled ->
            UserPreferences.notifyLiveFavoriteStarting = enabled
            if (enabled) {
                LiveFavoriteStartingChecker.resetBaseline()
            } else {
                // Cancel outstanding pre-kickoff OneTime work on opt-out.
                LiveReminderScheduler.syncFromFavorites(fragment.requireContext(), emptyList())
            }
        }
        bindSwitch(fragment, findPreference, "DOWNLOAD_NOTIFY_COMPLETE") { enabled ->
            UserPreferences.downloadNotifyComplete = enabled
        }

        // Refresh checked state from prefs (screen re-inflate).
        (findPreference("NOTIFY_WATCHLIST_NEW_EPISODE") as? SwitchPreference)
            ?.isChecked = UserPreferences.notifyWatchlistNewEpisode
        (findPreference("NOTIFY_WATCHLIST_NEW_EPISODE") as? SwitchPreferenceCompat)
            ?.isChecked = UserPreferences.notifyWatchlistNewEpisode
        (findPreference("NOTIFY_LIVE_FAVORITE_STARTING") as? SwitchPreference)
            ?.isChecked = UserPreferences.notifyLiveFavoriteStarting
        (findPreference("NOTIFY_LIVE_FAVORITE_STARTING") as? SwitchPreferenceCompat)
            ?.isChecked = UserPreferences.notifyLiveFavoriteStarting
        (findPreference("DOWNLOAD_NOTIFY_COMPLETE") as? SwitchPreference)
            ?.isChecked = UserPreferences.downloadNotifyComplete
        (findPreference("DOWNLOAD_NOTIFY_COMPLETE") as? SwitchPreferenceCompat)
            ?.isChecked = UserPreferences.downloadNotifyComplete
    }

    private fun bindSwitch(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
        key: String,
        onChanged: (Boolean) -> Unit,
    ) {
        val pref = findPreference(key) ?: return
        pref.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            onChanged(enabled)
            if (enabled) {
                fragment.activity?.let { NotificationPermission.maybeRequest(it) }
            }
            NotificationCheckScheduler.sync(fragment.requireContext())
            if (enabled) {
                NotificationCheckScheduler.enqueueImmediate(fragment.requireContext())
            }
            true
        }
    }
}
