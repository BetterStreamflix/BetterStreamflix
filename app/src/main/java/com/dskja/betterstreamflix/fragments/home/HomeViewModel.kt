package com.dskja.betterstreamflix.fragments.home

import android.util.Log
import com.dskja.betterstreamflix.BetterStreamflixApp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.AnimeOnlineNinjaProvider
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.providers.ProviderSmoke
import com.dskja.betterstreamflix.providers.TmdbProvider
import com.dskja.betterstreamflix.ui.UserDataNotifier
import com.dskja.betterstreamflix.utils.CrashReporter
import com.dskja.betterstreamflix.utils.CrossProviderLibrary
import com.dskja.betterstreamflix.utils.HomeCacheStore
import com.dskja.betterstreamflix.utils.HomeCatalogPipeline
import com.dskja.betterstreamflix.utils.ParentalControlUtils
import com.dskja.betterstreamflix.utils.ProviderChangeNotifier
import com.dskja.betterstreamflix.utils.UserDataCache
import com.dskja.betterstreamflix.utils.UserDataCache.toCached
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

class HomeViewModel(
    @Suppress("UNUSED_PARAMETER")
    database: AppDatabase? = null,
) : ViewModel() {

    private fun liveDatabase(): AppDatabase =
        AppDatabase.getInstance(BetterStreamflixApp.instance.applicationContext)

    private var getHomeJob: Job? = null
    private var userDataCacheJob: Job? = null

    private data class HomeHistory(
        val continueWatching: List<AppAdapter.Item>,
        val recentlyWatched: List<AppAdapter.Item>,
        val favoritesMovies: List<Movie>,
        val favoriteTvShows: List<TvShow>
    )

    private fun <T> preserveCacheOrder(
        cached: List<T>,
        incoming: List<T>,
        idOf: (T) -> String,
    ): List<T> {
        val incomingById = incoming.associateBy(idOf)
        val orderedExisting = cached.mapNotNull { cachedItem -> incomingById[idOf(cachedItem)] }
        val appendedNew = incoming.filter { incomingItem ->
            cached.none { cachedItem -> idOf(cachedItem) == idOf(incomingItem) }
        }
        return orderedExisting + appendedNew
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    private val continueWatchingTvShowCache = ConcurrentHashMap<String, TvShow>()
    private val continueWatchingSeasonEpisodesCache = ConcurrentHashMap<String, List<Episode>>()
    private val _userDataCache = MutableStateFlow<UserDataCache.UserData?>(null)
    private val libraryRefresh = MutableStateFlow(0)
    private val traktContinueWatching = MutableStateFlow<List<AppAdapter.Item>>(emptyList())
    private var currentProvider: Provider? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val homeHistory: Flow<HomeHistory> = libraryRefresh.transformLatest {
        // Home UI combines this flow with catalog state — emit local Room rows
        // immediately so Loading can clear, then soft-enrich CW with a hard cap.
        try {
            val appContext = BetterStreamflixApp.instance.applicationContext
            val raw = CrossProviderLibrary.loadHomeHistory(appContext)
            val movies = raw.continueWatching.filterIsInstance<Movie>()
            val localEpisodes = raw.continueWatching.filterIsInstance<Episode>()

            fun build(episodes: List<Episode>) = HomeHistory(
                continueWatching = (movies + episodes)
                    .sortedByDescending { engagementMillis(it) }
                    .distinctBy { continueWatchingKey(it) },
                recentlyWatched = raw.recentlyWatched,
                favoritesMovies = raw.favoriteMovies,
                favoriteTvShows = raw.favoriteTvShows,
            )

            emit(build(localEpisodes))

            if (localEpisodes.isEmpty()) return@transformLatest
            val enriched = try {
                withTimeout(ProviderSmoke.CW_ENRICH_TIMEOUT_MS) {
                    enrichContinueWatchingEpisodes(localEpisodes)
                }
            } catch (e: TimeoutCancellationException) {
                Log.w("HomeViewModel", "CW enrich timed out; keeping local episode rows")
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("HomeViewModel", "CW enrich failed; keeping local episode rows", e)
                null
            } ?: return@transformLatest

            emit(build(enriched))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A dead Room / closed DB must not kill the combine collector (infinite spinner).
            Log.e("HomeViewModel", "homeHistory failed", e)
            CrashReporter.logNonFatal("HomeViewModel", "homeHistory failed", e)
            emit(
                HomeHistory(
                    continueWatching = emptyList(),
                    recentlyWatched = emptyList(),
                    favoritesMovies = emptyList(),
                    favoriteTvShows = emptyList(),
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        homeHistory,
        traktContinueWatching,

        // MOVIES DB (current provider catalog merge)
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val movies = state.categories
                        .flatMap { it.list }
                        .filterIsInstance<Movie>()
                    if (movies.isEmpty()) {
                        emit(emptyList())
                    } else {
                        val db = runCatching { liveDatabase() }.getOrNull()
                        if (db == null || !db.isOpen) {
                            emit(emptyList())
                        } else {
                            // Provider switch closes Room mid-collect — never crash the combine.
                            emitAll(
                                db.movieDao().getByIds(movies.map { it.id }).catch { e ->
                                    if (e is CancellationException) throw e
                                    Log.w("HomeViewModel", "moviesDb flow failed after provider switch", e)
                                    emit(emptyList())
                                }
                            )
                        }
                    }
                }
                else -> emit(emptyList<Movie>())
            }
        }.flowOn(Dispatchers.IO),

        // TV SHOWS DB
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val tvShows = state.categories
                        .flatMap { it.list }
                        .filterIsInstance<TvShow>()
                    if (tvShows.isEmpty()) {
                        emit(emptyList())
                    } else {
                        val db = runCatching { liveDatabase() }.getOrNull()
                        if (db == null || !db.isOpen) {
                            emit(emptyList())
                        } else {
                            emitAll(
                                db.tvShowDao().getByIds(tvShows.map { it.id }).catch { e ->
                                    if (e is CancellationException) throw e
                                    Log.w("HomeViewModel", "tvShowsDb flow failed after provider switch", e)
                                    emit(emptyList())
                                }
                            )
                        }
                    }
                }
                else -> emit(emptyList<TvShow>())
            }
        }.flowOn(Dispatchers.IO),

        ) { state, history, traktCw, moviesDb, tvShowsDb ->

        when (state) {
            is State.SuccessLoading -> {

                val moviesMap = moviesDb.associateBy { it.id }
                val tvShowsMap = tvShowsDb.associateBy { it.id }

                fun mergeItem(item: AppAdapter.Item): AppAdapter.Item {
                    return when (item) {
                        is Movie -> moviesMap[item.id]
                            ?.takeIf { db ->
                                !item.isSame(db) ||
                                    (item.logo.isNullOrBlank() && !db.logo.isNullOrBlank())
                            }
                            ?.let { item.copy().merge(it) }
                            ?: item

                        is TvShow -> tvShowsMap[item.id]
                            ?.takeIf { db ->
                                !item.isSame(db) ||
                                    (item.logo.isNullOrBlank() && !db.logo.isNullOrBlank())
                            }
                            ?.let { item.copy().merge(it) }
                            ?: item

                        else -> item
                    }
                }

                val categories = ParentalControlUtils.filterCategories(listOfNotNull(

                    // FEATURED
                    state.categories
                        .find { Category.isFeaturedName(it.name) }
                        ?.let { category ->
                            category.copy(
                                list = category.list.map(::mergeItem)
                            )
                        },

                    // CONTINUE WATCHING (local + Trakt + self-host extras merged once)
                    Category(
                        name = Category.CONTINUE_WATCHING,
                        list = com.dskja.betterstreamflix.platform.ContinueWatchingMerger.merge(
                            local = history.continueWatching
                                .sortedByDescending {
                                    when (it) {
                                        is Episode -> it.watchHistory?.lastEngagementTimeUtcMillis
                                            ?: it.watchedDate?.timeInMillis
                                            ?: 0L

                                        is Movie -> it.watchHistory?.lastEngagementTimeUtcMillis
                                            ?: it.watchedDate?.timeInMillis
                                            ?: 0L

                                        else -> 0L
                                    }
                                }
                                .distinctBy { continueWatchingKey(it) },
                            remoteExtras = state.categories
                                .filter {
                                    com.dskja.betterstreamflix.platform.ContinueWatchingMerger
                                        .isProviderContinueWatching(it.name)
                                }
                                .flatMap { it.list } + traktCw,
                        ),
                    ).takeIf { UserPreferences.showContinueWatching },

                    Category(
                        name = Category.RECENTLY_WATCHED,
                        list = history.recentlyWatched,
                    ).takeIf { UserPreferences.showRecentlyWatched },

                    // FAVORITES
                    Category(
                        name = Category.FAVORITE_MOVIES,
                        list = history.favoritesMovies,
                    ),
                    Category(
                        name = Category.FAVORITE_TV_SHOWS,
                        list = history.favoriteTvShows,
                    ),
                ) + state.categories
                    .filter {
                        !Category.isFeaturedName(it.name) &&
                            !com.dskja.betterstreamflix.platform.ContinueWatchingMerger
                                .isProviderContinueWatching(it.name)
                    }
                    .map { category ->
                        category.copy(
                            list = category.list.map(::mergeItem)
                        )
                    })

                State.SuccessLoading(
                    HomeCatalogPipeline.ensureFeaturedShelf(categories),
                    providerWarning = state.providerWarning,
                )
            }

            else -> state
        }
    }.flowOn(Dispatchers.IO)

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(
            val categories: List<Category>,
            /** Soft warning when provider catalog failed but library rows can still show. */
            val providerWarning: String? = null,
        ) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        val initialProvider = UserPreferences.currentProvider
        if (initialProvider != null) {
            currentProvider = initialProvider
            loadUserDataCache(initialProvider)
        }
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                libraryRefresh.value += 1
                getHome()
            }
        }

        viewModelScope.launch {
            UserDataNotifier.updates.collect {
                libraryRefresh.value += 1
                val provider = UserPreferences.currentProvider ?: return@collect
                loadUserDataCache(provider)
            }
        }
        getHome()
    }

    private fun engagementMillis(item: AppAdapter.Item): Long = when (item) {
        is Movie -> item.watchHistory?.lastEngagementTimeUtcMillis
            ?: item.watchedDate?.timeInMillis
            ?: 0L
        is Episode -> item.watchHistory?.lastEngagementTimeUtcMillis
            ?: item.watchedDate?.timeInMillis
            ?: 0L
        else -> 0L
    }

    private fun continueWatchingKey(item: AppAdapter.Item): String? = when (item) {
        is Episode -> "${item.tvShow?.providerName}:${item.tvShow?.id}"
        is Movie -> "${item.providerName}:${item.id}"
        else -> null
    }

    private suspend fun enrichContinueWatchingEpisodes(episodes: List<Episode>): List<Episode> = coroutineScope {
        episodes.map { episode ->
            async {
                val tvShowId = episode.tvShow?.id ?: return@async episode
                val provider = episode.tvShow?.providerName
                    ?.let(Provider::findByName)
                    ?: UserPreferences.currentProvider
                    ?: return@async episode

                val cacheKey = "${provider.name}:$tvShowId"
                val resolvedTvShow = continueWatchingTvShowCache[cacheKey] ?: runCatching {
                    provider.getTvShow(tvShowId)
                }.getOrNull()?.also { fetchedTvShow ->
                    continueWatchingTvShowCache[cacheKey] = fetchedTvShow
                }

                val mergedTvShow = resolvedTvShow?.copy().apply {
                    this?.let { show ->
                        episode.tvShow?.let { existingTvShow -> show.merge(existingTvShow) }
                        // Room converter only stores tvShow id — overlay full row logo when present.
                        liveDatabase().tvShowDao().getById(tvShowId)?.let { dbShow ->
                            show.merge(dbShow)
                        }
                        show.providerName = provider.name
                    }
                } ?: episode.tvShow?.also { fallback ->
                    liveDatabase().tvShowDao().getById(tvShowId)?.let { dbShow ->
                        fallback.merge(dbShow)
                    }
                }

                val resolvedSeason = episode.season?.let { season ->
                    mergedTvShow?.seasons?.firstOrNull { it.id == season.id || it.number == season.number }
                        ?: season
                }

                val resolvedEpisode = if (UserPreferences.enableTmdb) {
                    val seasonId = resolvedSeason?.id
                        ?: episode.season?.id
                    seasonId?.let { key ->
                        val seasonCacheKey = "${provider.name}:$key"
                        continueWatchingSeasonEpisodesCache[seasonCacheKey] ?: runCatching {
                            provider.getEpisodesBySeason(key)
                        }.getOrDefault(emptyList()).also { fetchedEpisodes ->
                            if (fetchedEpisodes.isNotEmpty()) {
                                continueWatchingSeasonEpisodesCache[seasonCacheKey] = fetchedEpisodes
                            }
                        }
                    }?.firstOrNull { seasonEpisode ->
                        seasonEpisode.id == episode.id || seasonEpisode.number == episode.number
                    }
                } else {
                    null
                }

                episode.copy(
                    title = resolvedEpisode?.title ?: episode.title,
                    overview = resolvedEpisode?.overview ?: episode.overview,
                    poster = resolvedEpisode?.poster ?: episode.poster,
                    tvShow = mergedTvShow,
                    season = resolvedSeason,
                ).apply {
                    merge(episode)
                }
            }
        }.awaitAll()
    }

    fun getHome() {
        getHomeJob?.cancel()
        userDataCacheJob?.cancel()
        getHomeJob = viewModelScope.launch(Dispatchers.IO) {
            val provider = UserPreferences.currentProvider ?: run {
                _state.emit(State.FailedLoading(IllegalStateException("No provider selected")))
                return@launch
            }

            currentProvider = provider
            val providerName = provider.name
            val appContext = BetterStreamflixApp.instance.applicationContext
            val rawCached = HomeCacheStore.read(appContext, provider)
            val cachedCategories = rawCached?.let {
                HomeCatalogPipeline.process(provider, it).categories
            }
            val deferCachedHomeForClearance =
                provider === AnimeOnlineNinjaProvider &&
                    !AnimeOnlineNinjaProvider.hasCurrentClearanceCookie()
            // Stale-while-revalidate: show cache immediately even if old, then refresh.
            if (!cachedCategories.isNullOrEmpty() && !deferCachedHomeForClearance) {
                _state.emit(State.SuccessLoading(cachedCategories))
            } else {
                _state.emit(State.Loading)
            }

            loadUserDataCache(provider)
            libraryRefresh.value += 1
            viewModelScope.launch(Dispatchers.IO) {
                traktContinueWatching.value =
                    com.dskja.betterstreamflix.platform.trakt.TraktContinueWatching.load()
            }

            // Circuit breaker: prefer cache over hammering a dead origin.
            if (ProviderSmoke.isHomeCircuitOpen(provider.name) &&
                !cachedCategories.isNullOrEmpty() &&
                !deferCachedHomeForClearance
            ) {
                val hint = ProviderSmoke.circuitHint(provider.name)
                _state.emit(State.SuccessLoading(cachedCategories, providerWarning = hint))
                return@launch
            }

            try {
                val homeTimeoutMs = if (provider is TmdbProvider) {
                    ProviderSmoke.TMDB_HOME_TIMEOUT_MS
                } else {
                    ProviderSmoke.HOME_TIMEOUT_MS
                }
                val categories = ProviderSmoke.withProviderTimeout(
                    timeoutMs = homeTimeoutMs,
                    label = "getHome(${provider.name})",
                ) {
                    provider.getHome()
                }
                // Compare by name: TMDb used to allocate a new instance per
                // UserPreferences read, so reference != always aborted Success.
                if (!isActive || providerName != UserPreferences.currentProvider?.name) return@launch
                val addonRows = runCatching {
                    com.dskja.betterstreamflix.platform.plugins.PluginManager
                        .collectHomeCategories(provider)
                }.getOrDefault(emptyList())
                val processed = HomeCatalogPipeline.process(provider, categories, addonRows)
                HomeCacheStore.write(appContext, provider, processed.categories)
                ProviderSmoke.noteHomeSuccess(provider.name)
                if (!isActive || providerName != UserPreferences.currentProvider?.name) return@launch
                _state.emit(
                    State.SuccessLoading(
                        processed.categories,
                        providerWarning = processed.warningText,
                    )
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (!isActive) return@launch
                Log.e("HomeViewModel", "getHome: ", e)
                ProviderSmoke.noteHomeFailure(provider.name)
                // Provider timeouts are expected soft-fails — keep them local (BETTERSTREAMFLIX-1C).
                val isTimeout = e is java.util.concurrent.TimeoutException ||
                    e.cause is TimeoutCancellationException
                if (!isTimeout) {
                    CrashReporter.logNonFatal("HomeViewModel", "getHome failed for ${provider.name}", e)
                }
                val warning = buildString {
                    append(
                        e.message?.takeIf { it.isNotBlank() }
                            ?: "Catalog unavailable for ${provider.name}",
                    )
                    if (ProviderSmoke.isHomeCircuitOpen(provider.name)) {
                        append(" · paused after repeated failures")
                    }
                }
                if (!cachedCategories.isNullOrEmpty()) {
                    // Keep serving cache on failure / timeout (including deferred clearance case).
                    _state.emit(State.SuccessLoading(cachedCategories, providerWarning = warning))
                } else {
                    // Soft-fail: empty catalog still lets continue-watching / favorites render.
                    _state.emit(State.SuccessLoading(emptyList(), providerWarning = warning))
                }
            }
        }
    }

    private fun loadUserDataCache(provider: Provider) {
        val appContext = BetterStreamflixApp.instance.applicationContext
        userDataCacheJob?.cancel()
        userDataCacheJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val cached = UserDataCache.read(appContext, provider)
                _userDataCache.value = cached
                val db = runCatching { AppDatabase.getInstance(appContext) }.getOrNull()
                    ?: return@launch
                if (!db.isOpen) return@launch
                val moviesDeferred = async { db.movieDao().getFavorites().first() }
                val tvShowsDeferred = async { db.tvShowDao().getFavorites().first() }
                val watchingMoviesDeferred = async { db.movieDao().getWatchingMoviesCapped().first() }
                val watchingEpisodesDeferred = async { db.episodeDao().getWatchingEpisodesCapped().first() }

                val movies = moviesDeferred.await()
                val tvShows = tvShowsDeferred.await()
                val watchingMovies = watchingMoviesDeferred.await()
                val watchingEpisodes = watchingEpisodesDeferred.await()

                if (!isActive || provider.name != UserPreferences.currentProvider?.name) return@launch

                val newData = UserDataCache.UserData(
                    favoritesMovies = preserveCacheOrder(
                        cached = cached?.favoritesMovies ?: emptyList(),
                        incoming = movies.filter { it.isFavorite }.map { it.toCached() },
                        idOf = { it.id },
                    ),
                    favoritesTvShows = preserveCacheOrder(
                        cached = cached?.favoritesTvShows ?: emptyList(),
                        incoming = tvShows.filter { it.isFavorite }.map { it.toCached() },
                        idOf = { it.id },
                    ),
                    continueWatchingMovies = preserveCacheOrder(
                        cached = cached?.continueWatchingMovies ?: emptyList(),
                        incoming = (movies + watchingMovies)
                            .filter { it.watchHistory != null }
                            .map { it.toCached() },
                        idOf = { it.id },
                    ),
                    continueWatchingEpisodes = preserveCacheOrder(
                        cached = cached?.continueWatchingEpisodes ?: emptyList(),
                        incoming = watchingEpisodes
                            .filter { it.watchHistory != null }
                            .map { it.toCached() },
                        idOf = { it.id },
                    ),
                )

                UserDataCache.write(appContext, provider, newData)

                if (_userDataCache.value != newData) {
                    _userDataCache.value = newData
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.e("HomeViewModel", "loadUserDataCache failed", e)
            }
        }
    }
}
