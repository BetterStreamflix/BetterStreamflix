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
import com.dskja.betterstreamflix.providers.ProviderSmoke
import com.dskja.betterstreamflix.utils.ParentalControlUtils
import com.dskja.betterstreamflix.utils.SearchSort
import com.dskja.betterstreamflix.utils.SearchSortMode
import com.dskja.betterstreamflix.utils.UserPreferences
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

    private fun roomLookupIds(ids: List<String>): List<String> =
        ids.filter { it.isNotBlank() }.distinct().take(SearchResultGuard.ROOM_LOOKUP_LIMIT)

    private val _state = MutableStateFlow<State>(State.Searching)
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessSearching -> {
                    val movies = state.results
                        .filterIsInstance<Movie>()
                    val movieIds = roomLookupIds(movies.map { it.id })
                    if (movieIds.isEmpty()) {
                        emit(emptyList())
                    } else {
                        val db = runCatching { liveDb() }.getOrNull()
                        if (db == null || !db.isOpen) emit(emptyList())
                        else emitAll(
                            db.movieDao().getByIds(movieIds).catch { e ->
                                if (e is CancellationException) throw e
                                Log.w("SearchViewModel", "moviesDb flow failed after provider switch", e)
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
                is State.SuccessSearching -> {
                    val tvShows = state.results
                        .filterIsInstance<TvShow>()
                    val showIds = roomLookupIds(tvShows.map { it.id })
                    if (showIds.isEmpty()) {
                        emit(emptyList())
                    } else {
                        val db = runCatching { liveDb() }.getOrNull()
                        if (db == null || !db.isOpen) emit(emptyList())
                        else emitAll(
                            db.tvShowDao().getByIds(showIds).catch { e ->
                                if (e is CancellationException) throw e
                                Log.w("SearchViewModel", "tvShowsDb flow failed after provider switch", e)
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
            is State.SuccessSearching -> {
                val moviesById = moviesDb.associateBy { it.id }
                val tvShowsById = tvShowsDb.associateBy { it.id }

                State.SuccessSearching(
                    results = state.results.map { item ->
                        when (item) {
                            is Movie -> {
                                val merged = moviesById[item.id]
                                    ?.takeIf { db ->
                                        !item.isSame(db) ||
                                            (item.logo.isNullOrBlank() && !db.logo.isNullOrBlank())
                                    }
                                    ?.let { item.copy().merge(it) }
                                    ?: item
                                // Preserve adapter type across Room merge copies (TV/mobile grid stamps).
                                val sourceType = runCatching { item.itemType }.getOrNull()
                                if (sourceType != null && runCatching { merged.itemType }.isFailure) {
                                    merged.itemType = sourceType
                                }
                                merged
                            }
                            is TvShow -> {
                                val merged = tvShowsById[item.id]
                                    ?.takeIf { db ->
                                        !item.isSame(db) ||
                                            (item.logo.isNullOrBlank() && !db.logo.isNullOrBlank())
                                    }
                                    ?.let { item.copy().merge(it) }
                                    ?: item
                                val sourceType = runCatching { item.itemType }.getOrNull()
                                if (sourceType != null && runCatching { merged.itemType }.isFailure) {
                                    merged.itemType = sourceType
                                }
                                merged
                            }
                            else -> item
                        }
                    },
                    hasMore = state.hasMore
                )
            }
            else -> state
        }
    }.catch { t ->
        if (t is CancellationException) throw t
        Log.e("SearchViewModel", "state flow failed", t)
        emit(
            State.FailedSearching(
                (t as? Exception) ?: Exception(t.message ?: t.javaClass.simpleName, t),
            ),
        )
    }.flowOn(Dispatchers.IO)

    var query = ""
    private var page = 1

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var globalSearchJob: Job? = null

    /** Unordered / unfiltered provider+plugin hits for local search. */
    private var rawLocalResults: List<AppAdapter.Item> = emptyList()
    private var rawLocalHasMore: Boolean = false

    /** Global search rows with Success payloads still unordered / unfiltered. */
    private var rawGlobalResults: List<ProviderResult> = emptyList()

    private val providerStateComparator = compareBy<ProviderResult> { providerResult ->
        when (val state = providerResult.state) {
            is ProviderResult.State.Success -> if (state.results.isNotEmpty()) 1 else 3
            is ProviderResult.State.Loading -> 2
            is ProviderResult.State.Error -> 4
        }
    }

    init {
        search(query)
    }

    fun currentSortMode(): SearchSortMode = UserPreferences.searchSortMode

    fun currentYearFilter(): Int? = UserPreferences.searchYearFilter

    /** Years present in the current raw result set (local or global). */
    fun yearsInCurrentResults(): List<Int> {
        val items = when (val s = _state.value) {
            is State.SuccessSearching -> rawLocalResults
            is State.SuccessGlobalSearching -> rawGlobalResults.flatMap { pr ->
                (pr.state as? ProviderResult.State.Success)?.results.orEmpty()
            }
            else -> rawLocalResults
        }
        return SearchSort.availableYears(items)
    }

    fun setSortMode(mode: SearchSortMode) {
        if (UserPreferences.searchSortMode == mode) {
            reapplyOrdering()
            return
        }
        UserPreferences.searchSortMode = mode
        reapplyOrdering()
    }

    fun setYearFilter(year: Int?) {
        if (UserPreferences.searchYearFilter == year) {
            reapplyOrdering()
            return
        }
        UserPreferences.searchYearFilter = year
        reapplyOrdering()
    }

    fun reapplyOrdering() {
        viewModelScope.launch(Dispatchers.IO) {
            when (_state.value) {
                is State.SuccessSearching, is State.SearchingMore -> {
                    emitLocalSuccess()
                }
                is State.SuccessGlobalSearching -> {
                    _state.emit(State.SuccessGlobalSearching(orderGlobal(rawGlobalResults)))
                }
                else -> Unit
            }
        }
    }

    private fun orderLocal(items: List<AppAdapter.Item>): List<AppAdapter.Item> =
        SearchSort.items(items, UserPreferences.searchSortMode, UserPreferences.searchYearFilter)

    private fun orderGlobal(rows: List<ProviderResult>): List<ProviderResult> =
        rows.map { pr ->
            when (val st = pr.state) {
                is ProviderResult.State.Success -> pr.copy(
                    state = ProviderResult.State.Success(
                        SearchSort.items(
                            st.results,
                            UserPreferences.searchSortMode,
                            UserPreferences.searchYearFilter,
                        )
                    )
                )
                else -> pr
            }
        }.sortedWith(providerStateComparator)

    private suspend fun emitLocalSuccess() {
        _state.emit(
            State.SuccessSearching(
                results = orderLocal(rawLocalResults),
                hasMore = rawLocalHasMore,
            )
        )
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
                val results = SearchResultGuard.sanitize(
                    ParentalControlUtils.filterItems(
                        ProviderSmoke.withProviderTimeout(
                            timeoutMs = ProviderSmoke.SEARCH_TIMEOUT_MS,
                            label = "search(${provider.name})",
                        ) {
                            SearchResultGuard.sanitize(provider.search(query))
                        }
                    )
                )
                val addonHits = runCatching {
                    SearchResultGuard.sanitize(
                        com.dskja.betterstreamflix.platform.plugins.PluginManager
                            .collectSearchResults(provider, query, page = 1),
                    )
                }.getOrDefault(emptyList())
                val merged = (results + addonHits)
                    .distinctBy { it.searchIdentityKey() }
                    .take(SearchResultGuard.MAX_LOCAL_RESULTS)
                this@SearchViewModel.query = query
                page = 1
                rawLocalResults = merged
                rawLocalHasMore = results.isNotEmpty() &&
                    merged.size < SearchResultGuard.MAX_LOCAL_RESULTS
                rawGlobalResults = emptyList()
                emitLocalSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Errors (OOM from DE HTML/sitemap scrapes) must not kill the TV process.
                Log.e("SearchViewModel", "search: ", t)
                _state.emit(
                    State.FailedSearching(
                        (t as? Exception) ?: Exception(t.message ?: t.javaClass.simpleName, t),
                    ),
                )
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
                    val results = SearchResultGuard.sanitize(
                        ParentalControlUtils.filterItems(
                            ProviderSmoke.withProviderTimeout(
                                timeoutMs = ProviderSmoke.SEARCH_TIMEOUT_MS,
                                label = "search(${provider.name}, page=${page + 1})",
                            ) {
                                SearchResultGuard.sanitize(
                                    provider.search(requestedQuery, page + 1),
                                )
                            }
                        )
                    )
                    // Drop if the user started a newer search while we were loading.
                    if (query != requestedQuery) return@launch
                    val existingKeys = rawLocalResults
                        .asSequence()
                        .map { it.searchIdentityKey() }
                        .toHashSet()
                    val newUniqueResults = results.filterNot { it.searchIdentityKey() in existingKeys }
                    val roomLeft = (SearchResultGuard.MAX_LOCAL_RESULTS - rawLocalResults.size)
                        .coerceAtLeast(0)
                    val accepted = newUniqueResults.take(roomLeft)
                    page += 1
                    rawLocalResults = rawLocalResults + accepted
                    rawLocalHasMore = accepted.isNotEmpty() &&
                        rawLocalResults.size < SearchResultGuard.MAX_LOCAL_RESULTS
                    emitLocalSuccess()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e("SearchViewModel", "loadMore: ", t)
                    // Keep the rows already on screen. Replacing them with the error
                    // overlay throws away a successful search because one later page failed.
                    rawLocalHasMore = false
                    if (rawLocalResults.isNotEmpty()) {
                        emitLocalSuccess()
                    } else {
                        _state.emit(
                            State.FailedSearching(
                                (t as? Exception) ?: Exception(t.message ?: t.javaClass.simpleName, t),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun searchGlobal(query: String, currentLanguage: String) {
        searchJob?.cancel()
        loadMoreJob?.cancel()
        globalSearchJob?.cancel()
        globalSearchJob = viewModelScope.launch(Dispatchers.IO) {
            _state.emit(State.GlobalSearching)
            this@SearchViewModel.query = query
            page = 1
            rawLocalResults = emptyList()
            rawLocalHasMore = false

            val isCurrentProviderIptv = UserPreferences.currentProvider is IptvProvider
            val targetProviders = Provider.providers.keys
                .filter { it.language == currentLanguage && (it is IptvProvider) == isCurrentProviderIptv }
                .toList()

            if (targetProviders.isEmpty()) {
                rawGlobalResults = emptyList()
                _state.emit(State.SuccessGlobalSearching(emptyList()))
                return@launch
            }

            val initialResults = targetProviders.map { provider ->
                ProviderResult(provider, ProviderResult.State.Loading)
            }
            rawGlobalResults = initialResults
            _state.emit(State.SuccessGlobalSearching(initialResults))

            val mutableResults = initialResults.toMutableList()
            val resultsLock = Any()
            val requestedQuery = query
            // Cap fan-out (DE catalogs especially): WebView/sitemap/HTML scrapes in
            // parallel OOM-kill low-RAM Android TV sticks.
            val gate = Semaphore(permits = GLOBAL_SEARCH_CONCURRENCY)

            targetProviders.forEachIndexed { index, provider ->
                launch {
                    val next = try {
                        gate.withPermit {
                            val results = SearchResultGuard.bound(
                                ParentalControlUtils.filterItems(
                                    ProviderSmoke.withProviderTimeout(
                                        timeoutMs = ProviderSmoke.SEARCH_TIMEOUT_MS,
                                        label = "searchGlobal(${provider.name})",
                                    ) {
                                        SearchResultGuard.sanitize(provider.search(requestedQuery))
                                    }.onEach { item ->
                                        when (item) {
                                            is Movie -> item.providerName = provider.name
                                            is TvShow -> item.providerName = provider.name
                                        }
                                    },
                                ),
                                SearchResultGuard.MAX_GLOBAL_PER_PROVIDER,
                            )
                            ProviderResult(provider, ProviderResult.State.Success(results))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.e("SearchViewModel", "searchGlobal for ${provider.name}: ", t)
                        ProviderResult(
                            provider,
                            ProviderResult.State.Error(
                                (t as? Exception)
                                    ?: Exception(t.message ?: t.javaClass.simpleName, t),
                            ),
                        )
                    }

                    // Drop stale provider hits if the user started a newer search.
                    if (this@SearchViewModel.query != requestedQuery) return@launch

                    val snapshot = synchronized(resultsLock) {
                        mutableResults[index] = next
                        mutableResults.toList()
                    }
                    rawGlobalResults = snapshot
                    _state.emit(State.SuccessGlobalSearching(orderGlobal(snapshot)))
                }
            }
        }
    }

    companion object {
        /**
         * Parallel Global Search providers. Fire TV sticks die when two WebView/sitemap
         * scrapes run together; one at a time stays inside the process heap.
         */
        const val GLOBAL_SEARCH_CONCURRENCY = 1
    }
}

private fun AppAdapter.Item.searchIdentityKey(): String = when (this) {
    is Movie -> "movie:$id"
    is TvShow -> "tvshow:$id"
    else -> "${this::class.java.name}:${hashCode()}"
}
