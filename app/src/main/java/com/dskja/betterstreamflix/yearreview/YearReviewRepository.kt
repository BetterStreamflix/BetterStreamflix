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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads watch events from every on-disk provider DB for the active profile,
 * then aggregates via [YearReviewCalculator].
 */
object YearReviewRepository {

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
            return YearReviewMovieEvent(
                id = movie.id,
                title = movie.title,
                runtimeMinutes = movie.runtime,
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
        if (!episode.isWatched) return null
        val at = episode.watchedDate?.timeInMillis ?: return null
        val showId = episode.tvShow?.id?.takeIf { it.isNotBlank() } ?: return null
        val show = tvShows[showId]
        val title = show?.title?.takeIf { it.isNotBlank() }
            ?: episode.tvShow?.title?.takeIf { it.isNotBlank() }
            ?: showId
        val runtime = show?.runtime ?: episode.tvShow?.runtime
        val poster = show?.poster ?: episode.tvShow?.poster ?: episode.poster
        return YearReviewEpisodeEvent(
            id = episode.id,
            showKey = "$providerName:$showId",
            showTitle = title,
            showPoster = poster,
            runtimeMinutes = runtime,
            watchedAtMs = at,
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
