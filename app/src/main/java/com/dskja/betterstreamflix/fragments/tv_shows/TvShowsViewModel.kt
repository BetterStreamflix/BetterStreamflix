package com.dskja.betterstreamflix.fragments.tv_shows

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.CatalogSort
import com.dskja.betterstreamflix.utils.ParentalControlUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.ProviderChangeNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

class TvShowsViewModel(
    @Suppress("UNUSED_PARAMETER") database: AppDatabase? = null,
) : ViewModel() {

    private fun liveDb(): AppDatabase =
        AppDatabase.getInstance(com.dskja.betterstreamflix.BetterStreamflixApp.instance.applicationContext)

    private val _state = MutableStateFlow<State>(State.Loading)
    
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
                        val db = runCatching { liveDb() }.getOrNull()
                        if (db == null) emit(emptyList())
                        else emitAll(db.tvShowDao().getByIds(state.tvShows.map { it.id }))
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


    fun getTvShows() = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        try {
            val provider = UserPreferences.currentProvider
                ?: throw Exception("No provider selected")
            val tvShows = ParentalControlUtils.filterItems(
                provider.getTvShows()
            ).filterIsInstance<TvShow>().let { CatalogSort.tvShows(it) }

            page = 1

            _state.emit(State.SuccessLoading(tvShows, tvShows.isNotEmpty()))
        } catch (e: Exception) {
            Log.e("TvShowsViewModel", "getTvShows: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    fun loadMoreTvShows() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessLoading) {
            _state.emit(State.LoadingMore)

            try {
                val provider = UserPreferences.currentProvider
                    ?: throw Exception("No provider selected")
                val tvShows = ParentalControlUtils.filterItems(
                    provider.getTvShows(page + 1)
                ).filterIsInstance<TvShow>().let { CatalogSort.tvShows(it) }

                page += 1

                _state.emit(
                    State.SuccessLoading(
                        tvShows = currentState.tvShows + tvShows,
                        hasMore = tvShows.isNotEmpty(),
                    )
                )
            } catch (e: Exception) {
                Log.e("TvShowsViewModel", "loadMoreTvShows: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }
}
