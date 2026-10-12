package com.dskja.betterstreamflix.profiletransfer

import android.content.Context
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.sync.CloudSyncScheduler
import com.dskja.betterstreamflix.sync.FavoriteMutations
import com.dskja.betterstreamflix.ui.UserDataNotifier
import com.dskja.betterstreamflix.utils.EpisodeNotesStore
import com.dskja.betterstreamflix.utils.ParentalControlUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Applies a decoded [ProfileTransferPayload] to the active profile.
 *
 * Settings apply is overwrite-by-design (EI-011): no three-way merge — last import wins
 * for toggles, theme, and shelf-order keys present in the snapshot.
 */
object ProfileTransferImporter {

    data class Result(
        val settingsApplied: Boolean,
        val watchlistImported: Int,
        val watchlistAlready: Int,
        val watchlistSkipped: Int,
        val watchlistAgeSkipped: Int,
        val notesWritten: Int,
        val personaApplied: Boolean,
    )

    data class Options(
        val applySettings: Boolean = true,
        val applyWatchlist: Boolean = true,
        val applyNotes: Boolean = true,
        val applyPersona: Boolean = false,
        val applyKidsFlag: Boolean = false,
    )

    suspend fun importPayload(
        context: Context,
        payload: ProfileTransferPayload,
        options: Options = Options(),
    ): Result = withContext(Dispatchers.IO) {
        require(
            payload.v == ProfileTransferPayload.VERSION ||
                payload.v == ProfileTransferPayload.VERSION_LEGACY,
        ) { "unsupported version" }
        require(payload.kind == ProfileTransferPayload.KIND) { "unsupported kind" }
        val app = context.applicationContext

        var settingsApplied = false
        if (options.applySettings) {
            applySettings(payload.settings)
            settingsApplied = true
        }

        var personaApplied = false
        if (options.applyPersona) {
            personaApplied = applyPersona(payload.persona, options.applyKidsFlag)
        }

        var imported = 0
        var already = 0
        var skipped = 0
        var ageSkipped = 0
        if (options.applyWatchlist) {
            val wl = importWatchlist(app, payload.watchlist)
            imported = wl.imported
            already = wl.already
            skipped = wl.skipped
            ageSkipped = wl.ageSkipped
        }

        var notesWritten = 0
        if (options.applyNotes) {
            notesWritten = importNotes(app, payload.notes)
        }

        if (settingsApplied || imported > 0 || notesWritten > 0 || personaApplied) {
            UserDataNotifier.notifyChanged()
            CloudSyncScheduler.enqueue(app)
        }

        Result(
            settingsApplied = settingsApplied,
            watchlistImported = imported,
            watchlistAlready = already,
            watchlistSkipped = skipped,
            watchlistAgeSkipped = ageSkipped,
            notesWritten = notesWritten,
            personaApplied = personaApplied,
        )
    }

    private fun applySettings(s: ProfileTransferPayload.SettingsSnapshot) {
        s.libraryScope?.let {
            UserPreferences.libraryScope = UserPreferences.LibraryScope.fromKey(it)
        }
        s.selectedTheme?.takeIf { it.isNotBlank() }?.let { UserPreferences.selectedTheme = it }
        s.leanbackDensity?.takeIf { it.isNotBlank() }?.let { UserPreferences.leanbackDensity = it }
        s.showContinueWatching?.let { UserPreferences.showContinueWatching = it }
        s.showRecentlyWatched?.let { UserPreferences.showRecentlyWatched = it }
        s.showNextEpisodes?.let { UserPreferences.showNextEpisodes = it }
        s.showSmartCollections?.let { UserPreferences.showSmartCollections = it }
        s.showWatchlistWeek?.let { UserPreferences.showWatchlistWeek = it }
        s.homeShelfBlockOrder?.let { UserPreferences.homeShelfBlockOrder = it }
        s.homeShelfPinsJson?.let { UserPreferences.homeShelfPinsJson = it }
        s.seriesFastForwardEnabled?.let { UserPreferences.seriesFastForwardEnabled = it }
        s.spoilerShieldEnabled?.let { UserPreferences.spoilerShieldEnabled = it }
        // Age ceiling only — never PINs / lockout.
        if (s.parentalControlMaxAge != null) {
            UserPreferences.parentalControlMaxAge = s.parentalControlMaxAge
        }
        runCatching {
            val app = com.dskja.betterstreamflix.BetterStreamflixApp.instance.applicationContext
            if (s.tasteSignalsJson != null ||
                s.tasteAffinityJson != null ||
                s.tasteQueryTokensJson != null
            ) {
                com.dskja.betterstreamflix.taste.TasteGraphStore.importBundle(
                    app,
                    s.tasteSignalsJson,
                    s.tasteAffinityJson,
                    s.tasteQueryTokensJson,
                )
                com.dskja.betterstreamflix.taste.TasteGraph.invalidate()
            }
            s.searchMemoriesJson?.let {
                com.dskja.betterstreamflix.fragments.search.SearchMemories.importJson(app, it)
            }
        }
        s.favoriteSortByProvider.forEach { (provider, mode) ->
            if (provider.isNotBlank() && mode.isNotBlank()) {
                UserPreferences.setFavoriteSortMode(provider, mode)
            }
        }
        s.favoriteCategoryOrderByProvider.forEach { (provider, order) ->
            if (provider.isNotBlank() && order.isNotEmpty()) {
                UserPreferences.setFavoriteCategoryOrder(provider, order)
            }
        }
        s.favoriteItemOrderByProvider.forEach { (provider, sections) ->
            if (provider.isBlank()) return@forEach
            sections.forEach { (section, order) ->
                if (order.isNotEmpty()) {
                    UserPreferences.setFavoriteItemOrder(provider, section, order)
                }
            }
        }
    }

