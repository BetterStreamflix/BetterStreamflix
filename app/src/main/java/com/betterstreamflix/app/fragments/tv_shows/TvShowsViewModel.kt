package com.betterstreamflix.app.fragments.tv_shows

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.providers.ProviderSmoke
import com.betterstreamflix.app.utils.CatalogSort
import com.betterstreamflix.app.utils.ParentalControlUtils
import com.betterstreamflix.app.utils.UserPreferences
import com.betterstreamflix.app.utils.ProviderChangeNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

class TvShowsViewModel(
    @Suppress("UNUSED_PARAMETER") database: AppDatabase? = null,
) : ViewModel() {

    private fun liveDb(): AppDatabase? =
        AppDatabase.getInstanceOrNull(com.betterstreamflix.app.BetterStreamflixApp.instance.applicationContext)

    private val _state = MutableStateFlow<State>(State.Loading)
    private var tvShowsJob: Job? = null
    private var loadMoreJob: Job? = null

    init {
        // Listen for provider changes and reload data
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getTvShows()
            }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    if (state.tvShows.isEmpty()) {
                        emit(emptyList())
                    } else {
                        val db = liveDb()
                        if (db == null || !db.isOpen) {
                            emit(emptyList())
                        } else {
                            emitAll(
                                db.tvShowDao().getByIds(state.tvShows.map { it.id }).catch { e ->
                                    if (e is CancellationException) throw e
                                    Log.w("TvShowsViewModel", "tvShowsDb flow failed after provider switch", e)
                                    emit(emptyList())
                                }
                            )
                        }
                    }
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, tvShowsDb ->
        when (state) {
            is State.SuccessLoading -> {
                val tvShowsById = tvShowsDb.associateBy { it.id }
                State.SuccessLoading(
                    tvShows = state.tvShows.map { tvShow ->
                        tvShowsById[tvShow.id]
                            ?.takeIf { !tvShow.isSame(it) }
                            ?.let { tvShow.copy().merge(it) }
                            ?: tvShow
                    },
                    hasMore = state.hasMore
                )

            }
            else -> state
        }
    }.flowOn(Dispatchers.IO)

    private var page = 1

    sealed class State {
        data object Loading : State()
        data object LoadingMore : State()
        data class SuccessLoading(val tvShows: List<TvShow>, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getTvShows()
    }


    fun getTvShows() {
        tvShowsJob?.cancel()
        loadMoreJob?.cancel()
        tvShowsJob = viewModelScope.launch(Dispatchers.IO) {
            _state.emit(State.Loading)

            try {
                val provider = UserPreferences.currentProvider
                    ?: throw Exception(com.betterstreamflix.app.BetterStreamflixApp.instance.getString(com.betterstreamflix.app.R.string.error_no_provider_selected))
                val providerName = provider.name
                val tvShows = ParentalControlUtils.filterItems(
                    ProviderSmoke.withProviderTimeout(
                        timeoutMs = ProviderSmoke.CATALOG_TIMEOUT_MS,
                        label = "getTvShows(${provider.name})",
                    ) {
                        provider.getTvShows()
                    }
                ).filterIsInstance<TvShow>().let { CatalogSort.tvShows(it) }

                if (providerName != UserPreferences.currentProvider?.name) {
                    // Sticky playback switched providers mid-fetch — never leave Loading stuck.
                    viewModelScope.launch { getTvShows() }
                    return@launch
                }
                page = 1

                _state.emit(State.SuccessLoading(tvShows, tvShows.isNotEmpty()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TvShowsViewModel", "getTvShows: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }

    fun loadMoreTvShows() {
        val currentState = _state.value
        if (currentState !is State.SuccessLoading) return
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch(Dispatchers.IO) {
            _state.emit(State.LoadingMore)

            try {
                val provider = UserPreferences.currentProvider
                    ?: throw Exception(com.betterstreamflix.app.BetterStreamflixApp.instance.getString(com.betterstreamflix.app.R.string.error_no_provider_selected))
                val providerName = provider.name
                val tvShows = ParentalControlUtils.filterItems(
                    ProviderSmoke.withProviderTimeout(
                        timeoutMs = ProviderSmoke.CATALOG_TIMEOUT_MS,
                        label = "getTvShows(${provider.name}, page=${page + 1})",
                    ) {
                        provider.getTvShows(page + 1)
                    }
                ).filterIsInstance<TvShow>().let { CatalogSort.tvShows(it) }

                if (providerName != UserPreferences.currentProvider?.name) {
                    _state.emit(currentState)
                    return@launch
                }
                page += 1

                _state.emit(
                    State.SuccessLoading(
                        tvShows = currentState.tvShows + tvShows,
                        hasMore = tvShows.isNotEmpty(),
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TvShowsViewModel", "loadMoreTvShows: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }
}
