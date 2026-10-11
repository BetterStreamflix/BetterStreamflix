package com.dskja.betterstreamflix.smartcollections

import android.content.Context
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.player.SeriesAudioPreference
import com.dskja.betterstreamflix.providers.ProviderSmoke
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.TmdbCache
import com.dskja.betterstreamflix.utils.TmdbUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Soft-enrich library titles so Smart Collection heuristics have genres,
 * content ratings, and original language without blocking Home forever.
 */
object SmartCollectionEnricher {

    private const val POOL_CAP = 48
    private const val DEFAULT_CONCURRENCY = 4
    private const val CONSTRAINED_CONCURRENCY = 2

    /**
     * Normalize Watchlist + Continue Watching into Movie/TvShow posters and
     * soft-enrich missing metadata. Episodes become their parent show.
     */
    suspend fun preparePool(
        context: Context,
        favorites: List<AppAdapter.Item>,
        continueWatching: List<AppAdapter.Item>,
    ): List<AppAdapter.Item> {
        val normalized = LinkedHashMap<String, AppAdapter.Item>()
        fun put(item: AppAdapter.Item?) {
            val show = normalize(item) ?: return
            val key = poolKey(show) ?: return
            // Prefer the already-favorited / richer row when both sources contribute.
            val existing = normalized[key]
            if (existing == null) {
                normalized[key] = show
            } else {
                normalized[key] = preferRicher(existing, show)
            }
        }
        favorites.forEach { put(it) }
        continueWatching.forEach { put(it) }
        val pool = normalized.values.take(POOL_CAP).toList()
        if (pool.isEmpty()) return emptyList()

        return withTimeoutOrNull(ProviderSmoke.CW_ENRICH_TIMEOUT_MS) {
            enrichAll(context, pool)
        } ?: pool.map { applyWarmCache(it) }
    }

    fun preferredAudioLookup(context: Context): (String) -> String? = { seriesId ->
        SeriesAudioPreference.get(context, seriesId)
    }

    private fun normalize(item: AppAdapter.Item?): AppAdapter.Item? = when (item) {
        is Movie -> item
        is TvShow -> item
        is Episode -> item.tvShow
        else -> null
    }

    private fun poolKey(item: AppAdapter.Item): String? = when (item) {
        is Movie -> {
            val id = item.imdbId?.takeIf { it.isNotBlank() }
                ?: item.tmdbId?.takeIf { it.isNotBlank() }
                ?: item.id
            "movie:${item.providerName.orEmpty()}:$id"
        }
        is TvShow -> {
            val id = item.imdbId?.takeIf { it.isNotBlank() }
                ?: item.tmdbId?.takeIf { it.isNotBlank() }
                ?: item.id
            "show:${item.providerName.orEmpty()}:$id"
        }
        else -> null
    }

    private fun preferRicher(a: AppAdapter.Item, b: AppAdapter.Item): AppAdapter.Item {
        fun score(item: AppAdapter.Item): Int = when (item) {
            is Movie -> {
                var s = 0
                if (item.isFavorite) s += 4
                if (item.genres.isNotEmpty()) s += 2
                if (!item.contentRating.isNullOrBlank()) s += 1
                if (!item.originalLanguage.isNullOrBlank()) s += 1
                if (item.runtime != null) s += 1
                s
            }
            is TvShow -> {
                var s = 0
                if (item.isFavorite) s += 4
                if (item.genres.isNotEmpty()) s += 2
                if (!item.contentRating.isNullOrBlank()) s += 1
                if (!item.originalLanguage.isNullOrBlank()) s += 1
                if (item.runtime != null) s += 1
                s
            }
            else -> 0
        }
        return if (score(b) > score(a)) b else a
    }

    private suspend fun enrichAll(
        context: Context,
        pool: List<AppAdapter.Item>,
    ): List<AppAdapter.Item> = coroutineScope {
        val baseline = if (
            DeviceCapabilities.shouldUseConstrainedPlayback(context) ||
            DeviceCapabilities.isLowRamDevice(context) ||
            DeviceCapabilities.isLeanbackDevice(context)
        ) {
            CONSTRAINED_CONCURRENCY
        } else {
            DEFAULT_CONCURRENCY
        }
        val permits = com.dskja.betterstreamflix.ui.StickEnergyMode.providerParallelism(
            default = baseline,
            context = context,
        )
        val gate = Semaphore(permits)
        pool.map { item ->
            async {
                gate.withPermit { enrichOne(item) }
            }
        }.awaitAll()
    }

