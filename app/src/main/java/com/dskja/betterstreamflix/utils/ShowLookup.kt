package com.dskja.betterstreamflix.utils

import android.util.Log
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.Provider
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Resolves a movie / series for detail pages when the incoming id is a TMDb
 * person-credit id (or any id the current HTML provider does not understand).
 *
 * Order: provider id → provider retry on a 5xx → TMDb metadata (numeric) →
 * provider search by title → TMDb by humanized slug.
 */
object ShowLookup {

    private const val TAG = "ShowLookup"
    private const val SERVER_ERROR_RETRY_DELAY_MS = 600L

    suspend fun movie(provider: Provider, id: String): Movie {
        var primary = runCatching { provider.getMovie(id) }
        primary.getOrNull()?.let { return it }

        if (HttpFailures.isServerError(primary.exceptionOrNull())) {
            Log.w(TAG, "Provider returned a server error for movie $id, retrying", primary.exceptionOrNull())
            delay(SERVER_ERROR_RETRY_DELAY_MS)
            primary = runCatching { provider.getMovie(id) }
            primary.getOrNull()?.let { return it }
        }

        val tmdb = numericId(id)?.let { tmdbId ->
            runCatching { TmdbUtils.getMovieById(tmdbId, provider.language) }.getOrNull()
        }
        searchMovie(provider, tmdb?.title)?.let { return it }

        tmdb?.let { return it }

        // Provider slugs like "the-dark-knight-2008" still map onto TMDb on a 5xx.
        val slugTitle = humanizeSlug(id)
        if (!slugTitle.isNullOrBlank()) {
            searchMovie(provider, slugTitle)?.let { return it }
            runCatching {
                TmdbUtils.getMovie(slugTitle, year = yearFromSlug(id), language = provider.language)
            }.getOrNull()?.let { return it }
        }

        throw primary.exceptionOrNull() ?: IllegalStateException("Movie not found: $id")
    }

    suspend fun tvShow(provider: Provider, id: String): TvShow {
        var primary = runCatching { provider.getTvShow(id) }
        primary.getOrNull()?.let { return it }

        if (HttpFailures.isServerError(primary.exceptionOrNull())) {
            Log.w(TAG, "Provider returned a server error for series $id, retrying", primary.exceptionOrNull())
            delay(SERVER_ERROR_RETRY_DELAY_MS)
            primary = runCatching { provider.getTvShow(id) }
            primary.getOrNull()?.let { return it }
        }

        val tmdb = numericId(id)?.let { tmdbId ->
            runCatching { TmdbUtils.getTvShowById(tmdbId, provider.language) }.getOrNull()
        }
        searchTvShow(provider, tmdb?.title)?.let { return it }

        tmdb?.let { return it }

        val slugTitle = humanizeSlug(id)
        if (!slugTitle.isNullOrBlank()) {
            searchTvShow(provider, slugTitle)?.let { return it }
            runCatching {
                TmdbUtils.getTvShow(slugTitle, year = yearFromSlug(id), language = provider.language)
            }.getOrNull()?.let { return it }
        }

        throw primary.exceptionOrNull() ?: IllegalStateException("TV show not found: $id")
    }

    internal fun titlesMatch(left: String, right: String): Boolean {
        val a = normalize(left)
        val b = normalize(right)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    /** Turns provider path ids into a searchable title ("the-batman-2022" → "the batman"). */
    internal fun humanizeSlug(id: String): String? {
        val leaf = id.trim()
            .substringAfterLast('/')
            .substringBefore('?')
            .trim()
        if (leaf.isEmpty() || leaf.all { it.isDigit() }) return null
        val withoutYear = leaf.replace(Regex("[-_]?(19|20)\\d{2}$"), "")
        val words = withoutYear
            .replace(Regex("[._]+"), " ")
            .replace('-', ' ')
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        return words.takeIf { it.length >= 2 }
    }

    internal fun yearFromSlug(id: String): Int? {
        val match = Regex("(19|20)\\d{2}").findAll(id).lastOrNull() ?: return null
        return match.value.toIntOrNull()
    }

    private suspend fun searchMovie(provider: Provider, title: String?): Movie? {
        val query = title?.trim().orEmpty()
        if (query.isEmpty()) return null
        val hit = runCatching { provider.search(query, 1) }.getOrNull()
            ?.filterIsInstance<Movie>()
            ?.firstOrNull { titlesMatch(it.title, query) }
            ?: return null
        return runCatching { provider.getMovie(hit.id) }.getOrDefault(hit)
    }

    private suspend fun searchTvShow(provider: Provider, title: String?): TvShow? {
        val query = title?.trim().orEmpty()
        if (query.isEmpty()) return null
        val hit = runCatching { provider.search(query, 1) }.getOrNull()
            ?.filterIsInstance<TvShow>()
            ?.firstOrNull { titlesMatch(it.title, query) }
            ?: return null
        return runCatching { provider.getTvShow(hit.id) }.getOrDefault(hit)
    }

    private fun numericId(id: String): Int? =
        id.trim().takeIf { it.isNotEmpty() && it.all { ch -> ch.isDigit() } }?.toIntOrNull()

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")
}
