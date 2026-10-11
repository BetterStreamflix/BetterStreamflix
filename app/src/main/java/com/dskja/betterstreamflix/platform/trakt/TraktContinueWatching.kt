package com.dskja.betterstreamflix.platform.trakt

import android.util.Log
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.WatchItem
import com.dskja.betterstreamflix.utils.TmdbUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Maps Trakt `/sync/playback` into Continue Watching items.
 */
object TraktContinueWatching {
    private const val TAG = "TraktCW"
    private val isoFormats = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ssZ",
    )

    suspend fun load(): List<AppAdapter.Item> {
        if (!TraktConfig.configured()) return emptyList()
        return runCatching {
            val parsed = parsePlayback(TraktClient.fetchPlayback())
            hydratePosters(parsed)
        }.getOrElse {
            Log.w(TAG, "load failed: ${it.message}")
            emptyList()
        }
    }

    fun parsePlayback(items: JSONArray): List<AppAdapter.Item> {
        val out = mutableListOf<AppAdapter.Item>()
        for (i in 0 until items.length()) {
            val row = items.optJSONObject(i) ?: continue
            val progress = row.optDouble("progress", 0.0)
            val pausedAt = parsePausedAt(row)
            val type = row.optString("type")
            when (type) {
                "movie" -> {
                    val movie = row.optJSONObject("movie") ?: continue
                    val ids = movie.optJSONObject("ids")
                    val imdb = ids?.optString("imdb")?.ifBlank { null }
                    val tmdb = ids?.opt("tmdb")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                    val runtimeMin = movie.optInt("runtime", 0).takeIf { it > 0 } ?: 120
                    val duration = runtimeMin * 60_000L
                    // Prefer TMDb numeric id over imdb-only so enrich/catalog resolve can match.
                    val mapped = Movie(
                        id = tmdb?.takeIf { it.all(Char::isDigit) } ?: imdb.orEmpty(),
                        title = movie.optString("title"),
                        overview = movie.optString("overview").ifBlank { null },
                        released = movie.opt("year")?.toString()?.takeIf { it.isNotBlank() },
                        runtime = runtimeMin,
                        imdbId = imdb,
                        poster = null,
                    ).apply {
                        // Prefer current provider for playback; Trakt is metadata-only overlay.
                        providerName = UserPreferencesSafe.currentProviderName() ?: "Trakt"
                        if (progress > 0) {
                            watchHistory = WatchItem.WatchHistory(
                                lastEngagementTimeUtcMillis = pausedAt,
                                lastPlaybackPositionMillis = (duration * (progress / 100.0)).toLong(),
                                durationMillis = duration,
                            )
                        }
                    }
                    if (mapped.id.isNotBlank()) out.add(mapped)
                }
                "episode" -> {
                    val episode = row.optJSONObject("episode") ?: continue
                    val show = row.optJSONObject("show") ?: continue
                    val showIds = show.optJSONObject("ids")
                    val showImdb = showIds?.optString("imdb")?.ifBlank { null }
                    val showTmdb = showIds?.opt("tmdb")?.toString()?.ifBlank { null }
                    val season = episode.optInt("season")
                    val number = episode.optInt("number")
                    val runtimeMin = episode.optInt("runtime", 0).takeIf { it > 0 }
                        ?: show.optInt("runtime", 0).takeIf { it > 0 }
                        ?: 45
                    val duration = runtimeMin * 60_000L
                    val showId = showTmdb?.takeIf { it.all(Char::isDigit) }
                        ?: showImdb
                        ?: show.optString("title")
                    // Temporary placeholder id — HomeViewModel.resolveTraktCatalogIds replaces
                    // this with a real provider episode id before navigation.
                    val ep = Episode(
                        id = "trakt:$showId:S${season}E$number",
                        number = number,
                        title = episode.optString("title").ifBlank { null },
                        season = Season(number = season),
                        tvShow = TvShow(
                            id = showId,
                            title = show.optString("title"),
                            imdbId = showImdb,
                            providerName = UserPreferencesSafe.currentProviderName() ?: "Trakt",
                        ),
                    ).apply {
                        if (progress > 0) {
                            watchHistory = WatchItem.WatchHistory(
                                lastEngagementTimeUtcMillis = pausedAt,
                                lastPlaybackPositionMillis = (duration * (progress / 100.0)).toLong(),
                                durationMillis = duration,
                            )
                        }
                    }
                    out.add(ep)
                }
            }
        }
        return out
    }

    /**
     * Fill missing posters via TMDb Find-by-IMDb / title lookup. Catalog id resolution
     * (provider episode/movie ids) happens in [com.dskja.betterstreamflix.fragments.home.HomeViewModel].
     */
    suspend fun hydratePosters(items: List<AppAdapter.Item>): List<AppAdapter.Item> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                items.map { item ->
                    async {
                        when (item) {
                            is Movie -> hydrateMoviePoster(item)
                            is Episode -> hydrateEpisodePoster(item)
                            else -> item
                        }
                    }
                }.awaitAll()
            }
        }

    private suspend fun hydrateMoviePoster(movie: Movie): Movie {
        if (!movie.poster.isNullOrBlank()) return movie
        val language = UserPreferencesSafe.currentProviderLanguage()
        val tmdb = movie.imdbId?.let { runCatching { TmdbUtils.getMovieByImdbId(it, language) }.getOrNull() }
            ?: movie.id.toIntOrNull()?.let { runCatching { TmdbUtils.getMovieById(it, language) }.getOrNull() }
            ?: runCatching {
                TmdbUtils.getMovie(
                    movie.title,
                    year = movie.released?.get(java.util.Calendar.YEAR),
                    language = language,
                )
            }.getOrNull()
        if (tmdb == null) return movie
        return movie.copy(
            id = movie.id,
            poster = tmdb.poster ?: movie.poster,
            banner = tmdb.banner ?: movie.banner,
            overview = movie.overview ?: tmdb.overview,
            imdbId = movie.imdbId ?: tmdb.imdbId,
        ).apply {
            providerName = movie.providerName
            watchHistory = movie.watchHistory
            isWatched = movie.isWatched
            watchedDate = movie.watchedDate
        }
    }

    private suspend fun hydrateEpisodePoster(episode: Episode): Episode {
        val show = episode.tvShow ?: return episode
        val language = UserPreferencesSafe.currentProviderLanguage()
        val tmdbShow = show.imdbId?.let { runCatching { TmdbUtils.getTvShowByImdbId(it, language) }.getOrNull() }
            ?: show.id.toIntOrNull()?.let { runCatching { TmdbUtils.getTvShowById(it, language) }.getOrNull() }
            ?: runCatching { TmdbUtils.getTvShow(show.title, language = language) }.getOrNull()
        val seasonNumber = episode.season?.number ?: 0
        val tmdbEpisode = tmdbShow?.id?.let { showId ->
            runCatching {
                TmdbUtils.getEpisodesBySeason(showId, seasonNumber, language)
            }.getOrNull()?.firstOrNull { it.number == episode.number }
        }
        val mergedShow = (tmdbShow?.copy(
            id = show.id,
            imdbId = show.imdbId ?: tmdbShow.imdbId,
        ) ?: show).apply {
            providerName = show.providerName
            if (poster.isNullOrBlank()) poster = tmdbShow?.poster
            if (banner.isNullOrBlank()) banner = tmdbShow?.banner
        }
        return episode.copy(
            poster = episode.poster ?: tmdbEpisode?.poster ?: mergedShow.poster,
            title = episode.title ?: tmdbEpisode?.title,
            overview = episode.overview ?: tmdbEpisode?.overview,
            tvShow = mergedShow,
            season = episode.season ?: Season(number = seasonNumber),
        ).apply {
            merge(episode)
        }
    }

    fun parsePausedAt(row: JSONObject): Long {
        val asLong = row.optLong("paused_at", 0L)
        if (asLong > 1_000_000_000_000L) return asLong
        if (asLong in 1_000_000_000L..9_999_999_999L) return asLong * 1000L
        return parsePausedAtString(row.optString("paused_at"))
    }

    fun parsePausedAtString(raw: String): Long {
        if (raw.isBlank()) return System.currentTimeMillis()
        raw.toLongOrNull()?.let { n ->
            if (n > 1_000_000_000_000L) return n
            if (n in 1_000_000_000L..9_999_999_999L) return n * 1000L
        }
        for (pattern in isoFormats) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(raw)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return System.currentTimeMillis()
    }

    /** Avoids hard UserPreferences dependency failures in unit tests. */
    private object UserPreferencesSafe {
        fun currentProviderName(): String? =
            runCatching {
                com.dskja.betterstreamflix.utils.UserPreferences.currentProvider?.name
            }.getOrNull()

        fun currentProviderLanguage(): String? =
            runCatching {
                com.dskja.betterstreamflix.utils.UserPreferences.currentProvider?.language
            }.getOrNull()
    }
}
