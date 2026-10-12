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
import com.dskja.betterstreamflix.utils.ShowLookup
import com.dskja.betterstreamflix.utils.UserDataCache
import com.dskja.betterstreamflix.utils.UserDataCache.toCached
import com.dskja.betterstreamflix.utils.UserDataCache.toEpisode
import com.dskja.betterstreamflix.utils.UserDataCache.toMovie
import com.dskja.betterstreamflix.utils.UserDataCache.toTvShow
import com.dskja.betterstreamflix.familyfriday.FamilyFridayShelf
import com.dskja.betterstreamflix.home.HomeRemix
import com.dskja.betterstreamflix.home.HomeShelfCompanions
import com.dskja.betterstreamflix.home.HomeShelfLayout
import com.dskja.betterstreamflix.home.SeriesFastForward
import com.dskja.betterstreamflix.smartcollections.SmartCollectionsBuilder
import com.dskja.betterstreamflix.utils.LiveHubCatalog
import com.dskja.betterstreamflix.watchlist.WatchlistDigestPlanner
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.watchlistweek.WatchlistWeekBuilder
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

private const val RECENTLY_ADDED_SHELF_LIMIT = 20

class HomeViewModel(
    @Suppress("UNUSED_PARAMETER")
    database: AppDatabase? = null,
) : ViewModel() {

    private fun liveDatabase(): AppDatabase? =
        AppDatabase.getInstanceOrNull(BetterStreamflixApp.instance.applicationContext)

    private var getHomeJob: Job? = null
    private var userDataCacheJob: Job? = null

    private data class HomeHistory(
        val continueWatching: List<AppAdapter.Item>,
        val nextEpisodes: List<Episode>,
        val recentlyWatched: List<AppAdapter.Item>,
        val favoritesMovies: List<Movie>,
        val favoriteTvShows: List<TvShow>,
        val watchlistDigestEpisodes: List<Episode> = emptyList(),
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

    /** Unfiltered Next Episode ids from the latest Home combine (for visit snapshots). */
    @Volatile
    private var lastUnfilteredNextEpisodeIds: Set<String> = emptySet()

    /** Unfiltered Favorite TV ids from the latest Home combine (for visit snapshots). */
    @Volatile
    private var lastUnfilteredFavoriteTvIds: Set<String> = emptySet()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val homeHistory: Flow<HomeHistory> = libraryRefresh.transformLatest {
        // Home UI combines this flow with catalog state — emit local Room rows
        // immediately so Loading can clear, then soft-enrich CW with a hard cap.
        try {
            val appContext = BetterStreamflixApp.instance.applicationContext
            val raw = CrossProviderLibrary.loadHomeHistory(appContext)
            val movies = raw.continueWatching.filterIsInstance<Movie>()
            val localEpisodes = raw.continueWatching.filterIsInstance<Episode>()
            val localNext = raw.nextEpisodes

            fun build(
                episodes: List<Episode>,
                next: List<Episode>,
                digest: List<Episode> = emptyList(),
            ) = HomeHistory(
                continueWatching = (movies + episodes)
                    .sortedByDescending { engagementMillis(it) }
                    .distinctBy { continueWatchingKey(it) },
                nextEpisodes = next
                    .sortedByDescending { engagementMillis(it) }
                    .distinctBy { continueWatchingKey(it) },
                recentlyWatched = filterRecentlyWatchedAgainstActive(
                    recentlyWatched = raw.recentlyWatched,
                    continueWatching = movies + episodes,
                    nextEpisodes = next,
                ),
                favoritesMovies = raw.favoriteMovies,
                favoriteTvShows = raw.favoriteTvShows,
                watchlistDigestEpisodes = digest,
            )

            val digest = runCatching {
                WatchlistDigestPlanner.plan(appContext)
            }.getOrElse {
                Log.w("HomeViewModel", "watchlist digest plan failed", it)
                emptyList()
            }

            // Stale-while-revalidate: if Room CW is empty, surface JSON cache until sync fills it.
            // UserDataCache is CW+favorites only — overlay Room Next/RW when present.
            if (movies.isEmpty() && localEpisodes.isEmpty() && localNext.isEmpty()) {
                val cachedFallback = historyFromUserDataCache(
                    nextEpisodes = localNext,
                    recentlyWatched = raw.recentlyWatched,
                )?.copy(watchlistDigestEpisodes = digest)
                if (cachedFallback != null) {
                    emit(cachedFallback)
                } else {
                    emit(build(localEpisodes, localNext, digest))
                }
            } else {
                emit(build(localEpisodes, localNext, digest))
            }

            val toEnrich = (localEpisodes + localNext).distinctBy { it.id }
            if (toEnrich.isEmpty()) return@transformLatest
            val enriched = try {
                withTimeout(ProviderSmoke.CW_ENRICH_TIMEOUT_MS) {
                    enrichContinueWatchingEpisodes(toEnrich)
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

            val enrichedById = enriched.associateBy { it.id }
            emit(
                build(
                    episodes = localEpisodes.map { enrichedById[it.id] ?: it },
                    next = localNext.map { enrichedById[it.id] ?: it },
                    digest = digest,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A dead Room / closed DB must not kill the combine collector (infinite spinner).
            Log.e("HomeViewModel", "homeHistory failed", e)
            CrashReporter.logNonFatal("HomeViewModel", "homeHistory failed", e)
            val roomNextRw = quickRoomNextAndRecentlyWatched()
            emit(
                historyFromUserDataCache(
                    nextEpisodes = roomNextRw?.first.orEmpty(),
                    recentlyWatched = roomNextRw?.second.orEmpty(),
                ) ?: HomeHistory(
                    continueWatching = emptyList(),
                    nextEpisodes = roomNextRw?.first.orEmpty(),
                    recentlyWatched = roomNextRw?.second.orEmpty(),
                    favoritesMovies = emptyList(),
                    favoriteTvShows = emptyList(),
                    watchlistDigestEpisodes = emptyList(),
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
                        val db = liveDatabase()
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
                        val db = liveDatabase()
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

                val featuredCategory = state.categories
                    .find { Category.isFeaturedName(it.name) }
                    ?.let { category ->
                        category.copy(
                            list = category.list.map(::mergeItem)
                        )
                    }

                val mergedCw = com.dskja.betterstreamflix.platform.ContinueWatchingMerger.merge(
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
                )
                val cwKeys = mergedCw.mapNotNull {
                    com.dskja.betterstreamflix.platform.ContinueWatchingMerger.identityKey(it)
                }.toSet()
                // Drop Next tiles that already appear on merged CW (remote extras can overlap).
                val nextDeduped = history.nextEpisodes.filter { ep ->
                    val key = com.dskja.betterstreamflix.platform.ContinueWatchingMerger
                        .identityKey(ep)
                    key == null || key !in cwKeys
                }
                lastUnfilteredNextEpisodeIds = nextDeduped.map { it.id }.filter { it.isNotBlank() }.toSet()
                lastUnfilteredFavoriteTvIds =
                    history.favoriteTvShows.map { it.id }.filter { it.isNotBlank() }.toSet()
                val appCtx = BetterStreamflixApp.instance.applicationContext
                val nextTasteClean = SeriesFastForward.filterTasteHidden(appCtx, nextDeduped)
                val favTasteClean = SeriesFastForward.filterTasteHidden(
                    appCtx,
                    history.favoriteTvShows,
                )
                val nextFiltered = SeriesFastForward.filterEpisodes(nextTasteClean)
                val favoriteTvFiltered = SeriesFastForward.filterTvShows(favTasteClean)
                val rwDeduped = filterRecentlyWatchedAgainstActive(
                    recentlyWatched = history.recentlyWatched,
                    continueWatching = mergedCw,
                    nextEpisodes = nextDeduped,
                )
                // Recently Added — favorites by favoritedAt (limited Home companion shelf)
                val recentlyAdded = run {
                    val recent = (history.favoritesMovies + history.favoriteTvShows)
                        .sortedByDescending { item ->
                            when (item) {
                                is Movie -> item.favoritedAtMillis ?: 0L
                                is TvShow -> item.favoritedAtMillis ?: 0L
                                else -> 0L
                            }
                        }
                        .take(RECENTLY_ADDED_SHELF_LIMIT)
                    Category(name = Category.RECENTLY_ADDED, list = recent)
                        .takeIf { recent.isNotEmpty() }
                }

                val smartShelves = SmartCollectionsBuilder.build(
                    context = BetterStreamflixApp.instance.applicationContext,
                    favorites = history.favoritesMovies + history.favoriteTvShows,
                    continueWatching = mergedCw,
                )

                fun stamp(category: Category): Category =
                    HomeShelfLayout.stampIdentity(category, category.name)

                // Kids Friday ritual — catalog + favorites pool, independent of Smart Collections.
                val familyFriday = FamilyFridayShelf.build(
                    catalogCategories = state.categories,
                    favoriteCategories = listOf(
                        Category(
                            name = Category.FAVORITE_MOVIES,
                            list = history.favoritesMovies,
                        ),
                        Category(
                            name = Category.FAVORITE_TV_SHOWS,
                            list = history.favoriteTvShows,
                        ),
                    ),
                )?.let(::stamp)

                val watchlistWeek = WatchlistWeekBuilder.build(
                    context = BetterStreamflixApp.instance.applicationContext,
                    favoriteTvShows = history.favoriteTvShows,
                )?.let(::stamp)

                val continueShelves = listOfNotNull(
                    Category(
                        name = Category.CONTINUE_WATCHING,
                        list = mergedCw,
                    ).takeIf {
                        UserPreferences.showContinueWatching && mergedCw.isNotEmpty()
                    }?.let(::stamp),
                    familyFriday,
                    Category(
                        name = Category.NEXT_EPISODES,
                        list = nextFiltered,
                    ).takeIf {
                        UserPreferences.showNextEpisodes &&
                            SeriesFastForward.keepShelfWhenEmpty(
                                categoryName = Category.NEXT_EPISODES,
                                unfilteredCount = nextDeduped.size,
                                filteredCount = nextFiltered.size,
                            )
                    }?.let(::stamp),
                    watchlistWeek,
                    Category(
                        name = Category.RECENTLY_WATCHED,
                        list = rwDeduped,
                    ).takeIf {
                        UserPreferences.showRecentlyWatched && rwDeduped.isNotEmpty()
                    }?.let(::stamp),
                    recentlyAdded?.let(::stamp),
                )
                val liveShelf = buildHomeLiveShelf(history.favoriteTvShows)
                val collectionShelves = smartShelves.map(::stamp)
                val remixedLibrary = HomeRemix.apply(
                    categories = continueShelves + listOfNotNull(liveShelf) + collectionShelves,
                    order = HomeRemix.parseOrder(UserPreferences.homeShelfBlockOrder),
                )
                val appContext = BetterStreamflixApp.instance.applicationContext
                val downloadsShelf = HomeShelfCompanions.downloadsShelf(
                    context = appContext,
                    mobile = !com.dskja.betterstreamflix.utils.DeviceCapabilities
                        .isLeanbackDevice(appContext),
                )

                val categories = ParentalControlUtils.filterCategories(
                    listOfNotNull(featuredCategory?.let(::stamp)) +
                        remixedLibrary +
                        listOfNotNull(downloadsShelf) +
                        listOf(
                            stamp(
                                Category(
                                    name = Category.FAVORITE_MOVIES,
                                    list = history.favoritesMovies,
                                ),
                            ),
                            stamp(
                                Category(
                                    name = Category.FAVORITE_TV_SHOWS,
                                    list = favoriteTvFiltered,
                                ),
                            ),
                        ) +
                        com.dskja.betterstreamflix.platform.ContinueWatchingMerger
                            .filterProviderCategories(state.categories)
                            .filter { !Category.isFeaturedName(it.name) }
                            .map { category ->
                                stamp(
                                    category.copy(
                                        list = category.list.map(::mergeItem),
                                    ),
                                )
                            }
                )

                // Remix orders blocks; Shelf Pinning floats up to 3 shelves under Featured.
                val ordered = HomeShelfLayout.apply(
                    HomeCatalogPipeline.ensureFeaturedShelf(categories),
                )
                State.SuccessLoading(
                    ordered,
                    providerWarning = state.providerWarning,
                    watchlistDigestEpisodes = history.watchlistDigestEpisodes,
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
            /** Weekly Watchlist Digest picks (≤5) for the home card. */
            val watchlistDigestEpisodes: List<Episode> = emptyList(),
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
                HomeShelfCompanions.invalidateDownloadsCache()
                WatchlistWeekBuilder.invalidateCache()
                getHome()
            }
        }

        viewModelScope.launch {
            UserDataNotifier.updates.collect {
                libraryRefresh.value += 1
                HomeShelfCompanions.invalidateDownloadsCache()
                WatchlistWeekBuilder.invalidateCache()
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
        is Episode -> {
            val showId = item.tvShow?.id
            if (showId.isNullOrBlank()) "episode:${item.id}"
            else "${item.tvShow?.providerName}:$showId"
        }
        is Movie -> "${item.providerName}:${item.id}"
        else -> null
    }

    /** Home Live strip from favorited IPTV / hub channels (Remix Home block). */
    private fun buildHomeLiveShelf(favoriteTvShows: List<TvShow>): Category? {
        if (LiveHubCatalog.isAgeGated()) return null
        val live = favoriteTvShows
            .filter { LiveHubCatalog.isLiveFavorite(it) }
            .sortedByDescending { it.favoritedAtMillis ?: 0L }
            .take(24)
        if (live.isEmpty()) return null
        return Category(name = Category.HOME_LIVE, list = live).also {
            it.identityKey = Category.HOME_LIVE_ID
        }
    }

    /** Drop Recently Watched tiles that are already on Continue / Next shelves. */
    private fun filterRecentlyWatchedAgainstActive(
        recentlyWatched: List<AppAdapter.Item>,
        continueWatching: List<AppAdapter.Item>,
        nextEpisodes: List<Episode>,
    ): List<AppAdapter.Item> {
        val merger = com.dskja.betterstreamflix.platform.ContinueWatchingMerger
        val activeKeys = buildSet {
            continueWatching.mapNotNullTo(this) { merger.identityKey(it) }
            nextEpisodes.mapNotNullTo(this) { merger.identityKey(it) }
            // Also keep provider:id keys for local-only matches without IMDb.
            continueWatching.mapNotNullTo(this) { continueWatchingKey(it) }
            nextEpisodes.mapNotNullTo(this) { continueWatchingKey(it) }
        }
        if (activeKeys.isEmpty()) return recentlyWatched
        return recentlyWatched.filter { item ->
            val identity = merger.identityKey(item)
            val localKey = when (item) {
                is Movie -> "${item.providerName}:${item.id}"
                is TvShow -> "${item.providerName}:${item.id}"
                else -> null
            }
            val overlap = (identity != null && identity in activeKeys) ||
                (localKey != null && localKey in activeKeys)
            if (!overlap) return@filter true
            // Keep finished titles that still carry watched / progress context on RW.
            when (item) {
                is Movie -> item.isWatched || (item.watchHistory?.durationMillis ?: 0L) > 0L
                is TvShow -> {
                    val ep = item.lastPlayedEpisode
                    ep?.isWatched == true || (ep?.watchHistory?.durationMillis ?: 0L) > 0L
                }
                else -> false
            }
        }
    }

    /** Soft-refresh library/history shelves (same path as [UserDataNotifier]). */
    fun softRefreshUserData() {
        libraryRefresh.value += 1
        val provider = UserPreferences.currentProvider ?: return
        loadUserDataCache(provider)
    }

    /** Toggle Series Fast-Forward and rebuild shelves immediately. */
    fun setSeriesFastForwardEnabled(enabled: Boolean) {
        SeriesFastForward.setEnabled(enabled)
        libraryRefresh.value += 1
    }

    /**
     * End-of-visit watermark: snapshot unfiltered Next / Favorite-TV ids and
     * stamp [UserPreferences.homeLastVisitAtMs]. Call from Home onPause.
     */
    fun commitHomeVisit() {
        SeriesFastForward.commitVisit(
            nextEpisodeIds = lastUnfilteredNextEpisodeIds,
            favoriteTvIds = lastUnfilteredFavoriteTvIds,
        )
    }

    /**
     * JSON [UserDataCache] stores Continue Watching + favorites only — no Next/RW fields.
     * Callers should pass Room Next/RW overlays when the DB is still readable.
     */
    private fun historyFromUserDataCache(
        nextEpisodes: List<Episode> = emptyList(),
        recentlyWatched: List<AppAdapter.Item> = emptyList(),
    ): HomeHistory? {
        val providerName = UserPreferences.currentProvider?.name
        val cached = _userDataCache.value ?: run {
            val provider = UserPreferences.currentProvider ?: return null
            UserDataCache.read(BetterStreamflixApp.instance.applicationContext, provider)
        } ?: return null
        val movies = cached.continueWatchingMovies.map {
            it.toMovie().apply { this.providerName = providerName }
        }
        val episodes = cached.continueWatchingEpisodes.map {
            it.toEpisode().apply {
                tvShow?.providerName = providerName
            }
        }
        val next = cached.nextEpisodes.map {
            it.toEpisode().apply {
                tvShow?.providerName = providerName
            }
        }
        val recently = (
            cached.recentlyWatchedMovies.map {
                it.toMovie().apply { this.providerName = providerName }
            } + cached.recentlyWatchedTvShows.map {
                it.toTvShow().apply { this.providerName = providerName }
            }
            ).sortedByDescending { item ->
                when (item) {
                    is Movie -> item.lastPlayedAtMillis ?: 0L
                    is TvShow -> item.lastPlayedAtMillis ?: 0L
                    else -> 0L
                }
            }
        if (movies.isEmpty() && episodes.isEmpty() && next.isEmpty() && recently.isEmpty() &&
            cached.favoritesMovies.isEmpty() && cached.favoritesTvShows.isEmpty()
        ) {
            return null
        }
        val continueWatching = (movies + episodes)
            .sortedByDescending { engagementMillis(it) }
            .distinctBy { continueWatchingKey(it) }
        val nextMerged = (next + nextEpisodes)
            .sortedByDescending { engagementMillis(it) }
            .distinctBy { continueWatchingKey(it) }
        val recentlyMerged = (recently + recentlyWatched)
            .sortedByDescending { item ->
                when (item) {
                    is Movie -> item.lastPlayedAtMillis ?: 0L
                    is TvShow -> item.lastPlayedAtMillis ?: 0L
                    else -> 0L
                }
            }
            .distinctBy { item ->
                when (item) {
                    is Movie -> "${item.providerName}:${item.id}"
                    is TvShow -> "${item.providerName}:${item.id}"
                    else -> item.hashCode().toString()
                }
            }
        return HomeHistory(
            continueWatching = continueWatching,
            nextEpisodes = nextMerged,
            recentlyWatched = filterRecentlyWatchedAgainstActive(
                recentlyWatched = recentlyMerged,
                continueWatching = continueWatching,
                nextEpisodes = nextMerged,
            ),
            favoritesMovies = cached.favoritesMovies.map {
                it.toMovie().apply { this.providerName = providerName }
            },
            favoriteTvShows = cached.favoritesTvShows.map {
                it.toTvShow().apply { this.providerName = providerName }
            },
        )
    }

    /** Best-effort Room Next/RW when the primary history load failed; never blocks long. */
    private suspend fun quickRoomNextAndRecentlyWatched(): Pair<List<Episode>, List<AppAdapter.Item>>? {
        val db = liveDatabase()
        if (db == null || !db.isOpen) return null
        return try {
            withTimeout(1_200L) {
                val raw = CrossProviderLibrary.loadHomeHistory(
                    BetterStreamflixApp.instance.applicationContext,
                )
                raw.nextEpisodes to raw.recentlyWatched
            }
        } catch (e: TimeoutCancellationException) {
            Log.w("HomeViewModel", "quick Room Next/RW timed out")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HomeViewModel", "quick Room Next/RW failed", e)
            null
        }
    }

    /**
     * Replace Trakt placeholder ids (`trakt:…` / imdb-only) with current-provider
     * catalog ids so CW taps do not navigate with synthetic `${imdb}-S#E#` keys.
     */
    private suspend fun resolveTraktCatalogIds(
        items: List<AppAdapter.Item>,
        provider: Provider,
    ): List<AppAdapter.Item> = coroutineScope {
        items.map { item ->
            async {
                when (item) {
                    is Movie -> resolveTraktMovie(item, provider)
                    is Episode -> resolveTraktEpisode(item, provider)
                    else -> item
                }
            }
        }.awaitAll()
    }

    private suspend fun resolveTraktMovie(movie: Movie, provider: Provider): Movie {
        // Numeric TMDb / provider ids may already work; still prefer a catalog search hit.
        val hit = runCatching { provider.search(movie.title, 1) }.getOrNull()
            ?.filterIsInstance<Movie>()
            ?.firstOrNull { ShowLookup.titlesMatch(it.title, movie.title) }
            ?: return movie
        val resolved = runCatching { provider.getMovie(hit.id) }.getOrNull() ?: hit
        return movie.copy(
            id = resolved.id,
            poster = movie.poster ?: resolved.poster,
            banner = movie.banner ?: resolved.banner,
            overview = movie.overview ?: resolved.overview,
            imdbId = movie.imdbId ?: resolved.imdbId,
        ).apply {
            providerName = provider.name
            watchHistory = movie.watchHistory
            isWatched = movie.isWatched
            watchedDate = movie.watchedDate
        }
    }

    private suspend fun resolveTraktEpisode(episode: Episode, provider: Provider): Episode {
        val show = episode.tvShow ?: return episode
        val seasonNumber = episode.season?.number ?: return episode
        // Already a non-placeholder provider episode id — leave alone.
        if (!episode.id.startsWith("trakt:")) return episode
        val hit = runCatching { provider.search(show.title, 1) }.getOrNull()
            ?.filterIsInstance<TvShow>()
            ?.firstOrNull { ShowLookup.titlesMatch(it.title, show.title) }
            ?: return episode
        val resolvedShow = runCatching { provider.getTvShow(hit.id) }.getOrNull() ?: hit
        resolvedShow.providerName = provider.name
        val season = resolvedShow.seasons.firstOrNull { it.number == seasonNumber }
            ?: return episode.copy(tvShow = resolvedShow).apply { merge(episode) }
        val seasonEpisodes = runCatching { provider.getEpisodesBySeason(season.id) }
            .getOrDefault(emptyList())
        val matched = seasonEpisodes.firstOrNull { it.number == episode.number }
            ?: return episode.copy(tvShow = resolvedShow, season = season).apply { merge(episode) }
        return matched.copy(
            title = episode.title ?: matched.title,
            poster = episode.poster ?: matched.poster ?: resolvedShow.poster,
            overview = episode.overview ?: matched.overview,
            tvShow = resolvedShow,
            season = season,
        ).apply {
            merge(episode)
        }
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

                val db = liveDatabase()
                val mergedTvShow = resolvedTvShow?.copy().apply {
                    this?.let { show ->
                        episode.tvShow?.let { existingTvShow -> show.merge(existingTvShow) }
                        // Room converter only stores tvShow id — overlay full row logo when present.
                        db?.tvShowDao()?.getById(tvShowId)?.let { dbShow ->
                            show.merge(dbShow)
                        }
                        show.providerName = provider.name
                    }
                } ?: episode.tvShow?.also { fallback ->
                    db?.tvShowDao()?.getById(tvShowId)?.let { dbShow ->
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
                _state.emit(State.FailedLoading(IllegalStateException(com.dskja.betterstreamflix.BetterStreamflixApp.instance.getString(com.dskja.betterstreamflix.R.string.error_no_provider_selected))))
                return@launch
            }

            currentProvider = provider
            val providerName = provider.name
            val appContext = BetterStreamflixApp.instance.applicationContext
            runCatching {
                val watched = liveDatabase()?.takeIf { it.isOpen }?.episodeDao()?.countWatchedEpisodes()
                if (watched != null) {
                    com.dskja.betterstreamflix.support.SupportPromptPolicy
                        .seedWatchedCountFromDatabase(watched)
                }
            }
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
                val raw = com.dskja.betterstreamflix.platform.trakt.TraktContinueWatching.load()
                traktContinueWatching.value = resolveTraktCatalogIds(raw, provider)
            }

            // Circuit breaker: prefer cache over hammering a dead origin.
            // Still protect when cache is empty (after a prior wipe) — skip live probe.
            if (ProviderSmoke.isHomeCircuitOpen(provider.name) && !deferCachedHomeForClearance) {
                val hint = ProviderSmoke.circuitHint(provider.name)
                    ?: "Catalog paused for ${provider.name} (recent failures)"
                if (!cachedCategories.isNullOrEmpty()) {
                    _state.emit(State.SuccessLoading(cachedCategories, providerWarning = hint))
                } else {
                    // Empty cache + open circuit looked like a permanently blank Home.
                    _state.emit(State.FailedLoading(Exception(hint)))
                }
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
                if (!isActive) return@launch
                if (providerName != UserPreferences.currentProvider?.name) {
                    // Sticky playback switched mid-home-fetch. SWR may already be on screen;
                    // only re-dispatch when we would otherwise leave a bare Loading state.
                    if (cachedCategories.isNullOrEmpty() || deferCachedHomeForClearance) {
                        viewModelScope.launch { getHome() }
                    }
                    return@launch
                }
                val addonRows = runCatching {
                    com.dskja.betterstreamflix.platform.plugins.PluginManager
                        .collectHomeCategories(provider)
                }.getOrDefault(emptyList())
                val processed = HomeCatalogPipeline.process(provider, categories, addonRows)
                // Empty live / soft-fail catalogs must not clear the home circuit —
                // otherwise DaddyLive scrape misses and TMDb ClassCast empties never trip it.
                if (processed.categories.isEmpty()) {
                    ProviderSmoke.noteHomeFailure(provider.name)
                    // Never wipe a good Home cache with an empty scrape — keep serving
                    // prior shelves (same as the exception path) so SWR / circuit resume.
                    if (!cachedCategories.isNullOrEmpty()) {
                        if (!isActive || providerName != UserPreferences.currentProvider?.name) return@launch
                        val emptyWarning = processed.warningText
                            ?: "Catalog empty for ${provider.name} · showing cached shelves"
                        _state.emit(
                            State.SuccessLoading(
                                cachedCategories,
                                providerWarning = emptyWarning,
                            )
                        )
                        return@launch
                    }
                    HomeCacheStore.write(appContext, provider, processed.categories)
                    if (!isActive || providerName != UserPreferences.currentProvider?.name) return@launch
                    val emptyWarning = processed.warningText
                        ?: "Catalog empty for ${provider.name}"
                    _state.emit(
                        State.SuccessLoading(
                            emptyList(),
                            providerWarning = emptyWarning,
                        )
                    )
                    return@launch
                }
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
                val db = AppDatabase.getInstanceOrNull(appContext)
                    ?: return@launch
                if (!db.isOpen) return@launch
                val moviesDeferred = async { db.movieDao().getFavorites().first() }
                val tvShowsDeferred = async { db.tvShowDao().getFavorites().first() }
                val watchingMoviesDeferred = async { db.movieDao().getWatchingMoviesCapped().first() }
                val watchingEpisodesDeferred = async { db.episodeDao().getWatchingEpisodesCapped().first() }
                val nextEpisodesDeferred = async { db.episodeDao().getNextEpisodesToWatch().first() }
                val recentMoviesDeferred = async { db.movieDao().getRecentlyWatched().first() }
                val recentTvShowsDeferred = async { db.tvShowDao().getRecentlyWatched().first() }

                val movies = moviesDeferred.await()
                val tvShows = tvShowsDeferred.await()
                val watchingMovies = watchingMoviesDeferred.await()
                val watchingEpisodes = watchingEpisodesDeferred.await()
                val nextEpisodes = nextEpisodesDeferred.await()
                val recentMovies = recentMoviesDeferred.await()
                val recentTvShows = recentTvShowsDeferred.await()

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
                    nextEpisodes = preserveCacheOrder(
                        cached = cached?.nextEpisodes ?: emptyList(),
                        incoming = nextEpisodes.map { it.toCached() },
                        idOf = { it.id },
                    ),
                    recentlyWatchedMovies = preserveCacheOrder(
                        cached = cached?.recentlyWatchedMovies ?: emptyList(),
                        incoming = recentMovies.map { it.toCached() },
                        idOf = { it.id },
                    ),
                    recentlyWatchedTvShows = preserveCacheOrder(
                        cached = cached?.recentlyWatchedTvShows ?: emptyList(),
                        incoming = recentTvShows.map { it.toCached() },
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
