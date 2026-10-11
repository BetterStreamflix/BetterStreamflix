package com.dskja.betterstreamflix.utils

import com.dskja.betterstreamflix.BetterStreamflixApp
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Show
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.providers.TmdbProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object ParentalControlUtils {

    /** Cap concurrent TMDb age lookups — Fire Stick Search fans out dozens of rows. */
    private const val AGE_LOOKUP_CONCURRENCY_DEFAULT = 6
    private const val AGE_LOOKUP_CONCURRENCY_CONSTRAINED = 2

    private val matureGenreMarkers = listOf(
        "horror", "terror", "thriller", "erotic", "erótica", "erotica",
        "adult", "adulto", "xxx", "18+", "+18", "nc-17", "war", "guerra",
        "belica", "bélica", "crime", "crimen", "slasher", "gore",
        "suspense", "violence", "violencia", "softcore", "porn",
    )

    /** TMDb genre ids commonly unsuitable for kids browse chips. */
    private val matureTmdbGenreIds = setOf(
        "27", // Horror
        "53", // Thriller
        "80", // Crime
        "10752", // War
        "37", // Western (often mature on kids shelves)
    )

    /** Kids profile or parental-control PIN mode — catalog and Live Hub must gate. */
    fun isFilteringActive(): Boolean {
        if (ProfileManager.isKidsActive()) return true
        return UserPreferences.isParentalControlActive
    }

    private fun effectiveMaxAge(): Int? {
        ProfileManager.effectiveMaxAgeRating()?.let { return it }
        return UserPreferences.parentalControlMaxAge
    }

    private fun ageLookupPermits(): Int {
        val ctx = runCatching { BetterStreamflixApp.instance.applicationContext }.getOrNull()
            ?: return AGE_LOOKUP_CONCURRENCY_DEFAULT
        return if (
            DeviceCapabilities.shouldUseConstrainedPlayback(ctx) ||
            DeviceCapabilities.isLowRamDevice(ctx) ||
            DeviceCapabilities.isLeanbackDevice(ctx)
        ) {
            AGE_LOOKUP_CONCURRENCY_CONSTRAINED
        } else {
            AGE_LOOKUP_CONCURRENCY_DEFAULT
        }
    }

    suspend fun filterCategories(categories: List<Category>): List<Category> {
        if (!isFilteringActive()) return categories

        return categories.mapNotNull { category ->
            val filteredItems = filterItems(category.list)
            if (filteredItems.isEmpty()) {
                null
            } else {
                category.copy(list = filteredItems).also { filteredCategory ->
                    filteredCategory.selectedIndex = category.selectedIndex
                        .coerceAtMost(filteredItems.lastIndex.coerceAtLeast(0))
                    filteredCategory.itemSpacing = category.itemSpacing
                }
            }
        }
    }

    suspend fun filterShows(shows: List<Show>): List<Show> {
        return filterItems(shows)
    }

    suspend fun <T : AppAdapter.Item> filterItems(items: List<T>): List<T> {
        if (!isFilteringActive()) return items
        if (items.isEmpty()) return items

        val gate = Semaphore(ageLookupPermits())
        return coroutineScope {
            val visibility = items.map { item ->
                async {
                    gate.withPermit { filterItem(item) != null }
                }
            }.awaitAll()

            items.filterIndexed { index, _ -> visibility[index] }
        }
    }

    /**
     * Hide mature genre chips on blank Search / kids browse.
     * Genres are not Movie/TvShow rows so [filterItems] would otherwise pass them through.
     */
    fun filterGenreChips(items: List<AppAdapter.Item>): List<AppAdapter.Item> {
        if (!ProfileManager.isKidsActive()) return items
        return items.filterNot { item ->
            item is Genre && isMatureGenre(item)
        }
    }

    fun isMatureGenre(genre: Genre): Boolean {
        val id = genre.id.trim()
        if (id in matureTmdbGenreIds) return true
        val hay = "$id ${genre.name}".lowercase()
        return matureGenreMarkers.any { marker -> hay.contains(marker) }
    }

    private suspend fun filterItem(item: AppAdapter.Item): AppAdapter.Item? {
        return when (item) {
            is Movie -> item.takeIf { isAllowedMovie(it) }
            is TvShow -> item.takeIf { isAllowedTvShow(it) }
            is Episode -> item.takeIf { isAllowedEpisode(it) }
            is Genre -> item.takeUnless { ProfileManager.isKidsActive() && isMatureGenre(item) }
            else -> item
        }
    }

    /** Public helper for import paths — respects kids / parental filtering when active. */
    suspend fun isAllowed(movie: Movie): Boolean {
        if (!isFilteringActive()) return true
        return isAllowedMovie(movie)
    }

    /** Public helper for import paths — respects kids / parental filtering when active. */
    suspend fun isAllowed(tvShow: TvShow): Boolean {
        if (!isFilteringActive()) return true
        return isAllowedTvShow(tvShow)
    }

    private suspend fun isAllowedMovie(movie: Movie): Boolean {
        val maxAge = effectiveMaxAge() ?: return true
        // Kids profiles hide unrated titles; parental-control PIN mode keeps prior behavior.
        val ageRating = resolveMovieAgeRating(movie)
            ?: return !ProfileManager.isKidsActive()
        return ageRating <= maxAge
    }

    private suspend fun isAllowedTvShow(tvShow: TvShow): Boolean {
        val maxAge = effectiveMaxAge() ?: return true
        val ageRating = resolveTvShowAgeRating(tvShow)
            ?: return !ProfileManager.isKidsActive()
        return ageRating <= maxAge
    }

    private suspend fun isAllowedEpisode(episode: Episode): Boolean {
        val tvShow = episode.tvShow ?: return false
        return isAllowedTvShow(tvShow)
    }

    private suspend fun resolveMovieAgeRating(movie: Movie): Int? {
        // Prefer attached contentRating to avoid a TMDb round-trip per Search row.
        movie.contentRating
            ?.let { TmdbUtils.parseContentRatingToAge(it) }
            ?.let { return it }

        val provider = resolveProvider(movie.providerName)
        val providerLanguage = provider?.language ?: UserPreferences.currentProvider?.language
        val isTmdbSource = provider is TmdbProvider || (movie.providerName.isNullOrBlank() && UserPreferences.currentProvider is TmdbProvider)

        return when {
            isTmdbSource ->
                movie.id.toIntOrNull()?.let { TmdbUtils.getMovieAgeRatingById(it, providerLanguage) }
                    ?: TmdbUtils.getMovieAgeRating(movie.title, extractYear(movie), providerLanguage)

            else -> TmdbUtils.getMovieAgeRating(movie.title, extractYear(movie), providerLanguage)
        }
    }

    private suspend fun resolveTvShowAgeRating(tvShow: TvShow): Int? {
        tvShow.contentRating
            ?.let { TmdbUtils.parseContentRatingToAge(it) }
            ?.let { return it }

        val provider = resolveProvider(tvShow.providerName)
        val providerLanguage = provider?.language ?: UserPreferences.currentProvider?.language
        val isTmdbSource = provider is TmdbProvider || (tvShow.providerName.isNullOrBlank() && UserPreferences.currentProvider is TmdbProvider)

        return when {
            isTmdbSource ->
                tvShow.id.toIntOrNull()?.let { TmdbUtils.getTvShowAgeRatingById(it, providerLanguage) }
                    ?: TmdbUtils.getTvShowAgeRating(tvShow.title, extractYear(tvShow), providerLanguage)

            else -> TmdbUtils.getTvShowAgeRating(tvShow.title, extractYear(tvShow), providerLanguage)
        }
    }

    private fun extractYear(movie: Movie): Int? = movie.released?.get(java.util.Calendar.YEAR)

    private fun extractYear(tvShow: TvShow): Int? = tvShow.released?.get(java.util.Calendar.YEAR)

    private fun resolveProvider(providerName: String?): Provider? {
        if (providerName.isNullOrBlank()) return UserPreferences.currentProvider
        if (providerName.startsWith("TMDb (") && providerName.endsWith(")")) {
            return TmdbProvider.forLanguage(
                providerName.substringAfter("TMDb (").substringBefore(")")
            )
        }
        return Provider.findByName(providerName)
    }
}