    private fun applyPersona(
        persona: ProfileTransferPayload.Persona?,
        applyKidsFlag: Boolean,
    ): Boolean {
        persona ?: return false
        val id = ProfileManager.activeProfileId
        var changed = false
        if (persona.displayName.isNotBlank()) {
            changed = ProfileManager.rename(id, persona.displayName) || changed
        }
        if (persona.avatarKey.isNotBlank()) {
            runCatching {
                changed = ProfileManager.updateAvatar(id, persona.avatarKey) || changed
            }
        }
        if (persona.enabledIntegrations.isNotEmpty()) {
            changed = ProfileManager.setEnabledIntegrations(
                id,
                persona.enabledIntegrations.toSet(),
            ) || changed
        }
        if (applyKidsFlag) {
            changed = ProfileManager.updateKids(id, persona.isKids) || changed
            if (persona.isKids && persona.maxAgeRating != null) {
                changed = ProfileManager.updateMaxAgeRating(id, persona.maxAgeRating) || changed
            }
        } else if (persona.maxAgeRating != null && ProfileManager.activeProfile()?.isKids == true) {
            changed = ProfileManager.updateMaxAgeRating(id, persona.maxAgeRating) || changed
        }
        return changed
    }

    private data class WlCounts(
        val imported: Int,
        val already: Int,
        val skipped: Int,
        val ageSkipped: Int,
    )

    private suspend fun importWatchlist(
        context: Context,
        items: List<ProfileTransferPayload.WatchlistItem>,
    ): WlCounts {
        var imported = 0
        var already = 0
        var skipped = 0
        var ageSkipped = 0
        val now = System.currentTimeMillis()
        for (item in items) {
            val provider = Provider.findByName(item.provider)
            if (provider == null || item.mediaId.isBlank()) {
                skipped++
                continue
            }
            val ownsCurrent = UserPreferences.currentProvider?.name == provider.name
            val db = if (ownsCurrent) {
                AppDatabase.getInstanceOrNull(context) ?: run {
                    skipped++
                    continue
                }
            } else {
                AppDatabase.getInstanceForProviderOrNull(provider.name, context) ?: run {
                    skipped++
                    continue
                }
            }
            try {
                when (item.mediaType) {
                    "movie" -> {
                        val existing = db.movieDao().getById(item.mediaId)
                        if (existing?.isFavorite == true) {
                            already++
                            continue
                        }
                        val movie = (existing ?: Movie(
                            id = item.mediaId,
                            title = item.title.ifBlank { item.mediaId },
                            poster = item.poster,
                            banner = item.banner,
                        )).apply {
                            title = title.ifBlank { item.title.ifBlank { item.mediaId } }
                            poster = poster ?: item.poster
                            banner = banner ?: item.banner
                            providerName = provider.name
                            isFavorite = true
                            favoritedAtMillis = item.favoritedAtMillis ?: now
                        }
                        if (!ParentalControlUtils.isAllowed(movie)) {
                            ageSkipped++
                            continue
                        }
                        FavoriteMutations.setMovieFavorite(
                            context, provider, movie, favorite = true, database = db,
                        )
                        imported++
                    }
                    "tv_show" -> {
                        val existing = db.tvShowDao().getById(item.mediaId)
                        if (existing?.isFavorite == true) {
                            already++
                            continue
                        }
                        val show = (existing ?: TvShow(
                            id = item.mediaId,
                            title = item.title.ifBlank { item.mediaId },
                            poster = item.poster,
                            banner = item.banner,
                        )).apply {
                            title = title.ifBlank { item.title.ifBlank { item.mediaId } }
                            poster = poster ?: item.poster
                            banner = banner ?: item.banner
                            providerName = provider.name
                            isFavorite = true
                            favoritedAtMillis = item.favoritedAtMillis ?: now
                        }
                        if (!ParentalControlUtils.isAllowed(show)) {
                            ageSkipped++
                            continue
                        }
                        FavoriteMutations.setTvShowFavorite(
                            context, provider, show, favorite = true, database = db,
                        )
                        imported++
                    }
                    else -> skipped++
                }
            } finally {
                if (!ownsCurrent) runCatching { db.close() }
            }
        }
        return WlCounts(imported, already, skipped, ageSkipped)
    }

    private fun importNotes(
        context: Context,
        notes: List<ProfileTransferPayload.NoteItem>,
    ): Int {
        var written = 0
        for (note in notes) {
            val id = note.episodeId.trim()
            val text = EpisodeNotesStore.sanitize(note.text)
            if (id.isBlank() || text.isBlank()) continue
            EpisodeNotesStore.set(context, id, text)
            written++
        }
        return written
    }
}
