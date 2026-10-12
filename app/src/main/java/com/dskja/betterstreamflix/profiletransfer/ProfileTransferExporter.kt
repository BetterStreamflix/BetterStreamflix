package com.dskja.betterstreamflix.profiletransfer

import android.content.Context
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.CrossProviderLibrary
import com.dskja.betterstreamflix.utils.EpisodeNotesStore
import com.dskja.betterstreamflix.utils.LiveHubCatalog
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds a profile transfer payload from the active profile.
 *
 * Provider favorite layout (sort/order) is snapshotted only for providers present in
 * the exported watchlist — other providers' shelf layout is intentionally omitted (EI-010).
 */
object ProfileTransferExporter {

    suspend fun exportActive(context: Context): ProfileTransferPayload = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val profile = ProfileManager.activeProfile()
        val history = CrossProviderLibrary.loadHomeHistory(app)
        val watchlist = buildList {
            history.favoriteMovies.forEach { movie ->
                add(movie.toItem())
            }
            history.favoriteTvShows
                .filter { !LiveHubCatalog.isLiveFavorite(it) }
                .forEach { show ->
                    add(show.toItem())
                }
        // Silent truncate — see ProfileTransferPayload.MAX_FILE_* caps.
        }.take(ProfileTransferPayload.MAX_FILE_WATCHLIST)

        val providers = watchlist.map { it.provider }.filter { it.isNotBlank() }.distinct()
        val settings = snapshotSettings(app, providers)
        val notes = EpisodeNotesStore.listForActiveProfile(app)
            .take(ProfileTransferPayload.MAX_FILE_NOTES)
            .map { (id, text) ->
                ProfileTransferPayload.NoteItem(
                    episodeId = id.take(ProfileTransferPayload.MAX_ID_CHARS),
                    text = EpisodeNotesStore.sanitize(text),
                )
            }
            .filter { it.text.isNotBlank() }

        ProfileTransferPayload(
            v = ProfileTransferPayload.VERSION,
            kind = ProfileTransferPayload.KIND,
            exportedAt = System.currentTimeMillis(),
            sourceProfileId = ProfileManager.activeProfileId,
            persona = profile?.let {
                ProfileTransferPayload.Persona(
                    displayName = it.displayName,
                    avatarKey = it.avatarKey,
                    isKids = it.isKids,
                    maxAgeRating = it.maxAgeRating,
                    enabledIntegrations = it.enabledIntegrations.toList().sorted(),
                )
            },
            settings = settings,
            watchlist = watchlist,
            notes = notes,
        )
    }

    private fun snapshotSettings(
        appContext: Context,
        providers: List<String>,
    ): ProfileTransferPayload.SettingsSnapshot {
        val sort = LinkedHashMap<String, String>()
        val itemOrder = LinkedHashMap<String, Map<String, List<String>>>()
        val catOrder = LinkedHashMap<String, List<String>>()
        providers.forEach { provider ->
            sort[provider] = UserPreferences.getFavoriteSortMode(provider)
            val movies = UserPreferences.getFavoriteItemOrder(provider, "movies")
            val tv = UserPreferences.getFavoriteItemOrder(provider, "tv_shows")
            if (movies.isNotEmpty() || tv.isNotEmpty()) {
                itemOrder[provider] = buildMap {
                    if (movies.isNotEmpty()) put("movies", movies)
                    if (tv.isNotEmpty()) put("tv_shows", tv)
                }
            }
            val cats = UserPreferences.getFavoriteCategoryOrder(provider)
            if (cats != listOf("movies", "tv_shows")) {
                catOrder[provider] = cats
            }
        }
        return ProfileTransferPayload.SettingsSnapshot(
            libraryScope = UserPreferences.libraryScope.key,
            selectedTheme = UserPreferences.selectedTheme,
            leanbackDensity = UserPreferences.leanbackDensity,
            showContinueWatching = UserPreferences.showContinueWatching,
            showRecentlyWatched = UserPreferences.showRecentlyWatched,
            showNextEpisodes = UserPreferences.showNextEpisodes,
            showSmartCollections = UserPreferences.showSmartCollections,
            showWatchlistWeek = UserPreferences.showWatchlistWeek,
            homeShelfBlockOrder = UserPreferences.homeShelfBlockOrder.takeIf { it.isNotBlank() },
            homeShelfPinsJson = UserPreferences.homeShelfPinsJson.takeIf { it.isNotBlank() },
            seriesFastForwardEnabled = UserPreferences.seriesFastForwardEnabled,
            spoilerShieldEnabled = UserPreferences.spoilerShieldEnabled,
            // Age ceiling stays device-local — never transfer without a parental flow.
            parentalControlMaxAge = null,
            favoriteSortByProvider = sort,
            favoriteItemOrderByProvider = itemOrder,
            favoriteCategoryOrderByProvider = catOrder,
            tasteSignalsJson = runCatching {
                com.dskja.betterstreamflix.taste.TasteGraphStore.exportSignalsJson(appContext)
            }.getOrNull(),
            tasteAffinityJson = runCatching {
                com.dskja.betterstreamflix.taste.TasteGraphStore.exportAffinityJson(appContext)
            }.getOrNull(),
            tasteQueryTokensJson = runCatching {
                com.dskja.betterstreamflix.taste.TasteGraphStore.exportTokensJson(appContext)
            }.getOrNull(),
            searchMemoriesJson = runCatching {
                com.dskja.betterstreamflix.fragments.search.SearchMemories.exportJson(appContext)
            }.getOrNull(),
        )
    }

    private fun Movie.toItem() = ProfileTransferPayload.WatchlistItem(
        provider = providerName.orEmpty(),
        mediaType = "movie",
        mediaId = id,
        title = title.take(ProfileTransferPayload.MAX_TITLE_CHARS),
        poster = poster,
        banner = banner,
        favoritedAtMillis = favoritedAtMillis,
    )

    private fun TvShow.toItem() = ProfileTransferPayload.WatchlistItem(
        provider = providerName.orEmpty(),
        mediaType = "tv_show",
        mediaId = id,
        title = title.take(ProfileTransferPayload.MAX_TITLE_CHARS),
        poster = poster,
        banner = banner,
        favoritedAtMillis = favoritedAtMillis,
    )
}
