package com.dskja.betterstreamflix.fragments.people

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.People
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

class PeopleViewModel(private val id: String, database: AppDatabase) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val movies = state.people.filmography
                        .filterIsInstance<Movie>()
                    if (movies.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.movieDao().getByIds(movies.map { it.id }))
                    }
                }
                else -> emit(emptyList<Movie>())
            }
        },
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val tvShows = state.people.filmography
                        .filterIsInstance<TvShow>()
                    if (tvShows.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.tvShowDao().getByIds(tvShows.map { it.id }))
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
                    people = state.people.copy(
                        filmography = state.people.filmography.map { item ->
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
        data class SuccessLoading(val people: People, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getPeople(id)
    }


    fun getPeople(id: String) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        try {
            val provider = UserPreferences.currentProvider
                ?: throw Exception("No provider selected")
            val people = try {
                provider.getPeople(id)
            } catch (primary: Exception) {
                // TMDb numeric person ids (from enrichment) 404 on HTML providers —
                // fall back to TMDb person details when the id looks numeric.
                // Skip filmography: TMDb show ids are not valid on HTML providers.
                if (id.all { it.isDigit() }) {
                    Log.w("PeopleViewModel", "Provider getPeople failed for $id, trying TMDb", primary)
                    com.dskja.betterstreamflix.utils.TMDb3.People.details(
                        personId = id.toInt(),
                        language = provider.language,
                    ).let { person ->
                        People(
                            id = person.id.toString(),
                            name = person.name,
                            image = person.profilePath?.let {
                                "https://image.tmdb.org/t/p/w500/$it"
                            },
                            biography = person.biography,
                            placeOfBirth = person.placeOfBirth,
                            birthday = person.birthday,
                            deathday = person.deathday,
                            filmography = emptyList(),
                        )
                    }
                } else {
                    throw primary
                }
            }

            page = 1

            _state.emit(
                State.SuccessLoading(
                    people = people,
                    hasMore = people.filmography.isNotEmpty(),
                ),
            )
        } catch (e: Exception) {
            Log.e("PeopleViewModel", "getPeople: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    fun loadMorePeopleFilmography() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessLoading) {
            if (!currentState.hasMore) return@launch
            _state.emit(State.LoadingMore)

            try {
                val provider = UserPreferences.currentProvider
                    ?: throw Exception("No provider selected")
                val people = provider.getPeople(id, page + 1)

                page += 1

                _state.emit(
                    State.SuccessLoading(
                        people = currentState.people.copy(
                            filmography = currentState.people.filmography + people.filmography
                        ),
                        hasMore = people.filmography.isNotEmpty(),
                    )
                )
            } catch (e: Exception) {
                Log.e("PeopleViewModel", "loadMorePeopleFilmography: ", e)
                // Keep the already-loaded person page; just stop pagination.
                _state.emit(currentState.copy(hasMore = false))
            }
        }
    }
}
