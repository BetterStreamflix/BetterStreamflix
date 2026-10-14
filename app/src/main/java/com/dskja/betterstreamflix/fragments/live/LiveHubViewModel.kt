package com.dskja.betterstreamflix.fragments.live

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskja.betterstreamflix.BetterStreamflixApp
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.notifications.LiveReminderScheduler
import com.dskja.betterstreamflix.utils.LiveHubCatalog
import com.dskja.betterstreamflix.utils.ParentalControlUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LiveHubViewModel : ViewModel() {

    private val _state = MutableSharedFlow<State>(replay = 1)
    val state = _state.asSharedFlow()

    private var loadJob: Job? = null
    /** Separate from [loadJob] so resume Favorites refresh cannot cancel an in-flight load. */
    private var favoritesJob: Job? = null
    private var lastLabels: Labels? = null
    /** Last successful hub rows (pre-favorites) for cheap Favorites-shelf refresh. */
    private var lastHubShows: List<TvShow> = emptyList()
    private var lastWarning: String? = null

    companion object {
        private const val TAG = "LiveHubViewModel"
    }

    private data class Labels(
        val sport: String,
        val news: String,
        val channels: String,
        val favorites: String,
    )

    sealed class State {
        data object Loading : State()
        /** Kids / parental controls — no channels, no playback. */
        data object Restricted : State()
        data class Success(
            val categories: List<Category>,
            val warning: String? = null,
        ) : State()
        /** Catalog loaded cleanly but no live rows — show empty chrome, not an error toast. */
        data class Empty(val warning: String? = null) : State()
        data class Failed(val error: Exception) : State()
    }

    fun load(
        sportLabel: String,
        newsLabel: String,
        channelsLabel: String,
        favoritesLabel: String,
        /** When true, keep current Success content on screen (full re-fetch). */
        silent: Boolean = false,
    ) {
        lastLabels = Labels(sportLabel, newsLabel, channelsLabel, favoritesLabel)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (LiveHubCatalog.isAgeGated()) {
                _state.emit(State.Restricted)
                return@launch
            }
            if (!silent) {
                _state.emit(State.Loading)
            }
            try {
                val includeQuarantined = UserPreferences.showQuarantinedProviders
                val ctx = runCatching { BetterStreamflixApp.instance }.getOrNull()
                val result = LiveHubCatalog.load(
                    sportLabel = sportLabel,
                    newsLabel = newsLabel,
                    channelsLabel = channelsLabel,
                    favoritesLabel = favoritesLabel,
                    includeQuarantined = includeQuarantined,
                    context = ctx,
                )
                val categories = ParentalControlUtils.filterCategories(result.categories)
                lastHubShows = categories
                    .filter { it.identityKey != LiveHubCatalog.SECTION_FAVORITES }
                    .flatMap { it.list.filterIsInstance<TvShow>() }
                lastWarning = result.warnings.takeIf { it.isNotEmpty() }
                    ?.joinToString(separator = " · ")
                if (categories.isEmpty()) {
                    // Sources failed with warnings → Failed (retryable), not silent Empty.
                    if (!lastWarning.isNullOrBlank()) {
                        _state.emit(State.Failed(Exception(lastWarning)))
                    } else {
                        _state.emit(State.Empty(warning = null))
                    }
                    return@launch
                }
                _state.emit(State.Success(categories, lastWarning))
                scheduleLiveReminders(categories)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!silent) {
                    _state.emit(State.Failed(e))
                } else {
                    Log.w(TAG, "Silent Live Hub load failed: ${e.message}", e)
                }
            }
        }
    }

    /** Re-resolve Favorites shelf from library without re-fetching live sources. */
    fun refreshFavoritesShelf() {
        val labels = lastLabels ?: return
        if (lastHubShows.isEmpty()) return
        favoritesJob?.cancel()
        favoritesJob = viewModelScope.launch {
            if (LiveHubCatalog.isAgeGated()) {
                _state.emit(State.Restricted)
                return@launch
            }
            try {
                val ctx = runCatching { BetterStreamflixApp.instance }.getOrNull()
                val favorites = withContext(Dispatchers.IO) {
                    LiveHubCatalog.resolveFavoriteChannels(ctx, lastHubShows)
                }
                val categories = ParentalControlUtils.filterCategories(
                    LiveHubCatalog.buildCategories(
                        shows = lastHubShows,
                        sportLabel = labels.sport,
                        newsLabel = labels.news,
                        channelsLabel = labels.channels,
                        favorites = favorites,
                        favoritesLabel = labels.favorites,
                    ),
                )
                if (categories.isEmpty()) {
                    _state.emit(State.Empty(warning = lastWarning))
                } else {
                    _state.emit(State.Success(categories, lastWarning))
                    scheduleLiveReminders(categories)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Favorites shelf refresh failed: ${e.message}", e)
            }
        }
    }

    /** Pre-kickoff OneTime schedules for Favorites shelf rows (opt-in). */
    private fun scheduleLiveReminders(categories: List<Category>) {
        if (!UserPreferences.notifyLiveFavoriteStarting) return
        val ctx = runCatching { BetterStreamflixApp.instance }.getOrNull() ?: return
        val favorites = categories
            .firstOrNull { it.identityKey == LiveHubCatalog.SECTION_FAVORITES }
            ?.list
            ?.filterIsInstance<TvShow>()
            .orEmpty()
        LiveReminderScheduler.syncFromFavorites(ctx, favorites)
    }
}
