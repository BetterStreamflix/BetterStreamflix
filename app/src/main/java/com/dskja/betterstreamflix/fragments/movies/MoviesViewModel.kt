package com.dskja.betterstreamflix.fragments.movies

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.providers.ProviderSmoke
import com.dskja.betterstreamflix.utils.CatalogSort
import com.dskja.betterstreamflix.utils.ParentalControlUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.ProviderChangeNotifier
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

class MoviesViewModel(
    @Suppress("UNUSED_PARAMETER") database: AppDatabase? = null,
) : ViewModel() {

    private fun liveDb(): AppDatabase =
        AppDatabase.getInstance(com.dskja.betterstreamflix.BetterStreamflixApp.instance.applicationContext)

    private val _state = MutableStateFlow<State>(State.Loading)
    private var moviesJob: Job? = null
    private var loadMoreJob: Job? = null

    init {
        // Listen for provider changes and reload data
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getMovies()
            }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    if (state.movies.isEmpty()) {
                        emit(emptyList())
                    } else {
                        val db = runCatching { liveDb() }.getOrNull()
                        if (db == null || !db.isOpen) {
                            emit(emptyList())
                        } else {
                            emitAll(
                                db.movieDao().getByIds(state.movies.map { it.id }).catch { e ->
                                    if (e is CancellationException) throw e
                                    Log.w("MoviesViewModel", "moviesDb flow failed after provider switch", e)
                                    emit(emptyList())
                                }
                            )
                        }
                    }
                }
                else -> emit(emptyList<Movie>())
            }
        },
    ) { state, moviesDb ->
        when (state) {
            is State.SuccessLoading -> {
                val moviesById = moviesDb.associateBy { it.id }
                State.SuccessLoading(
                    movies = state.movies.map { movie ->
                        moviesById[movie.id]
                            ?.takeIf { !movie.isSame(it) }
                            ?.let { movie.copy().merge(it) }
                            ?: movie
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
        data class SuccessLoading(val movies: List<Movie>, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getMovies()
    }


    fun getMovies() {
        moviesJob?.cancel()
        loadMoreJob?.cancel()
        moviesJob = viewModelScope.launch(Dispatchers.IO) {
            _state.emit(State.Loading)

            try {
                val provider = UserPreferences.currentProvider
                    ?: throw Exception("No provider selected")
                val providerName = provider.name
                val movies = ParentalControlUtils.filterItems(
                    ProviderSmoke.withProviderTimeout(
                        timeoutMs = ProviderSmoke.CATALOG_TIMEOUT_MS,
                        label = "getMovies(${provider.name})",
                    ) {
                        provider.getMovies()
                    }
                ).filterIsInstance<Movie>().let { CatalogSort.movies(it) }

                if (providerName != UserPreferences.currentProvider?.name) return@launch
                page = 1

                _state.emit(State.SuccessLoading(movies, movies.isNotEmpty()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MoviesViewModel", "getMovies: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }

    fun loadMoreMovies() {
        val currentState = _state.value
        if (currentState !is State.SuccessLoading) return
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch(Dispatchers.IO) {
            _state.emit(State.LoadingMore)

            try {
                val provider = UserPreferences.currentProvider
                    ?: throw Exception("No provider selected")
                val providerName = provider.name
                val movies = ParentalControlUtils.filterItems(
                    ProviderSmoke.withProviderTimeout(
                        timeoutMs = ProviderSmoke.CATALOG_TIMEOUT_MS,
                        label = "getMovies(${provider.name}, page=${page + 1})",
                    ) {
                        provider.getMovies(page + 1)
                    }
                ).filterIsInstance<Movie>().let { CatalogSort.movies(it) }

                if (providerName != UserPreferences.currentProvider?.name) return@launch
                page += 1

                _state.emit(
                    State.SuccessLoading(
                        movies = currentState.movies + movies,
                        hasMore = movies.isNotEmpty(),
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MoviesViewModel", "loadMoreMovies: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }
}
