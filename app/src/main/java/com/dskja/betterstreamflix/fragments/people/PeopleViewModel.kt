package com.dskja.betterstreamflix.fragments.people

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.People
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.ProviderSmoke
import com.dskja.betterstreamflix.utils.format
import com.dskja.betterstreamflix.utils.UserPreferences
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

class PeopleViewModel(private val id: String, private val database: AppDatabase) : ViewModel() {

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
                    } else if (!database.isOpen) {
                        emit(emptyList())
                    } else {
                        emitAll(
                            database.movieDao().getByIds(movies.map { it.id }).catch { e ->
                                if (e is CancellationException) throw e
                                Log.w("PeopleViewModel", "moviesDb flow failed after provider switch", e)
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
                    val tvShows = state.people.filmography
                        .filterIsInstance<TvShow>()
                    if (tvShows.isEmpty()) {
                        emit(emptyList())
                    } else if (!database.isOpen) {
                        emit(emptyList())
                    } else {
                        emitAll(
                            database.tvShowDao().getByIds(tvShows.map { it.id }).catch { e ->
                                if (e is CancellationException) throw e
                                Log.w("PeopleViewModel", "tvShowsDb flow failed after provider switch", e)
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
                val loaded = ProviderSmoke.withProviderTimeout(
                    timeoutMs = ProviderSmoke.DETAIL_TIMEOUT_MS,
                    label = "getPeople(${provider.name})",
                ) {
                    provider.getPeople(id)
                }
                val providerHadCredits = loaded.filmography.isNotEmpty()
                val enriched = enrichPeopleFromTmdb(loaded, id, provider.language)
                page = 1
                _state.emit(
                    State.SuccessLoading(
                        people = enriched,
                        hasMore = providerHadCredits,
                    ),
                )
                return@launch
            } catch (primary: Exception) {
                // TMDb numeric person ids (from enrichment) 404 on HTML providers.
                val tmdbId = id.toIntOrNull()
                if (tmdbId != null) {
                    Log.w("PeopleViewModel", "Provider getPeople failed for $id, trying TMDb", primary)
                    com.dskja.betterstreamflix.utils.TmdbUtils.getPeopleById(
                        personId = tmdbId,
                        language = provider.language,
                    ) ?: throw primary
                } else {
                    throw primary
                }
            }

            page = 1

            _state.emit(
                State.SuccessLoading(
                    people = people,
                    hasMore = false,
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
                val people = ProviderSmoke.withProviderTimeout(
                    timeoutMs = ProviderSmoke.DETAIL_TIMEOUT_MS,
                    label = "getPeople(${provider.name}, page=${page + 1})",
                ) {
                    provider.getPeople(id, page + 1)
                }

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

    private suspend fun enrichPeopleFromTmdb(
        people: People,
        id: String,
        language: String?,
    ): People {
        val needsCredits = people.filmography.isEmpty()
        val needsBio = people.biography.isNullOrBlank() ||
            people.image.isNullOrBlank() ||
            people.birthday == null ||
            people.placeOfBirth.isNullOrBlank()
        if (!needsCredits && !needsBio) return people
        val tmdbId = id.toIntOrNull() ?: return people
        val tmdb = com.dskja.betterstreamflix.utils.TmdbUtils.getPeopleById(tmdbId, language)
            ?: return people
        return people.copy(
            name = people.name.ifBlank { tmdb.name },
            // TMDb serves the full-size portrait; providers usually only have a thumbnail.
            image = tmdb.image ?: people.image,
            biography = people.biography?.takeIf { it.isNotBlank() } ?: tmdb.biography,
            placeOfBirth = people.placeOfBirth?.takeIf { it.isNotBlank() } ?: tmdb.placeOfBirth,
            birthday = (people.birthday ?: tmdb.birthday)?.format("yyyy-MM-dd"),
            deathday = (people.deathday ?: tmdb.deathday)?.format("yyyy-MM-dd"),
            knownForDepartment = people.knownForDepartment?.takeIf { it.isNotBlank() }
                ?: tmdb.knownForDepartment,
            filmography = mergeFilmography(people.filmography, tmdb.filmography),
        )
    }

    private fun mergeFilmography(
        primary: List<com.dskja.betterstreamflix.models.Show>,
        extra: List<com.dskja.betterstreamflix.models.Show>,
    ): List<com.dskja.betterstreamflix.models.Show> {
        if (extra.isEmpty()) return primary
        if (primary.isEmpty()) return extra
        val seenIds = HashSet<String>()
        val seenTitles = HashSet<String>()
        fun keyOf(show: com.dskja.betterstreamflix.models.Show): Pair<String, String> = when (show) {
            is Movie -> (show.tmdbId ?: show.id) to show.title.lowercase()
            is TvShow -> (show.tmdbId ?: show.id) to show.title.lowercase()
            else -> "" to ""
        }
        val merged = ArrayList<com.dskja.betterstreamflix.models.Show>(primary.size + extra.size)
        (primary + extra).forEach { show ->
            val (id, title) = keyOf(show)
            if (id.isBlank() && title.isBlank()) return@forEach
            if (id.isNotBlank() && !seenIds.add(id)) return@forEach
            if (title.isNotBlank() && !seenTitles.add(title)) return@forEach
            merged.add(show)
        }
        return merged
    }
}
