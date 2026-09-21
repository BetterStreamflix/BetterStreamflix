package com.dskja.betterstreamflix.utils

import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.Provider
import java.util.Locale

/**
 * Resolves a movie / series for detail pages when the incoming id is a TMDb
 * person-credit id (or any id the current HTML provider does not understand).
 *
 * Order: provider id → TMDb metadata (numeric) → provider search by title.
 */
object ShowLookup {

    suspend fun movie(provider: Provider, id: String): Movie {
        val primary = runCatching { provider.getMovie(id) }
        primary.getOrNull()?.let { return it }

        val tmdb = numericId(id)?.let { tmdbId ->
            runCatching { TmdbUtils.getMovieById(tmdbId, provider.language) }.getOrNull()
        }
        searchMovie(provider, tmdb?.title)?.let { return it }

        tmdb?.let { return it }
        throw primary.exceptionOrNull() ?: IllegalStateException("Movie not found: $id")
    }

    suspend fun tvShow(provider: Provider, id: String): TvShow {
        val primary = runCatching { provider.getTvShow(id) }
        primary.getOrNull()?.let { return it }

        val tmdb = numericId(id)?.let { tmdbId ->
            runCatching { TmdbUtils.getTvShowById(tmdbId, provider.language) }.getOrNull()
        }
        searchTvShow(provider, tmdb?.title)?.let { return it }

        tmdb?.let { return it }
        throw primary.exceptionOrNull() ?: IllegalStateException("TV show not found: $id")
    }

    internal fun titlesMatch(left: String, right: String): Boolean {
        val a = normalize(left)
        val b = normalize(right)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
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
