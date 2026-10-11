package com.dskja.betterstreamflix.fragments.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.People
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.IptvProvider
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.providers.ProviderSmoke
import com.dskja.betterstreamflix.utils.ParentalControlUtils
import com.dskja.betterstreamflix.utils.ProviderChangeNotifier
import com.dskja.betterstreamflix.utils.SearchSort
import com.dskja.betterstreamflix.utils.SearchSortMode
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.BetterStreamflixApp
import com.dskja.betterstreamflix.taste.TasteGraph
import com.dskja.betterstreamflix.taste.TasteMood
import com.dskja.betterstreamflix.taste.TasteMoodMatcher
import com.dskja.betterstreamflix.taste.TasteRanker
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

    private fun liveDb(): AppDatabase? =
        AppDatabase.getInstanceOrNull(com.dskja.betterstreamflix.BetterStreamflixApp.instance.applicationContext)

    private fun roomLookupIds(ids: List<String>): List<String> =
        ids.filter { it.isNotBlank() }.distinct().take(SearchResultGuard.ROOM_LOOKUP_LIMIT)

    private val _state = MutableStateFlow<State>(State.Searching)
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            val movies = collectMoviesForRoom(state)
            val movieIds = roomLookupIds(movies.map { it.id })
            if (movieIds.isEmpty()) {
                emit(emptyList())
            } else {
                val db = liveDb()
                if (db == null || !db.isOpen) emit(emptyList())
                else emitAll(
                    db.movieDao().getByIds(movieIds).catch { e ->
                        if (e is CancellationException) throw e
                        Log.w("SearchViewModel", "moviesDb flow failed after provider switch", e)
                        emit(emptyList())
                    },
                )
            }
        },
        _state.transformLatest { state ->
            val tvShows = collectTvShowsForRoom(state)
            val showIds = roomLookupIds(tvShows.map { it.id })
            if (showIds.isEmpty()) {
                emit(emptyList())
            } else {
                val db = liveDb()
                if (db == null || !db.isOpen) emit(emptyList())
                else emitAll(
                    db.tvShowDao().getByIds(showIds).catch { e ->
                        if (e is CancellationException) throw e
                        Log.w("SearchViewModel", "tvShowsDb flow failed after provider switch", e)
                        emit(emptyList())
                    },
                )
            }
        },
    ) { state, moviesDb, tvShowsDb ->
        when (state) {
            is State.SuccessSearching -> {
                val merged = mergeRoomFlags(state.results, moviesDb, tvShowsDb)
                // Re-rank after Room merge so FOR_YOU sees favorite/watched flags.
                val ranked = reRankAfterRoomMerge(merged)
                State.SuccessSearching(
                    results = ranked,
                    hasMore = state.hasMore,
                )
            }
            is State.SuccessGlobalSearching -> {
                val rows = state.providerResults.map { pr ->
                    when (val st = pr.state) {
                        is ProviderResult.State.Success -> {
                            val merged = mergeRoomFlags(st.results, moviesDb, tvShowsDb)
                            pr.copy(
                                state = ProviderResult.State.Success(reRankAfterRoomMerge(merged)),
                            )
                        }
                        else -> pr
                    }
                }
                State.SuccessGlobalSearching(rows)
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

    private fun collectMoviesForRoom(state: State): List<Movie> = when (state) {
        is State.SuccessSearching -> state.results.filterIsInstance<Movie>()
        is State.SuccessGlobalSearching -> state.providerResults.flatMap { pr ->
            (pr.state as? ProviderResult.State.Success)?.results.orEmpty()
        }.filterIsInstance<Movie>()
        else -> emptyList()
    }

    private fun collectTvShowsForRoom(state: State): List<TvShow> = when (state) {
        is State.SuccessSearching -> state.results.filterIsInstance<TvShow>()
        is State.SuccessGlobalSearching -> state.providerResults.flatMap { pr ->
            (pr.state as? ProviderResult.State.Success)?.results.orEmpty()
        }.filterIsInstance<TvShow>()
        else -> emptyList()
    }

    private fun mergeRoomFlags(
        items: List<AppAdapter.Item>,
        moviesDb: List<Movie>,
        tvShowsDb: List<TvShow>,
    ): List<AppAdapter.Item> {
        val moviesById = moviesDb.associateBy { it.id }
        val tvShowsById = tvShowsDb.associateBy { it.id }
        return items.map { item ->
            when (item) {
                is Movie -> {
                    val merged = moviesById[item.id]
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
        }
    }

    private fun reRankAfterRoomMerge(items: List<AppAdapter.Item>): List<AppAdapter.Item> {
        val mode = UserPreferences.searchSortMode
        val mood = UserPreferences.searchMood
        if (mode != SearchSortMode.FOR_YOU && mood == TasteMood.ALL) return items
        val app = BetterStreamflixApp.instance.applicationContext
        val snap = TasteGraph.snapshot(app)
        return TasteRanker.apply(items, snap, mood, query)
    }

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

    /** Dedup Taste ingest for concurrent Global Search completions. */
    private var lastGlobalIngestKey: String? = null

    private val providerStateComparator = compareBy<ProviderResult> { providerResult ->
        when (val state = providerResult.state) {
            is ProviderResult.State.Success -> if (state.results.isNotEmpty()) 1 else 3
            is ProviderResult.State.Loading -> 2
            is ProviderResult.State.Error -> 4
        }
    }

    init {
        search(query)
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                // Cancel mid-search Room merges against a closing DB after provider switch.
                searchJob?.cancel()
                globalSearchJob?.cancel()
            }
        }
    }

    fun currentSortMode(): SearchSortMode = UserPreferences.searchSortMode

    fun currentYearFilter(): Int? = UserPreferences.searchYearFilter

    fun currentMood(): TasteMood = UserPreferences.searchMood

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

    fun setMood(mood: TasteMood) {
        if (UserPreferences.searchMood == mood) {
            reapplyOrdering()
            return
        }
        UserPreferences.searchMood = mood
        reapplyOrdering()
    }

    fun reapplyOrdering() {
        viewModelScope.launch(Dispatchers.IO) {
            when (_state.value) {
                is State.SuccessSearching, is State.SearchingMore -> {
                    emitLocalSuccess(recordMemory = false)
                }
                is State.SuccessGlobalSearching -> {
                    _state.emit(State.SuccessGlobalSearching(orderGlobal(rawGlobalResults)))
                }
                else -> Unit
            }
        }
    }

    /**
     * Launch a “more like this” search seeded by TMDb id when available.
     */
    fun searchMoreLike(item: AppAdapter.Item) {
        val title = when (item) {
            is Movie -> item.title
            is TvShow -> item.title
            else -> return
        }
        val tmdbId = when (item) {
            is Movie -> item.tmdbId?.takeIf { it.isNotBlank() }
            is TvShow -> item.tmdbId?.takeIf { it.isNotBlank() }
            else -> null
        }
        val isTv = item is TvShow
        searchJob?.cancel()
        loadMoreJob?.cancel()
        globalSearchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            _state.emit(State.Searching)
            try {
                val provider = UserPreferences.currentProvider
                    ?: run {
                        rememberSearchOutcome(title, SearchMemories.Outcome.FAILED, global = false)
                        _state.emit(
                            State.FailedSearching(
                                Exception(
                                    BetterStreamflixApp.instance.getString(
                                        R.string.error_no_provider_selected,
                                    ),
                                ),
                            ),
                        )
                        return@launch
                    }
                query = BetterStreamflixApp.instance.getString(
                    R.string.search_query_more_like,
                    title,
                )
                page = 1
                rawGlobalResults = emptyList()
                val language = resolveSearchLanguage(provider)
                val similar = ParentalControlUtils.filterItems(
                    SearchResultGuard.sanitize(
                        if (tmdbId != null) {
                            SearchTmdbAssist.similarToTmdb(tmdbId, isTv, language)
                        } else {
                            SearchTmdbAssist.similarTo(title, language)
                        },
                    ),
                )
                val headerLabel = BetterStreamflixApp.instance.getString(
                    R.string.search_similar_header,
                    title,
                )
                val merged = buildList {
                    if (similar.isNotEmpty()) {
                        add(
                            Category(name = headerLabel, list = emptyList()).also {
                                it.identityKey = "search-similar:$title"
                            },
                        )
                        addAll(similar)
                    }
                }
                rawLocalResults = merged
                rawLocalHasMore = false
                emitLocalSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("SearchViewModel", "searchMoreLike: ", t)
                rememberSearchOutcome(title, SearchMemories.Outcome.FAILED, global = false)
                _state.emit(
                    State.FailedSearching(
                        (t as? Exception) ?: Exception(t.message ?: t.javaClass.simpleName, t),
                    ),
                )
            }
        }
    }

    private fun orderLocal(items: List<AppAdapter.Item>): List<AppAdapter.Item> {
        val mode = UserPreferences.searchSortMode
        val year = UserPreferences.searchYearFilter
        val mood = UserPreferences.searchMood
        val isIptv = UserPreferences.currentProvider is IptvProvider
        // IPTV channels have little genre signal — skip mood / FOR_YOU ranking noise.
        val effectiveMood = if (isIptv) TasteMood.ALL else mood
        val effectiveMode = if (isIptv && mode == SearchSortMode.FOR_YOU) {
            SearchSortMode.PROVIDER_DEFAULT
        } else {
            mode
        }
        val sortMode = if (effectiveMode == SearchSortMode.FOR_YOU) {
            SearchSortMode.PROVIDER_DEFAULT
        } else {
            effectiveMode
        }
        val yearSorted = SearchSort.items(items, sortMode, year)
        val app = BetterStreamflixApp.instance.applicationContext
        val snap = TasteGraph.snapshot(app)
        val withoutNegatives = yearSorted.filter { item ->
            if (item !is Movie && item !is TvShow) return@filter true
            val tmdb = when (item) {
                is Movie -> com.dskja.betterstreamflix.taste.TasteSnapshot.tmdbKey(item.tmdbId, false)
                is TvShow -> com.dskja.betterstreamflix.taste.TasteSnapshot.tmdbKey(item.tmdbId, true)
                else -> null
            }
            !snap.isHardHidden(item.searchIdentityKey(), tmdb)
        }
        val moodFiltered = if (effectiveMood == TasteMood.ALL) {
            withoutNegatives
        } else {
            withoutNegatives.filter { TasteMoodMatcher.matches(it, effectiveMood) }
        }
        // Drop orphan Category headers when all poster children were filtered out.
        val withoutOrphanHeaders = dropOrphanCategoryHeaders(moodFiltered)
        return if (effectiveMode == SearchSortMode.FOR_YOU || effectiveMood != TasteMood.ALL) {
            TasteRanker.apply(withoutOrphanHeaders, snap, effectiveMood, query)
        } else {
            withoutOrphanHeaders
        }
    }

    private fun dropOrphanCategoryHeaders(items: List<AppAdapter.Item>): List<AppAdapter.Item> {
        if (items.none { it is Category }) return items
        return buildList {
            var i = 0
            while (i < items.size) {
                val item = items[i]
                if (item is Category) {
                    var j = i + 1
                    var hasShow = false
                    while (j < items.size && items[j] !is Category) {
                        if (items[j] is Movie || items[j] is TvShow || items[j] is People) {
                            hasShow = true
                            break
                        }
                        j++
                    }
                    if (hasShow) add(item)
                } else {
                    add(item)
                }
                i++
            }
        }
    }

    /** Visible show count after mood/negatives — used for hasMore / empty paging. */
    private fun orderedShowCount(items: List<AppAdapter.Item>): Int =
        orderLocal(items).count { it is Movie || it is TvShow }

    private fun orderGlobal(rows: List<ProviderResult>): List<ProviderResult> =
        rows.map { pr ->
            when (val st = pr.state) {
                is ProviderResult.State.Success -> pr.copy(
                    state = ProviderResult.State.Success(orderLocal(st.results)),
                )
                else -> pr
            }
        }.sortedWith(providerStateComparator)

    private suspend fun emitLocalSuccess(recordMemory: Boolean = true) {
        val ordered = orderLocal(rawLocalResults)
        if (recordMemory) {
            rememberSearchOutcome(
                query = query,
                outcome = SearchMemories.outcomeForLocalResults(
                    ordered.filter { it is Movie || it is TvShow || it is People },
                ),
                global = false,
            )
        }
        // hasMore: only continue paging when mood/negatives still leave room AND
        // the ordered set is non-empty (or mood is ALL so empty means truly empty).
        val mood = UserPreferences.searchMood
        val visibleShows = ordered.count { it is Movie || it is TvShow }
        val hasMore = rawLocalHasMore &&
            (mood == TasteMood.ALL || visibleShows > 0) &&
            ordered.size < SearchResultGuard.maxLocalResults()
        _state.emit(
            State.SuccessSearching(
                results = ordered,
                hasMore = hasMore,
            ),
        )
    }

    private fun rememberSearchOutcome(
        query: String,
        outcome: SearchMemories.Outcome,
        global: Boolean,
        bumpTimestamp: Boolean = true,
    ) {
        val app = BetterStreamflixApp.instance.applicationContext
        if (global) {
            val key = "$query|$outcome"
            if (lastGlobalIngestKey == key && outcome == SearchMemories.Outcome.SUCCESS) {
                return
            }
            lastGlobalIngestKey = key
        }
        SearchMemories.recordOutcome(
            app,
            query,
            outcome,
            global,
            bumpTimestamp = bumpTimestamp,
        )
        if (outcome == SearchMemories.Outcome.SUCCESS) {
            val top = when {
                rawLocalResults.isNotEmpty() -> rawLocalResults
                else -> rawGlobalResults.flatMap { pr ->
                    (pr.state as? ProviderResult.State.Success)?.results.orEmpty()
                }
            }.filter { it is Movie || it is TvShow }
            // Prefer items that already carry genres for affinity bumps.
            val withGenres = top.filter { item ->
                when (item) {
                    is Movie -> item.genres.isNotEmpty()
                    is TvShow -> item.genres.isNotEmpty()
                    else -> false
                }
            }
            TasteGraph.ingestSearchMemory(app, query, withGenres.ifEmpty { top })
        }
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
                        rememberSearchOutcome(query, SearchMemories.Outcome.FAILED, global = false)
                        _state.emit(
                            State.FailedSearching(
                                Exception(
                                    BetterStreamflixApp.instance.getString(
                                        R.string.error_no_provider_selected,
                                    ),
                                ),
                            ),
                        )
                        return@launch
                    }
                this@SearchViewModel.query = query
                page = 1
                rawGlobalResults = emptyList()

                val intent = SearchQueryParser.parse(query)
                when (intent) {
                    is SearchQueryParser.Intent.SimilarTo -> {
                        searchSimilarTo(provider, intent)
                    }
                    is SearchQueryParser.Intent.Normal -> {
                        searchNormal(provider, intent.query)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Errors (OOM from DE HTML/sitemap scrapes) must not kill the TV process.
                Log.e("SearchViewModel", "search: ", t)
                rememberSearchOutcome(query, SearchMemories.Outcome.FAILED, global = false)
                _state.emit(
                    State.FailedSearching(
                        (t as? Exception) ?: Exception(t.message ?: t.javaClass.simpleName, t),
                    ),
                )
            }
        }
    }

    private suspend fun searchSimilarTo(
        provider: Provider,
        intent: SearchQueryParser.Intent.SimilarTo,
    ) {
        val language = resolveSearchLanguage(provider)
        val similar = ParentalControlUtils.filterItems(
            SearchResultGuard.sanitize(
                SearchTmdbAssist.similarTo(intent.seedTitle, language),
            ),
        )
        // Also try the current catalog for the seed title itself.
        val seedHits = if (provider !is IptvProvider) {
            SearchResultGuard.sanitize(
                ParentalControlUtils.filterItems(
                    runCatching {
                        ProviderSmoke.withProviderTimeout(
                            timeoutMs = ProviderSmoke.SEARCH_TIMEOUT_MS,
                            label = "searchSimilarSeed(${provider.name})",
                        ) {
                            SearchResultGuard.sanitize(provider.search(intent.seedTitle))
                        }
                    }.getOrDefault(emptyList()),
                ),
            )
        } else {
            emptyList()
        }
        val headerLabel = BetterStreamflixApp.instance.getString(
            R.string.search_similar_header,
            intent.seedTitle,
        )
        // Header Category uses an empty list (Mobile full-width label). TV rows drop
        // empty CATEGORY_TV_ITEM shelves to avoid Leanback focus traps.
        val merged = buildList {
            if (similar.isNotEmpty() || seedHits.isNotEmpty()) {
                add(
                    Category(name = headerLabel, list = emptyList()).apply {
                        identityKey = "search-similar:${intent.seedTitle}"
                    },
                )
            }
            addAll(similar)
            addAll(seedHits)
        }.distinctBy { it.searchIdentityKey() }
            .take(SearchResultGuard.maxLocalResults())
        rawLocalResults = merged
        rawLocalHasMore = false
        emitLocalSuccess()
    }

    private suspend fun searchNormal(provider: Provider, query: String) {
        val language = resolveSearchLanguage(provider)
        val mood = UserPreferences.searchMood
        val isIptv = provider is IptvProvider
        val results = SearchResultGuard.sanitize(
            ParentalControlUtils.filterItems(
                ProviderSmoke.withProviderTimeout(
                    timeoutMs = ProviderSmoke.SEARCH_TIMEOUT_MS,
                    label = "search(${provider.name})",
                ) {
                    SearchResultGuard.sanitize(provider.search(query))
                },
            ),
        )
        val addonHits = ParentalControlUtils.filterItems(
            runCatching {
                SearchResultGuard.sanitize(
                    com.dskja.betterstreamflix.platform.plugins.PluginManager
                        .collectSearchResults(provider, query, page = 1),
                )
            }.getOrDefault(emptyList()),
        )
        var merged = (results + addonHits)
            .distinctBy { it.searchIdentityKey() }

        // Blank query + mood → Discover browse by mood genre ids (TMDb assist).
        if (!isIptv && query.isBlank() && mood != TasteMood.ALL) {
            val discover = ParentalControlUtils.filterItems(
                SearchResultGuard.sanitize(
                    SearchTmdbAssist.discoverByMood(mood, language),
                ),
            )
            if (discover.isNotEmpty()) {
                merged = (discover + merged).distinctBy { it.searchIdentityKey() }
            }
        }

        val showHits = merged.count { it is Movie || it is TvShow }
        val hasPeople = merged.any { it is People }

        // Typo rescue + people/directors when the catalog misspells or ignores names.
        if (!isIptv && query.isNotBlank()) {
            if (showHits == 0) {
                val rescue = SearchTmdbAssist.typoRescue(query, language)
                val corrected = rescue.correctedQuery
                val retry = if (!corrected.isNullOrBlank()) {
                    SearchResultGuard.sanitize(
                        ParentalControlUtils.filterItems(
                            runCatching {
                                ProviderSmoke.withProviderTimeout(
                                    timeoutMs = ProviderSmoke.SEARCH_TIMEOUT_MS,
                                    label = "searchTypo(${provider.name})",
                                ) {
                                    SearchResultGuard.sanitize(provider.search(corrected))
                                }
                            }.getOrDefault(emptyList()),
                        ),
                    )
                } else {
                    emptyList()
                }
                merged = (rescue.people + retry + rescue.shows + merged)
                    .distinctBy { it.searchIdentityKey() }
            } else if (!hasPeople) {
                val people = ParentalControlUtils.filterItems(
                    SearchTmdbAssist.peopleForQuery(query, language),
                )
                if (people.isNotEmpty()) {
                    merged = (people + merged).distinctBy { it.searchIdentityKey() }
                }
            }
        }

        merged = merged.take(SearchResultGuard.maxLocalResults())
        rawLocalResults = merged
        val orderedShows = orderedShowCount(merged)
        rawLocalHasMore = results.isNotEmpty() &&
            merged.size < SearchResultGuard.maxLocalResults() &&
            showHits > 0 &&
            (mood == TasteMood.ALL || orderedShows > 0)
        emitLocalSuccess()
    }

    private fun resolveSearchLanguage(provider: Provider): String? =
        provider.language.takeIf { it.isNotBlank() }
            ?: UserPreferences.providerLanguage?.takeIf { it.isNotBlank() }
            ?: UserPreferences.currentLanguage?.takeIf { it.isNotBlank() }

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
                            _state.emit(State.FailedSearching(Exception(com.dskja.betterstreamflix.BetterStreamflixApp.instance.getString(com.dskja.betterstreamflix.R.string.error_no_provider_selected))))
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
                    val localCap = SearchResultGuard.maxLocalResults()
                    val roomLeft = (localCap - rawLocalResults.size)
                        .coerceAtLeast(0)
                    val accepted = newUniqueResults.take(roomLeft)
                    page += 1
                    rawLocalResults = rawLocalResults + accepted
                    val mood = UserPreferences.searchMood
                    val visible = orderedShowCount(rawLocalResults)
                    rawLocalHasMore = accepted.isNotEmpty() &&
                        rawLocalResults.size < localCap &&
                        (mood == TasteMood.ALL || visible > 0)
                    // Paging must not rewrite memory timestamps.
                    emitLocalSuccess(recordMemory = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e("SearchViewModel", "loadMore: ", t)
                    // Keep the rows already on screen. Replacing them with the error
                    // overlay throws away a successful search because one later page failed.
                    rawLocalHasMore = false
                    if (rawLocalResults.isNotEmpty()) {
                        emitLocalSuccess(recordMemory = false)
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
                rememberSearchOutcome(query, SearchMemories.Outcome.EMPTY, global = true)
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
                                SearchResultGuard.maxGlobalPerProvider(),
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
                    val ordered = orderGlobal(snapshot)
                    SearchMemories.outcomeForGlobal(ordered)?.let { outcome ->
                        rememberSearchOutcome(requestedQuery, outcome, global = true)
                    }
                    _state.emit(State.SuccessGlobalSearching(ordered))
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
