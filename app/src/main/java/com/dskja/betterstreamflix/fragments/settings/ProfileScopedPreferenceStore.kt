package com.dskja.betterstreamflix.fragments.settings

import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Routes profile-scoped settings widgets at the active profile's keys.
 *
 * Preference XML keys are unsuffixed. Without this store, Switch/List widgets
 * persist into the default profile's SharedPreferences entry and can also
 * write a string over an int (parental max age).
 *
 * Parental PIN keys are intentionally absent: the settings UI clears the
 * widget text so the PIN is not echoed, and that must not wipe the stored PIN.
 */
internal object ProfileScopedPreferenceStore {

    val SCOPED_KEYS = setOf(
        "LIBRARY_SCOPE",
        "SHOW_CONTINUE_WATCHING",
        "TV_AMBIENT_MODE",
        "SHOW_RECENTLY_WATCHED",
        "SHOW_NEXT_EPISODES",
        "SHOW_SMART_COLLECTIONS",
        "SPOILER_SHIELD_ENABLED",
        "KIDS_BEDTIME_ENABLED",
        "PARENTAL_CONTROL_MAX_AGE",
        "SELECTED_THEME",
    )

    fun attach(fragment: PreferenceFragmentCompat) {
        val screen = fragment.preferenceScreen ?: return
        walk(screen)
    }

    private fun walk(group: PreferenceGroup) {
        for (index in 0 until group.preferenceCount) {
            val preference = group.getPreference(index)
            if (preference.key in SCOPED_KEYS) {
                preference.preferenceDataStore = Store
            }
            if (preference is PreferenceGroup) {
                walk(preference)
            }
        }
    }

    private object Store : PreferenceDataStore() {
        override fun putString(key: String, value: String?) {
            when (key) {
                "LIBRARY_SCOPE" -> {
                    UserPreferences.libraryScope = UserPreferences.LibraryScope.fromKey(value)
                }
                "PARENTAL_CONTROL_MAX_AGE" -> {
                    val age = value?.trim()?.toIntOrNull()
                    UserPreferences.parentalControlMaxAge = age
                    if (ProfileManager.isKidsActive() && age != null) {
                        ProfileManager.updateMaxAgeRating(ProfileManager.activeProfileId, age)
                    }
                }
                "SELECTED_THEME" -> {
                    if (!value.isNullOrBlank()) {
                        UserPreferences.selectedTheme = value
                    }
                }
            }
        }

        override fun getString(key: String, defValue: String?): String? = when (key) {
            "LIBRARY_SCOPE" -> UserPreferences.libraryScope.key
            "PARENTAL_CONTROL_MAX_AGE" ->
                UserPreferences.parentalControlMaxAge?.toString().orEmpty()
            "SELECTED_THEME" -> UserPreferences.selectedTheme
            else -> defValue
        }

        override fun putBoolean(key: String, value: Boolean) {
            when (key) {
                "SHOW_CONTINUE_WATCHING" -> UserPreferences.showContinueWatching = value
                "TV_AMBIENT_MODE" -> UserPreferences.tvAmbientModeEnabled = value
                "SHOW_RECENTLY_WATCHED" -> UserPreferences.showRecentlyWatched = value
                "SHOW_NEXT_EPISODES" -> UserPreferences.showNextEpisodes = value
                "SHOW_SMART_COLLECTIONS" -> UserPreferences.showSmartCollections = value
                "SPOILER_SHIELD_ENABLED" -> UserPreferences.spoilerShieldEnabled = value
                "SHOW_WATCHLIST_WEEK" -> UserPreferences.showWatchlistWeek = value
                "KIDS_BEDTIME_ENABLED" -> UserPreferences.kidsBedtimeEnabled = value
            }
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean = when (key) {
            "SHOW_CONTINUE_WATCHING" -> UserPreferences.showContinueWatching
            "TV_AMBIENT_MODE" -> UserPreferences.tvAmbientModeEnabled
            "SHOW_RECENTLY_WATCHED" -> UserPreferences.showRecentlyWatched
            "SHOW_NEXT_EPISODES" -> UserPreferences.showNextEpisodes
            "SHOW_SMART_COLLECTIONS" -> UserPreferences.showSmartCollections
            "SPOILER_SHIELD_ENABLED" -> UserPreferences.spoilerShieldEnabled
            "SHOW_WATCHLIST_WEEK" -> UserPreferences.showWatchlistWeek
            "KIDS_BEDTIME_ENABLED" -> UserPreferences.kidsBedtimeEnabled
            else -> defValue
        }
    }
}
