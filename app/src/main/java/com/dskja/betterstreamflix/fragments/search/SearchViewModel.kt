package com.dskja.betterstreamflix.fragments.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.IptvProvider
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.ParentalControlUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

// DEFINICIONES DE ESTADO Y RESULTADOS (Fuera de la clase para mejor acceso)
sealed class State {
    data object Searching : State()
    data object SearchingMore : State()
    data class SuccessSearching(val results: List<AppAdapter.Item>, val hasMore: Boolean) : State()
    data class FailedSearching(val error: Exception) : State()
    data object GlobalSearching : State()
    data class SuccessGlobalSearching(val providerResults: List<ProviderResult>) : State()
}

data class ProviderResult(
    val provider: Provider,
    val state: State,
) {
    sealed class State {
        data object Loading : State()
        data class Success(val results: List<AppAdapter.Item>) : State()
        data class Error(val error: Exception) : State()
    }
}


class SearchViewModel(
    @Suppress("UNUSED_PARAMETER") database: AppDatabase? = null,
) : ViewModel() {

    private fun liveDb(): AppDatabase =
        AppDatabase.getInstance(com.dskja.betterstreamflix.BetterStreamflixApp.instance.applicationContext)

    private val _state = MutableStateFlow<State>(State.Searching)
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessSearching -> {
                    val movies = state.results
                        .filterIsInstance<Movie>()
                    if (movies.isEmpty()) {
                        emit(emptyList())
                    } else {
                        val db = runCatching { liveDb() }.getOrNull()
                        if (db == null) emit(emptyList())
                        else emitAll(db.movieDao().getByIds(movies.map { it.id }))
                    }
                }
                else -> emit(emptyList<Movie>())
            }
        },
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessSearching -> {
                    val tvShows = state.results
                        .filterIsInstance<TvShow>()
                    if (tvShows.isEmpty()) {
                        emit(emptyList())
                    } else {
                        val db = runCatching { liveDb() }.getOrNull()
                        if (db == null) emit(emptyList())
                        else emitAll(db.tvShowDao().getByIds(tvShows.map { it.id }))
                    }
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, moviesDb, tvShowsDb ->
        when (state) {
            is State.SuccessSearching -> {
                val moviesById = moviesDb.associateBy { it.id }
                val tvShowsById = tvShowsDb.associateBy { it.id }

                State.SuccessSearching(
                    results = state.results.map { item ->
                        when (item) {
                            is Movie -> moviesById[item.id]
                                ?.takeIf { !item.isSame(it) }
                                ?.let { item.copy().merge(it) }
                                ?: item
                            is TvShow -> tvShowsById[item.id]
                                ?.takeIf { !item.isSame(it) }
                                ?.let { item.copy().merge(it) }
                                ?: item
                            else -> item
                        }
                    },
                    hasMore = state.hasMore
                )
            }
            else -> state
        }
    }.flowOn(Dispatchers.IO)

    var query = ""
    private var page = 1

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var globalSearchJob: Job? = null

    init {
        search(query)
    }

    fun search(query: String) {
        // Cancel every in-flight search path so stale pages / global hits can't overwrite.
        searchJob?.cancel()
        loadMoreJob?.cancel()
        globalSearchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            _state.emit(State.Searching)

            try {
                val provider = UserPreferences.currentProvider
                    ?: run {
                        _state.emit(State.FailedSearching(Exception("No provider selected")))
                        return@launch
                    }
                val results = ParentalControlUtils.filterItems(provider.search(query))
                val addonHits = runCatching {
                    com.dskja.betterstreamflix.platform.plugins.PluginManager
                        .collectSearchResults(provider, query, page = 1)
                }.getOrDefault(emptyList())
                val merged = (results + addonHits).distinctBy { it.searchIdentityKey() }
                this@SearchViewModel.query = query
                page = 1
                _state.emit(State.SuccessSearching(merged, merged.isNotEmpty()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SearchViewModel", "search: ", e)
                _state.emit(State.FailedSearching(e))
            }
        }
    }

    fun loadMore() {
        if (loadMoreJob?.isActive == true) return
        if (searchJob?.isActive == true || globalSearchJob?.isActive == true) return
        val requestedQuery = query
        loadMoreJob = viewModelScope.launch(Dispatchers.IO) {
            val currentState = _state.value
            if (currentState is State.SuccessSearching) {
                _state.emit(State.SearchingMore)
                try {
                    val provider = UserPreferences.currentProvider
                        ?: run {
                            _state.emit(State.FailedSearching(Exception("No provider selected")))
                            return@launch
                        }
                    val results = ParentalControlUtils.filterItems(
                        provider.search(requestedQuery, page + 1)
                    )
                    // Drop if the user started a newer search while we were loading.
                    if (query != requestedQuery) return@launch
                    val existingKeys = currentState.results
                        .asSequence()
                        .map { it.searchIdentityKey() }
                        .toHashSet()
                    val newUniqueResults = results.filterNot { it.searchIdentityKey() in existingKeys }
                    page += 1
                    _state.emit(
                        State.SuccessSearching(
                            results = currentState.results + newUniqueResults,
                            hasMore = newUniqueResults.isNotEmpty(),
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("SearchViewModel", "loadMore: ", e)
                    _state.emit(State.FailedSearching(e))
                }
            }
        }
    }

    // FUNCIÓN DE BÚSQUEDA GLOBAL AÑADIDA
    fun searchGlobal(query: String, currentLanguage: String) {
        searchJob?.cancel()
        loadMoreJob?.cancel()
        globalSearchJob?.cancel()
        globalSearchJob = viewModelScope.launch(Dispatchers.IO) {
            _state.emit(State.GlobalSearching)

            val isCurrentProviderIptv = UserPreferences.currentProvider is IptvProvider
            val targetProviders = Provider.providers.keys
                .filter { it.language == currentLanguage && (it is IptvProvider) == isCurrentProviderIptv }
                .toList()

            if (targetProviders.isEmpty()) {
                _state.emit(State.SuccessGlobalSearching(emptyList()))
                return@launch
            }

            val initialResults = targetProviders.map { provider ->
                ProviderResult(provider, ProviderResult.State.Loading)
            }
            _state.emit(State.SuccessGlobalSearching(initialResults))

            val mutableResults = initialResults.toMutableList()
            val resultsLock = Any()

            val stateComparator = compareBy<ProviderResult> { providerResult ->
                when (val state = providerResult.state) {
                    is ProviderResult.State.Success -> if (state.results.isNotEmpty()) 1 else 3
                    is ProviderResult.State.Loading -> 2
                    is ProviderResult.State.Error -> 4
                }
            }

            targetProviders.forEachIndexed { index, provider ->
                launch {
                    val next = try {
                        val results = ParentalControlUtils.filterItems(provider.search(query).onEach { item ->
                            // ========= ¡AQUÍ ESTÁ LA MAGIA! =========
                            // Le ponemos el sello a cada resultado
                            when (item) {
                                is Movie -> item.providerName = provider.name
                                is TvShow -> item.providerName = provider.name
                            }
                            // =======================================
                        })
                        ProviderResult(provider, ProviderResult.State.Success(results))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("SearchViewModel", "searchGlobal for ${provider.name}: ", e)
                        ProviderResult(provider, ProviderResult.State.Error(e))
                    }

                    val snapshot = synchronized(resultsLock) {
                        mutableResults[index] = next
                        mutableResults.toList().sortedWith(stateComparator)
                    }
                    _state.emit(State.SuccessGlobalSearching(snapshot))
                }
            }
        }
    }
}

private fun AppAdapter.Item.searchIdentityKey(): String = when (this) {
    is Movie -> "movie:$id"
    is TvShow -> "tvshow:$id"
    else -> "${this::class.java.name}:${hashCode()}"
}