    private suspend fun enrichOne(item: AppAdapter.Item): AppAdapter.Item {
        val warmed = applyWarmCache(item)
        if (hasEnoughMeta(warmed)) return warmed
        if (!UserPreferences.enableTmdb) return warmed
        return when (warmed) {
            is Movie -> runCatching {
                TmdbUtils.enrichMovieDetail(warmed).also { enriched ->
                    // Preserve library stamps enrich may not copy through every path.
                    enriched.providerName = warmed.providerName
                    enriched.isFavorite = warmed.isFavorite
                    enriched.favoritedAtMillis = warmed.favoritedAtMillis
                    enriched.lastPlayedAtMillis = warmed.lastPlayedAtMillis
                    enriched.watchHistory = warmed.watchHistory
                    if (enriched.originalLanguage.isNullOrBlank()) {
                        enriched.originalLanguage = warmed.originalLanguage
                    }
                }
            }.getOrDefault(warmed)
            is TvShow -> runCatching {
                TmdbUtils.enrichTvShowDetail(warmed).also { enriched ->
                    enriched.providerName = warmed.providerName
                    enriched.isFavorite = warmed.isFavorite
                    enriched.favoritedAtMillis = warmed.favoritedAtMillis
                    enriched.lastPlayedAtMillis = warmed.lastPlayedAtMillis
                    if (enriched.originalLanguage.isNullOrBlank()) {
                        enriched.originalLanguage = warmed.originalLanguage
                    }
                }
            }.getOrDefault(warmed)
            else -> warmed
        }
    }

    private fun hasEnoughMeta(item: AppAdapter.Item): Boolean = when (item) {
        is Movie -> item.genres.isNotEmpty() &&
            (!item.contentRating.isNullOrBlank() || !item.originalLanguage.isNullOrBlank())
        is TvShow -> item.genres.isNotEmpty() &&
            (!item.contentRating.isNullOrBlank() || !item.originalLanguage.isNullOrBlank())
        else -> true
    }

    private fun applyWarmCache(item: AppAdapter.Item): AppAdapter.Item {
        val lang = UserPreferences.currentProvider?.language
        return when (item) {
            is Movie -> {
                val tmdbId = item.tmdbId?.toIntOrNull() ?: return item
                val cached = TmdbCache.getMovie(tmdbId, lang) ?: return item
                if (item.genres.isEmpty() && cached.genres.isNotEmpty()) {
                    // Movie.genres is a constructor val — copy to attach.
                    return item.copy(
                        genres = cached.genres.map { Genre(it.first, it.second) },
                        runtime = item.runtime ?: cached.runtime,
                        rating = item.rating ?: cached.rating,
                        imdbId = item.imdbId ?: cached.imdbId,
                    ).also { copy ->
                        copy.tmdbId = item.tmdbId ?: cached.id.toString()
                        copy.contentRating = item.contentRating ?: cached.contentRating
                        copy.originalLanguage = item.originalLanguage ?: cached.originalLanguage
                        copy.providerName = item.providerName
                        copy.isFavorite = item.isFavorite
                        copy.favoritedAtMillis = item.favoritedAtMillis
                        copy.lastPlayedAtMillis = item.lastPlayedAtMillis
                        copy.watchHistory = item.watchHistory
                        copy.logo = item.logo ?: cached.logo
                        copy.logoLanguage = item.logoLanguage ?: cached.logoLanguage
                    }
                }
                if (item.contentRating.isNullOrBlank()) {
                    item.contentRating = cached.contentRating
                }
                if (item.originalLanguage.isNullOrBlank()) {
                    item.originalLanguage = cached.originalLanguage
                }
                item
            }
            is TvShow -> {
                val tmdbId = item.tmdbId?.toIntOrNull() ?: return item
                val cached = TmdbCache.getTv(tmdbId, lang) ?: return item
                if (item.genres.isEmpty() && cached.genres.isNotEmpty()) {
                    return item.copy(
                        genres = cached.genres.map { Genre(it.first, it.second) },
                        runtime = item.runtime,
                        rating = item.rating ?: cached.rating,
                        imdbId = item.imdbId ?: cached.imdbId,
                    ).also { copy ->
                        copy.tmdbId = item.tmdbId ?: cached.id.toString()
                        copy.contentRating = item.contentRating ?: cached.contentRating
                        copy.originalLanguage = item.originalLanguage ?: cached.originalLanguage
                        copy.providerName = item.providerName
                        copy.isFavorite = item.isFavorite
                        copy.favoritedAtMillis = item.favoritedAtMillis
                        copy.lastPlayedAtMillis = item.lastPlayedAtMillis
                        copy.logo = item.logo ?: cached.logo
                        copy.logoLanguage = item.logoLanguage ?: cached.logoLanguage
                    }
                }
                if (item.contentRating.isNullOrBlank()) {
                    item.contentRating = cached.contentRating
                }
                if (item.originalLanguage.isNullOrBlank()) {
                    item.originalLanguage = cached.originalLanguage
                }
                item
            }
            else -> item
        }
    }
}
