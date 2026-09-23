package com.dskja.betterstreamflix.fragments.genre

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.providers.ProviderSmoke
import com.dskja.betterstreamflix.providers.TmdbProvider
import com.dskja.betterstreamflix.utils.ParentalControlUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.ProviderChangeNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

class GenreViewModel(
    private val id: String,
    private val database: AppDatabase,
    private val name: String? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    
    init {
        // Listen for provider changes and reload data
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getGenre(id)
            }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val movies = state.genre.shows
                        .filterIsInstance<Movie>()
                    if (movies.isEmpty()) {
                        emit(emptyList())
                    } else if (!database.isOpen) {
                        emit(emptyList())
                    } else {
                        emitAll(
                            database.movieDao().getByIds(movies.map { it.id }).catch { e ->
                                if (e is CancellationException) throw e
                                Log.w("GenreViewModel", "moviesDb flow failed after provider switch", e)
                                emit(emptyList())
                            }
                        )
                    }
                }
                else -> emit(emptyList<Movie>())
            }
        },
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val tvShows = state.genre.shows
                        .filterIsInstance<TvShow>()
                    if (tvShows.isEmpty()) {
                        emit(emptyList())
                    } else if (!database.isOpen) {
                        emit(emptyList())
                    } else {
                        emitAll(
                            database.tvShowDao().getByIds(tvShows.map { it.id }).catch { e ->
                                if (e is CancellationException) throw e
                                Log.w("GenreViewModel", "tvShowsDb flow failed after provider switch", e)
                                emit(emptyList())
                            }
                        )
                    }
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, moviesDb, tvShowsDb ->
        when (state) {
            is State.SuccessLoading -> {
                val moviesById = moviesDb.associateBy { it.id }
                val tvShowsById = tvShowsDb.associateBy { it.id }
                State.SuccessLoading(
                    genre = state.genre.copy(
                        shows = state.genre.shows.map { item ->
                            when (item) {
                                is Movie -> moviesById[item.id]
                                    ?.takeIf { !item.isSame(it) }
                                    ?.let { item.copy().merge(it) }
                                    ?: item
                                is TvShow -> tvShowsById[item.id]
                                    ?.takeIf { !item.isSame(it) }
                                    ?.let { item.copy().merge(it) }
                                    ?: item
                            }
                        }
                    ),
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
        data class SuccessLoading(val genre: Genre, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getGenre(id)
    }


    fun getGenre(id: String) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        try {
            val provider = UserPreferences.currentProvider
                ?: throw Exception("No provider selected")

            var genre = ProviderSmoke.withProviderTimeout(
                timeoutMs = ProviderSmoke.CATALOG_TIMEOUT_MS,
                label = "getGenre(${provider.name})",
            ) {
                provider.getGenre(id)
            }.let {
                it.copy(shows = ParentalControlUtils.filterShows(it.shows))
            }

            // Providers keyed by slug often ignore a numeric TMDb id, and vice versa.
            if (genre.shows.isEmpty() && !name.isNullOrBlank() && !name.equals(id, true)) {
                genre = tryFetchGenre(provider, name) ?: genre
            }

            if (genre.shows.isEmpty() && id.toIntOrNull() != null && provider !is TmdbProvider) {
                genre = tryFetchGenre(TmdbProvider(provider.language), id) ?: genre
            }

            if (genre.name.isBlank() && !name.isNullOrBlank()) {
                genre = genre.copy(name = name)
            }

            page = 1

            _state.emit(State.SuccessLoading(genre, true))
        } catch (e: Exception) {
            Log.e("GenreViewModel", "getGenre: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    /** Fallback lookup; a failing alternative must not replace the primary result. */
    private suspend fun tryFetchGenre(provider: Provider, genreId: String): Genre? =
        runCatching {
            ProviderSmoke.withProviderTimeout(
                timeoutMs = ProviderSmoke.CATALOG_TIMEOUT_MS,
                label = "getGenre(${provider.name}, fallback)",
            ) {
                provider.getGenre(genreId)
            }.let {
                it.copy(shows = ParentalControlUtils.filterShows(it.shows))
            }
        }.getOrNull()?.takeIf { it.shows.isNotEmpty() }

    fun loadMoreGenreShows() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessLoading) {
            _state.emit(State.LoadingMore)

            try {
                val provider = UserPreferences.currentProvider
                    ?: throw Exception("No provider selected")
                val genre = ProviderSmoke.withProviderTimeout(
                    timeoutMs = ProviderSmoke.CATALOG_TIMEOUT_MS,
                    label = "getGenre(${provider.name}, page=${page + 1})",
                ) {
                    provider.getGenre(id, page + 1)
                }.let {
                    it.copy(shows = ParentalControlUtils.filterShows(it.shows))
                }

                page += 1

                _state.emit(
                    State.SuccessLoading(
                        genre = Genre(
                            id = genre.id,
                            name = genre.name,

                            shows = currentState.genre.shows + genre.shows,
                        ),
                        hasMore = genre.shows.isNotEmpty(),
                    )
                )
            } catch (e: Exception) {
                Log.e("GenreViewModel", "loadMoreGenreShows: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }
}
