package com.dskja.betterstreamflix.yearreview

import android.content.Context
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.UserPreferences
import java.util.Calendar
import java.util.TimeZone
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads watch events from every on-disk provider DB for the active profile,
 * then aggregates via [YearReviewCalculator].
 */
object YearReviewRepository {

    private const val TAG = "YearReviewRepository"

    suspend fun load(
        context: Context,
        year: Int = Calendar.getInstance().get(Calendar.YEAR),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): YearReviewStats = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val profile = ProfileManager.activeProfile()
        val profileName = profile?.displayName?.takeIf { it.isNotBlank() } ?: "Profile"
        val profileId = ProfileManager.activeProfileId

        val movies = mutableListOf<YearReviewMovieEvent>()
        val episodes = mutableListOf<YearReviewEpisodeEvent>()

        forEachProfileProviderDb(app, profileId) { providerName, db ->
            runCatching {
                collectFromDb(db, providerName, movies, episodes)
            }.onFailure { error ->
                Log.w(TAG, "Year review DB read failed for $providerName", error)
            }
        }

        YearReviewCalculator.compute(
            year = year,
            profileName = profileName,
            movies = movies,
            episodes = episodes,
            timeZone = timeZone,
        )
    }

    private fun collectFromDb(
        db: AppDatabase,
        providerName: String,
        movies: MutableList<YearReviewMovieEvent>,
        episodes: MutableList<YearReviewEpisodeEvent>,
    ) {
        val tvShows = db.tvShowDao().getAllForBackup().associateBy { it.id }

        for (movie in db.movieDao().getAll()) {
            movieEvent(movie)?.let { movies += it }
        }

        for (episode in db.episodeDao().getAllForBackup()) {
            episodeEvent(episode, tvShows, providerName)?.let { episodes += it }
        }
    }

    private fun movieEvent(movie: Movie): YearReviewMovieEvent? {
        if (movie.isWatched) {
            val at = movie.watchedDate?.timeInMillis ?: return null
            // Finish threshold is ~90% — credit watched portion, not full catalog runtime.
            val catalog = movie.runtime?.takeIf { it > 0 }
            val credited = catalog?.let { (it * 0.9).toInt().coerceAtLeast(1) }
            return YearReviewMovieEvent(
                id = movie.id,
                title = movie.title,
                runtimeMinutes = credited,
                watchedAtMs = at,
            )
        }
        val history = movie.watchHistory ?: return null
        if (history.lastPlaybackPositionMillis <= 0L) return null
        return YearReviewMovieEvent(
            id = movie.id,
            title = movie.title,
            runtimeMinutes = movie.runtime,
            watchedAtMs = history.lastEngagementTimeUtcMillis,
            inProgressPositionMs = history.lastPlaybackPositionMillis
                .coerceAtMost(history.durationMillis.takeIf { it > 0 } ?: Long.MAX_VALUE),
        )
    }

    private fun episodeEvent(
        episode: Episode,
        tvShows: Map<String, com.dskja.betterstreamflix.models.TvShow>,
        providerName: String,
    ): YearReviewEpisodeEvent? {
        val showId = episode.tvShow?.id?.takeIf { it.isNotBlank() } ?: return null
        val show = tvShows[showId]
        val title = show?.title?.takeIf { it.isNotBlank() }
            ?: episode.tvShow?.title?.takeIf { it.isNotBlank() }
            ?: showId
        val history = episode.watchHistory
        val runtimeFromHistory = history?.durationMillis
            ?.takeIf { it > 0L }
            ?.let { (it / 60_000L).toInt().coerceAtLeast(1) }
        val runtime = runtimeFromHistory ?: show?.runtime ?: episode.tvShow?.runtime
        val poster = show?.poster ?: episode.tvShow?.poster ?: episode.poster
        val showKey = title.trim().lowercase()
        if (episode.isWatched) {
            val at = episode.watchedDate?.timeInMillis ?: return null
            return YearReviewEpisodeEvent(
                id = episode.id,
                showKey = showKey,
                showTitle = title,
                showPoster = poster,
                runtimeMinutes = runtime,
                watchedAtMs = at,
            )
        }
        if (history == null || history.lastPlaybackPositionMillis <= 0L) return null
        return YearReviewEpisodeEvent(
            id = episode.id,
            showKey = showKey,
            showTitle = title,
            showPoster = poster,
            runtimeMinutes = runtime,
            watchedAtMs = history.lastEngagementTimeUtcMillis,
            inProgressPositionMs = history.lastPlaybackPositionMillis
                .coerceAtMost(history.durationMillis.takeIf { it > 0 } ?: Long.MAX_VALUE),
        )
    }

    /**
     * Every provider DB file that exists for [profileId] (not limited by library scope).
     */
    private inline fun forEachProfileProviderDb(
        context: Context,
        profileId: String,
        block: (providerName: String, db: AppDatabase) -> Unit,
    ) {
        val current = UserPreferences.currentProvider
        val currentName = current?.name
        val currentDb = if (currentName != null) {
            AppDatabase.getInstanceOrNull(context)
        } else {
            null
        }

        val providers = Provider.allKnown()
            .distinctBy { it.name }
            .filter { provider ->
                context.getDatabasePath(
                    AppDatabase.databaseNameFor(provider.name, profileId),
                ).exists()
            }
            .ifEmpty { listOfNotNull(current) }

        for (provider in providers) {
            if (provider.name == currentName &&
                currentDb != null &&
                ProfileManager.activeProfileId == profileId
            ) {
                block(provider.name, currentDb)
            } else {
                val opened = AppDatabase.openForProfile(provider.name, context, profileId)
                try {
                    block(provider.name, opened.database)
                } finally {
                    if (opened.ownsConnection) opened.database.close()
                }
            }
        }
    }
}
