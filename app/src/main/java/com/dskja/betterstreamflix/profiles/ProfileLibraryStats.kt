package com.dskja.betterstreamflix.profiles

import android.content.Context
import com.dskja.betterstreamflix.utils.UserDataCache
import com.dskja.betterstreamflix.utils.UserPreferences

data class ProfileLibraryStats(
    val continueWatching: Int = 0,
    val favorites: Int = 0,
) {
    val hasAnything: Boolean get() = continueWatching > 0 || favorites > 0

    companion object {
        val EMPTY = ProfileLibraryStats()

        /**
         * Memory-only: never hits disk, safe on the main thread for picker chrome.
         */
        fun forProfile(profileId: String): ProfileLibraryStats {
            val provider = UserPreferences.currentProvider ?: return EMPTY
            val data = UserDataCache.peekMemory(provider, profileId) ?: return EMPTY
            return ProfileLibraryStats(
                continueWatching = data.continueWatchingMovies.size + data.continueWatchingEpisodes.size,
                favorites = data.favoritesMovies.size + data.favoritesTvShows.size,
            )
        }

        fun formatMeta(context: Context, stats: ProfileLibraryStats, extras: List<String>): String {
            val parts = extras.toMutableList()
            if (stats.continueWatching > 0) {
                parts += context.resources.getQuantityString(
                    com.dskja.betterstreamflix.R.plurals.profile_stat_continue,
                    stats.continueWatching,
                    stats.continueWatching,
                )
            } else if (stats.favorites > 0) {
                parts += context.resources.getQuantityString(
                    com.dskja.betterstreamflix.R.plurals.profile_stat_favorites,
                    stats.favorites,
                    stats.favorites,
                )
            }
            return parts.joinToString(" · ")
        }
    }
}
