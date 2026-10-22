package com.betterstreamflix.app.logo

import android.content.Context
import com.betterstreamflix.app.BetterStreamflixApp
import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.utils.TmdbUtils
import com.betterstreamflix.app.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Bulk re-resolve of persisted title logos — favorites plus artwork-repair
 * candidates — after a language/provider change or from the TMDb settings row.
 * Bounded by the session fetch budget and per-resolve timeouts in TmdbUtils;
 * chunks of four keep the resolve pipeline busy without stampeding the API.
 */
object LogoBulkRefresh {

    private const val MAX_ITEMS = 150
    private const val CHUNK = 4

    @Volatile
    var lastRunCount: Int = 0
        private set

    @Volatile
    var running: Boolean = false
        private set

    /** Returns the number of library items queued for re-resolution. */
    suspend fun refreshPersisted(context: Context = BetterStreamflixApp.instance): Int {
        if (running) return 0
        running = true
        try {
            return withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstanceOrNull(context) ?: return@withContext 0
                val movies = (db.movieDao().getFavorites().first() +
                    runCatching { db.movieDao().getArtworkRepairCandidates() }.getOrDefault(emptyList()))
                    .distinctBy { it.id }
                val shows = (db.tvShowDao().getFavorites().first() +
                    runCatching { db.tvShowDao().getArtworkRepairCandidates() }.getOrDefault(emptyList()))
                    .distinctBy { it.id }
                val lang = UserPreferences.currentProvider?.language
                val targets: List<suspend () -> Unit> =
                    movies.take(MAX_ITEMS / 2).map { m -> suspend { refreshMovie(m, lang) } } +
                        shows.take(MAX_ITEMS / 2).map { s -> suspend { refreshTv(s, lang) } }
                lastRunCount = targets.size
                targets.chunked(CHUNK).forEach { chunk ->
                    coroutineScope {
                        chunk.map { async { runCatching { it() } } }.awaitAll()
                    }
                }
                targets.size
            }
        } finally {
            running = false
        }
    }

    private suspend fun refreshMovie(movie: Movie, lang: String?) {
        val url = TmdbUtils.resolveTitleLogo(
            title = movie.title,
            isTv = false,
            tmdbId = movie.tmdbId,
            imdbId = movie.imdbId,
        ) ?: return
        movie.logo = url
        movie.logoLanguage = lang
        movie.logoSource = TmdbLogoPicker.inferSource(url)
        LogoPersist.persistMovieLogo(movie)
    }

    private suspend fun refreshTv(show: TvShow, lang: String?) {
        val url = TmdbUtils.resolveTitleLogo(
            title = show.title,
            isTv = true,
            tmdbId = show.tmdbId,
            imdbId = show.imdbId,
        ) ?: return
        show.logo = url
        show.logoLanguage = lang
        show.logoSource = TmdbLogoPicker.inferSource(url)
        LogoPersist.persistTvLogo(show)
    }
}
