package com.betterstreamflix.app.download.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterstreamflix.app.download.DownloadConnectivityMonitor
import com.betterstreamflix.app.download.DownloadController
import com.betterstreamflix.app.download.DownloadEventBridge
import com.betterstreamflix.app.download.DownloadHealth
import com.betterstreamflix.app.download.DownloadItemEntity
import com.betterstreamflix.app.download.DownloadItemState
import com.betterstreamflix.app.download.DownloadLane
import com.betterstreamflix.app.download.DownloadLaneFocus
import com.betterstreamflix.app.download.DownloadLanePolicy
import com.betterstreamflix.app.download.DownloadLaneScheduler
import com.betterstreamflix.app.download.DownloadParentalFilter
import com.betterstreamflix.app.download.DownloadRepository
import com.betterstreamflix.app.download.DownloadResumeScheduler
import com.betterstreamflix.app.download.DownloadSeasonPackEntity
import com.betterstreamflix.app.download.DownloadSeriesFolders
import com.betterstreamflix.app.download.DownloadStats
import com.betterstreamflix.app.download.DownloadStorage
import com.betterstreamflix.app.download.OfflineExpiryPolicy
import com.betterstreamflix.app.download.OfflineLibrary
import com.betterstreamflix.app.download.OfflinePlayback
import com.betterstreamflix.app.download.SmartDownloadsManager
import com.betterstreamflix.app.download.SmartDownloadsPolicy
import com.betterstreamflix.app.download.StreamflixDownloadManager
import com.betterstreamflix.app.fragments.downloads.OfflineVideoCache
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadsViewModel(
    private val appContext: Context,
) : ViewModel() {
    /** Resolve per call so profile switches reopen the scoped Room DB. */
    private fun repo() = DownloadRepository.get(appContext)
    private val filter = MutableStateFlow(DownloadsFilter.ALL)
    private val sort = MutableStateFlow(DownloadsSort.NEWEST)
    private val collapsedSeries = MutableStateFlow<Set<String>>(emptySet())
    private val profileTick = MutableStateFlow(0)
    private val viewMode = MutableStateFlow(DownloadsViewMode.LIBRARY)
    private val libraryQuery = MutableStateFlow("")
    private var boundProfileId: String = ProfileManager.activeProfileId
    private var progressJob: Job? = null
    private var reconcileJob: Job? = null

    val selectedFilter: StateFlow<DownloadsFilter> = filter.asStateFlow()
    val selectedSort: StateFlow<DownloadsSort> = sort.asStateFlow()
    val selectedViewMode: StateFlow<DownloadsViewMode> = viewMode.asStateFlow()
    val librarySearchQuery: StateFlow<String> = libraryQuery.asStateFlow()

    // Avoid walkTopDown / SimpleCache on the main thread during ViewModel init.
    val storageLabel: StateFlow<String> = MutableStateFlow("")

    val lowSpace: StateFlow<Boolean> = MutableStateFlow(false)

    init {
        refreshStorage()
    }

    private val wifiOnlyPreferenceTick = MutableStateFlow(0)
    private val studioPrefsTick = MutableStateFlow(0)

    /** Call from Downloads onResume so the Wi‑Fi-only banner tracks Settings toggles. */
    fun refreshWifiPreference() {
        wifiOnlyPreferenceTick.value = wifiOnlyPreferenceTick.value + 1
        studioPrefsTick.value = studioPrefsTick.value + 1
    }

    /** Re-read Smart / quota / soft-limit prefs after Studio Controls edits. */
    fun refreshStudioPreferences() {
        wifiOnlyPreferenceTick.value = wifiOnlyPreferenceTick.value + 1
        studioPrefsTick.value = studioPrefsTick.value + 1
    }

    /** Rebind collectors when the active profile changed since last bind. */
    fun ensureProfileScope() {
        val active = ProfileManager.activeProfileId
        if (active == boundProfileId) return
        boundProfileId = active
        collapsedSeries.value = emptySet()
        OfflineVideoCache.clear()
        profileTick.value = profileTick.value + 1
        refreshStorage()
    }

    val wifiOnlyEnabled: StateFlow<Boolean> = wifiOnlyPreferenceTick.map {
        UserPreferences.downloadWifiOnly
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences.downloadWifiOnly)

    val wifiPaused: StateFlow<Boolean> = combine(
        DownloadConnectivityMonitor.status,
        wifiOnlyPreferenceTick,
    ) { _, _ ->
        DownloadConnectivityMonitor.shouldHoldForWifiOnly(appContext)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Unplayable / cache-missing count for the Downloads health banner. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val healthSummary: StateFlow<DownloadHealth.Summary> = profileTick
        .flatMapLatest { repo().observeForActiveProfile() }
        .mapLatest { items ->
            DownloadHealth.summarize(appContext, applyParentalFilter(items))
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DownloadHealth.Summary(0, 0, 0, emptyList()),
        )

    /**
     * Offline Studio aggregate for Care + Controls + status rail.
     * Rebuilds when Room rows, packs, Studio prefs, or Lane focus change.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val studioSnapshot: StateFlow<OfflineStudioSnapshot> = profileTick
        .flatMapLatest {
            combine(
                repo().observeForActiveProfile(),
                repo().observeSeasonPacks(),
                studioPrefsTick,
                DownloadLaneFocus.tick,
            ) { items, packs, _, _ ->
                items to packs
            }
        }
        .mapLatest { (items, packs) ->
            val parental = applyParentalFilter(items)
            OfflineStudioSnapshot.build(
                items = parental,
                packs = filterPacksForParental(packs, parental),
                smartEnabled = UserPreferences.downloadSmartEnabled,
                smartLookahead = UserPreferences.downloadSmartLookahead,
                smartSeriesQuota = UserPreferences.downloadSmartSeriesQuota,
                autoDeleteWatched = UserPreferences.downloadAutoDeleteWatched,
                wifiOnly = UserPreferences.downloadWifiOnly,
                softLimitGb = UserPreferences.downloadSoftLimitGb,
                maxConcurrent = UserPreferences.downloadMaxConcurrent,
                watchingFocus = DownloadLaneFocus.current(),
                careExpiryAllowed = OfflineExpiryPolicy.shouldPrompt(),
                context = appContext,
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            OfflineStudioSnapshot.build(
                items = emptyList(),
                packs = emptyList(),
                smartEnabled = UserPreferences.downloadSmartEnabled,
                smartLookahead = UserPreferences.downloadSmartLookahead,
                smartSeriesQuota = UserPreferences.downloadSmartSeriesQuota,
                autoDeleteWatched = UserPreferences.downloadAutoDeleteWatched,
                wifiOnly = UserPreferences.downloadWifiOnly,
                softLimitGb = UserPreferences.downloadSoftLimitGb,
                maxConcurrent = UserPreferences.downloadMaxConcurrent,
                careExpiryAllowed = OfflineExpiryPolicy.shouldPrompt(),
            ),
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: StateFlow<List<DownloadRowUiModel>> = profileTick
        .flatMapLatest {
            combine(
                repo().observeForActiveProfile(),
                repo().observeSeasonPacks(),
                combine(filter, sort, collapsedSeries) { f, s, collapsed ->
                    Triple(f, s, collapsed)
                },
                DownloadLaneFocus.tick,
            ) { items, packs, filters, _ ->
                val (selectedFilter, selectedSort, collapsed) = filters
                Quad(items, packs, selectedFilter, selectedSort, collapsed)
            }
        }
        .mapLatest { q ->
            val filteredItems = applyParentalFilter(q.items)
            val packs = filterPacksForParental(q.packs, filteredItems)
            buildRows(filteredItems, packs, q.filter, q.sort, q.collapsed)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Completed, playable titles for the Offline-Bibliothek poster grid.
     * Local title filter only — never hits providers or TMDb.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val libraryEntries: StateFlow<List<OfflineLibrary.Entry>> = profileTick
        .flatMapLatest {
            combine(repo().observeForActiveProfile(), libraryQuery) { items, query -> items to query }
        }
        .mapLatest { (items, query) ->
            val parental = applyParentalFilter(items)
            val providerFilter = UserPreferences.downloadFilterCurrentProvider
            val providerName = UserPreferences.currentProvider?.name
            val completed = parental
                .groupBy { it.contentKey }
                .map { (_, rows) -> rows.maxBy { it.updatedAt } }
                .filter { it.state == DownloadItemState.COMPLETED.name }
                .filter { !providerFilter || providerName.isNullOrBlank() || it.providerName == providerName }
                .filter { OfflinePlayback.canPlayOffline(appContext, it) }
            OfflineLibrary.filter(OfflineLibrary.build(completed), query)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setViewMode(value: DownloadsViewMode) {
        viewMode.value = value
    }

    fun setLibraryQuery(value: String) {
        libraryQuery.value = value
    }

    fun currentViewMode(): DownloadsViewMode = viewMode.value

    fun setFilter(value: DownloadsFilter) {
        filter.value = value
    }

    fun setSort(value: DownloadsSort) {
        sort.value = value
    }

    fun toggleSeriesFolder(seriesKey: String) {
        collapsedSeries.value = collapsedSeries.value.toMutableSet().also { set ->
            if (!set.add(seriesKey)) set.remove(seriesKey)
        }
    }

    fun currentFilter(): DownloadsFilter = filter.value
    fun currentSort(): DownloadsSort = sort.value

    fun setWifiOnly(enabled: Boolean) {
        if (UserPreferences.downloadWifiOnly == enabled) {
            refreshWifiPreference()
            return
        }
        UserPreferences.downloadWifiOnly = enabled
        refreshWifiPreference()
        StreamflixDownloadManager.applyWifiOnlyRequirements(appContext)
        DownloadResumeScheduler.enqueue(appContext, replace = true)
        viewModelScope.launch {
            if (enabled && DownloadConnectivityMonitor.shouldHoldForWifiOnly(appContext)) {
                repo().pauseForConnectivity()
            } else if (!enabled) {
                repo().resumeConnectivityHeld()
            }
            DownloadLaneScheduler.apply(appContext)
        }
    }

    fun setSmartEnabled(enabled: Boolean) {
        UserPreferences.downloadSmartEnabled = enabled
        refreshStudioPreferences()
        if (enabled) {
            viewModelScope.launch {
                runCatching { SmartDownloadsManager.sweepAfterEnable(appContext) }
            }
        }
    }

    fun setSmartLookahead(value: Int) {
        UserPreferences.downloadSmartLookahead = SmartDownloadsPolicy.clampLookahead(value)
        refreshStudioPreferences()
    }

    fun bumpSmartLookahead(delta: Int) {
        setSmartLookahead(UserPreferences.downloadSmartLookahead + delta)
    }

    fun setSmartSeriesQuota(value: Int) {
        UserPreferences.downloadSmartSeriesQuota = SmartDownloadsPolicy.clampSeriesQuota(value)
        refreshStudioPreferences()
    }

    fun bumpSmartSeriesQuota(delta: Int) {
        setSmartSeriesQuota(UserPreferences.downloadSmartSeriesQuota + delta)
    }

    fun setAutoDeleteWatched(enabled: Boolean) {
        UserPreferences.downloadAutoDeleteWatched = enabled
        refreshStudioPreferences()
    }

    fun setSoftLimitGb(value: Int) {
        UserPreferences.downloadSoftLimitGb = value.coerceIn(0, 500)
        refreshStudioPreferences()
        refreshStorage()
        viewModelScope.launch {
            if (!DownloadStorage.isOverSoftLimit(appContext) &&
                !DownloadStorage.isLowSpace(appContext)
            ) {
                repo().resumeStorageHeld()
            }
            DownloadLaneScheduler.apply(appContext)
        }
    }

    fun bumpSoftLimitGb(delta: Int) {
        val step = if (delta > 0) 5 else -5
        setSoftLimitGb(UserPreferences.downloadSoftLimitGb + step)
    }

    fun setMaxConcurrent(value: Int) {
        UserPreferences.downloadMaxConcurrent = value.coerceIn(1, 4)
        refreshStudioPreferences()
        viewModelScope.launch { DownloadLaneScheduler.apply(appContext) }
    }

    fun bumpMaxConcurrent(delta: Int) {
        setMaxConcurrent(UserPreferences.downloadMaxConcurrent + delta)
    }

    /** Poll Media3 while the Downloads screen is resumed so progress/speed stay live. */
    fun startLiveProgress() {
        if (progressJob?.isActive == true) return
        reconcileOrphanedCompleted()
        progressJob = viewModelScope.launch {
            // Warm SimpleCache / OpenHelper off the main thread (ANR / File IO).
            runCatching { withContext(Dispatchers.IO) { StreamflixDownloadManager.get(appContext) } }
            while (isActive) {
                runCatching {
                    withContext(Dispatchers.IO) { DownloadEventBridge.syncAllActive(appContext) }
                }
                refreshStorage()
                delay(PROGRESS_POLL_MS)
            }
        }
    }

    fun stopLiveProgress() {
        progressJob?.cancel()
        progressJob = null
    }

    fun reconcileOrphanedCompleted() {
        if (reconcileJob?.isActive == true) return
        reconcileJob = viewModelScope.launch {
            runCatching { repo().reconcileOrphanedCompleted() }
        }
    }

    fun refreshStorage() {
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) {
                DownloadStats.storageSummary(appContext)
            }
            val low = withContext(Dispatchers.IO) {
                DownloadStorage.isLowSpace(appContext)
            }
            (storageLabel as MutableStateFlow).value = summary
            (lowSpace as MutableStateFlow).value = low
        }
    }

    fun pause(id: String) = viewModelScope.launch { repo().pause(id) }
    fun resume(id: String) = viewModelScope.launch { repo().resume(id) }
    fun remove(id: String) = viewModelScope.launch {
        repo().remove(id)
        refreshStorage()
    }

    /** Keep = bump updatedAt so the 30-day untouched clock resets. */
    fun touchDownloads(ids: Collection<String>) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        ids.forEach { id ->
            val item = repo().getById(id) ?: return@forEach
            repo().upsert(item.copy(updatedAt = now))
        }
        refreshStorage()
    }

    fun removeDownloads(ids: Collection<String>) = viewModelScope.launch {
        ids.forEach { id -> repo().remove(id) }
        refreshStorage()
    }

    /**
     * Stale COMPLETED downloads for the Offline Expiry Nudge (empty when cool-down / kids).
     */
    suspend fun loadOfflineExpiryCandidates(): List<DownloadItemEntity> {
        if (!OfflineExpiryPolicy.shouldPrompt()) return emptyList()
        val all = applyParentalFilter(
            repo().getAllOnce().filter {
                com.betterstreamflix.app.download.DownloadProfileScope.matchesActive(it.profileId)
            },
        )
        return OfflineExpiryPolicy.selectStale(all, max = OfflineExpiryPolicy.MAX_BATCH)
    }
    fun pauseAll() = viewModelScope.launch { repo().pauseAll() }
    fun resumeAll() = viewModelScope.launch { repo().resumeAll() }
    fun clearCompleted() = viewModelScope.launch {
        repo().clearCompleted()
        refreshStorage()
    }
    fun clearFailed() = viewModelScope.launch {
        repo().clearFailed()
        refreshStorage()
    }
    fun clearWatched() = viewModelScope.launch {
        repo().clearWatched()
        refreshStorage()
    }
    fun retryAllFailed() = viewModelScope.launch {
        DownloadController.retryAllFailed(appContext)
        refreshStorage()
    }

    fun redownloadAllUnhealthy() = viewModelScope.launch {
        val ids = healthSummary.value.ids
        DownloadController.redownloadAllUnhealthy(appContext, onlyIds = ids)
        refreshStorage()
    }
    fun pausePack(packId: String) = viewModelScope.launch { repo().pausePack(packId) }
    fun resumePack(packId: String) = viewModelScope.launch { repo().resumePack(packId) }
    fun removePack(packId: String) = viewModelScope.launch {
        repo().removePack(packId)
        refreshStorage()
    }

    suspend fun isPlayAllowed(entity: DownloadItemEntity): Boolean =
        DownloadParentalFilter.isAllowed(entity)

    override fun onCleared() {
        stopLiveProgress()
        reconcileJob?.cancel()
        super.onCleared()
    }

    private suspend fun applyParentalFilter(items: List<DownloadItemEntity>): List<DownloadItemEntity> =
        DownloadParentalFilter.filter(items)

    /** Hide packs whose every linked episode is parental-hidden. */
    private fun filterPacksForParental(
        packs: List<DownloadSeasonPackEntity>,
        visibleItems: List<DownloadItemEntity>,
    ): List<DownloadSeasonPackEntity> {
        val visiblePackIds = visibleItems.mapNotNull { it.seasonPackId }.toHashSet()
        return packs.filter { it.id in visiblePackIds }
    }

    private fun buildRows(
        items: List<DownloadItemEntity>,
        packs: List<DownloadSeasonPackEntity>,
        selectedFilter: DownloadsFilter,
        selectedSort: DownloadsSort,
        collapsed: Set<String>,
    ): List<DownloadRowUiModel> {
        val providerFilter = UserPreferences.downloadFilterCurrentProvider
        val providerName = UserPreferences.currentProvider?.name
        var filtered = items
            .groupBy { it.contentKey }
            .map { (_, rows) -> rows.maxBy { it.updatedAt } }
        if (providerFilter && !providerName.isNullOrBlank()) {
            filtered = filtered.filter { it.providerName == providerName }
        }
        filtered = when (selectedFilter) {
            DownloadsFilter.ALL -> filtered
            DownloadsFilter.DOWNLOADING -> filtered.filter {
                DownloadItemState.fromKey(it.state).isActive
            }
            DownloadsFilter.COMPLETED -> filtered.filter {
                it.state == DownloadItemState.COMPLETED.name
            }
            DownloadsFilter.FAILED -> filtered.filter {
                it.state == DownloadItemState.FAILED.name
            }
        }
        val watchingFocus = DownloadLaneFocus.current()
        val sorter: Comparator<DownloadItemEntity> =
            when (selectedSort) {
                // Download Lane: watching > queue > smart-auto, then newest.
                DownloadsSort.NEWEST -> compareBy<DownloadItemEntity> {
                    DownloadLanePolicy.laneOf(it, watchingFocus).rank
                }.thenByDescending { it.createdAt }.thenBy { it.sortIndex }
                DownloadsSort.TITLE -> compareBy<DownloadItemEntity> {
                    DownloadLanePolicy.laneOf(it, watchingFocus).rank
                }.thenBy { it.title.lowercase() }.thenBy { it.sortIndex }
                DownloadsSort.SIZE -> compareBy<DownloadItemEntity> {
                    DownloadLanePolicy.laneOf(it, watchingFocus).rank
                }.thenByDescending {
                    maxOf(it.contentLength, it.bytesDownloaded)
                }
            }
        val active = filtered
            .filter { DownloadItemState.fromKey(it.state).isActive }
            .sortedWith(sorter)
        val completed = filtered
            .filter { it.state == DownloadItemState.COMPLETED.name }
            .sortedWith(sorter)
        val redownload = filtered
            .filter { DownloadSeriesFolders.needsRedownload(it) }
            .sortedWith(sorter)
        val failed = filtered
            .filter {
                it.state == DownloadItemState.FAILED.name &&
                    !DownloadSeriesFolders.needsRedownload(it)
            }
            .sortedWith(sorter)
        // Cross-section series counts so Active+Completed folders agree (DL-P3-G).
        val seriesTotals = filtered
            .mapNotNull { item -> DownloadSeriesFolders.seriesRef(item)?.let { it.key to item } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, eps) ->
                eps.size to eps.sumOf { maxOf(it.contentLength, it.bytesDownloaded) }
            }
        return buildList {
            val relevantPacks = packs.filter { pack ->
                filtered.any { it.seasonPackId == pack.id }
            }
            if (active.isNotEmpty()) {
                val watching = active.filter {
                    DownloadLanePolicy.laneOf(it, watchingFocus) == DownloadLane.WATCHING
                }
                val queueLane = active.filter {
                    DownloadLanePolicy.laneOf(it, watchingFocus) == DownloadLane.QUEUE
                }
                val smartLane = active.filter {
                    DownloadLanePolicy.laneOf(it, watchingFocus) == DownloadLane.SMART_AUTO
                }
                if (watching.isNotEmpty()) {
                    add(
                        DownloadRowUiModel.Header(
                            appContext.getString(
                                com.betterstreamflix.app.R.string.offline_studio_lane_watching,
                            ),
                        ),
                    )
                    addGroupedBySeries(watching, collapsed, seriesTotals)
                }
                if (queueLane.isNotEmpty()) {
                    add(
                        DownloadRowUiModel.Header(
                            appContext.getString(
                                com.betterstreamflix.app.R.string.offline_studio_lane_queue,
                            ),
                        ),
                    )
                    addGroupedBySeries(queueLane, collapsed, seriesTotals)
                }
                if (smartLane.isNotEmpty()) {
                    add(
                        DownloadRowUiModel.Header(
                            appContext.getString(
                                com.betterstreamflix.app.R.string.offline_studio_lane_smart,
                            ),
                        ),
                    )
                    addGroupedBySeries(smartLane, collapsed, seriesTotals)
                }
            }
            if (relevantPacks.isNotEmpty() && selectedFilter != DownloadsFilter.FAILED) {
                add(DownloadRowUiModel.Header(appContext.getString(com.betterstreamflix.app.R.string.downloads_section_seasons)))
                relevantPacks.forEach { add(DownloadRowUiModel.SeasonPack(it)) }
            }
            if (completed.isNotEmpty()) {
                add(DownloadRowUiModel.Header(appContext.getString(com.betterstreamflix.app.R.string.downloads_section_completed)))
                addGroupedBySeries(completed, collapsed, seriesTotals)
            }
            if (redownload.isNotEmpty()) {
                add(
                    DownloadRowUiModel.Header(
                        appContext.getString(
                            com.betterstreamflix.app.R.string.downloads_section_redownload_count,
                            redownload.size,
                        ),
                    ),
                )
                addGroupedBySeries(redownload, collapsed, seriesTotals)
            }
            if (failed.isNotEmpty()) {
                add(DownloadRowUiModel.Header(appContext.getString(com.betterstreamflix.app.R.string.downloads_section_failed)))
                addGroupedBySeries(failed, collapsed, seriesTotals)
            }
        }
    }

    private fun MutableList<DownloadRowUiModel>.addGroupedBySeries(
        items: List<DownloadItemEntity>,
        collapsed: Set<String>,
        seriesTotals: Map<String, Pair<Int, Long>>,
    ) {
        val movies = items.filter { DownloadSeriesFolders.seriesRef(it) == null }
        val bySeries = items
            .mapNotNull { item -> DownloadSeriesFolders.seriesRef(item)?.let { it to item } }
            .groupBy({ it.first.key }, { it })
        // Stable: series folders by title, then loose movies/items.
        bySeries.entries
            .sortedBy { it.value.first().first.title.lowercase() }
            .forEach { (_, pairs) ->
                val ref = pairs.first().first
                val episodes = pairs.map { it.second }
                val (count, bytes) = seriesTotals[ref.key]
                    ?: (episodes.size to episodes.sumOf { maxOf(it.contentLength, it.bytesDownloaded) })
                val expanded = ref.key !in collapsed
                add(
                    DownloadRowUiModel.SeriesFolder(
                        seriesKey = ref.key,
                        title = ref.title,
                        episodeCount = count,
                        totalBytes = bytes,
                        posterUrl = ref.posterUrl,
                        expanded = expanded,
                    ),
                )
                if (expanded) {
                    episodes.forEach { add(DownloadRowUiModel.Item(it)) }
                }
            }
        movies.forEach { add(DownloadRowUiModel.Item(it)) }
    }

    private data class Quad(
        val items: List<DownloadItemEntity>,
        val packs: List<DownloadSeasonPackEntity>,
        val filter: DownloadsFilter,
        val sort: DownloadsSort,
        val collapsed: Set<String>,
    )

    companion object {
        private const val PROGRESS_POLL_MS = 500L
    }
}
