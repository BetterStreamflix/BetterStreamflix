package com.dskja.betterstreamflix.download.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskja.betterstreamflix.download.DownloadConnectivityMonitor
import com.dskja.betterstreamflix.download.DownloadController
import com.dskja.betterstreamflix.download.DownloadEventBridge
import com.dskja.betterstreamflix.download.DownloadItemEntity
import com.dskja.betterstreamflix.download.DownloadItemState
import com.dskja.betterstreamflix.download.DownloadKind
import com.dskja.betterstreamflix.download.DownloadLaneFocus
import com.dskja.betterstreamflix.download.DownloadLanePolicy
import com.dskja.betterstreamflix.download.DownloadRepository
import com.dskja.betterstreamflix.download.DownloadSeriesFolders
import com.dskja.betterstreamflix.download.DownloadStats
import com.dskja.betterstreamflix.download.DownloadStorage
import com.dskja.betterstreamflix.download.OfflineExpiryPolicy
import com.dskja.betterstreamflix.download.OfflineLibrary
import com.dskja.betterstreamflix.download.OfflinePlayback
import com.dskja.betterstreamflix.download.StreamflixDownloadManager
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.ParentalControlUtils
import com.dskja.betterstreamflix.utils.UserPreferences
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
import org.json.JSONObject

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

    val storageLabel: StateFlow<String> = MutableStateFlow(
        DownloadStats.storageSummary(appContext),
    )

    val lowSpace: StateFlow<Boolean> = MutableStateFlow(DownloadStorage.isLowSpace(appContext))

    private val wifiOnlyPreferenceTick = MutableStateFlow(0)

    /** Call from Downloads onResume so the Wi‑Fi-only banner tracks Settings toggles. */
    fun refreshWifiPreference() {
        wifiOnlyPreferenceTick.value = wifiOnlyPreferenceTick.value + 1
    }

    /** Rebind collectors when the active profile changed since last bind. */
    fun ensureProfileScope() {
        val active = ProfileManager.activeProfileId
        if (active == boundProfileId) return
        boundProfileId = active
        collapsedSeries.value = emptySet()
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

    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: StateFlow<List<DownloadRowUiModel>> = profileTick
        .flatMapLatest {
            combine(
                repo().observeAll(),
                repo().observeSeasonPacks(),
                combine(filter, sort, collapsedSeries) { f, s, collapsed ->
                    Triple(f, s, collapsed)
                },
            ) { items, packs, filters ->
                val (selectedFilter, selectedSort, collapsed) = filters
                Quad(items, packs, selectedFilter, selectedSort, collapsed)
            }
        }
        .mapLatest { q ->
            val filteredItems = applyParentalFilter(q.items)
            buildRows(filteredItems, q.packs, q.filter, q.sort, q.collapsed)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Completed, playable titles for the Offline-Bibliothek poster grid.
     * Local title filter only — never hits providers or TMDb.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val libraryEntries: StateFlow<List<OfflineLibrary.Entry>> = profileTick
        .flatMapLatest {
            combine(repo().observeAll(), libraryQuery) { items, query -> items to query }
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
        viewModelScope.launch {
            if (enabled && DownloadConnectivityMonitor.shouldHoldForWifiOnly(appContext)) {
                repo().pauseForConnectivity()
            } else if (!enabled) {
                repo().resumeConnectivityHeld()
            }
        }
    }

    /** Poll Media3 while the Downloads screen is resumed so progress/speed stay live. */
    fun startLiveProgress() {
        if (progressJob?.isActive == true) return
        // Warm the download manager / listener in this process.
        StreamflixDownloadManager.get(appContext)
        reconcileOrphanedCompleted()
        progressJob = viewModelScope.launch {
            while (isActive) {
                runCatching { DownloadEventBridge.syncAllActive(appContext) }
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
        (storageLabel as MutableStateFlow).value = DownloadStats.storageSummary(appContext)
        (lowSpace as MutableStateFlow).value = DownloadStorage.isLowSpace(appContext)
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
        val all = applyParentalFilter(repo().getAllOnce())
        return OfflineExpiryPolicy.selectStale(all)
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
    fun pausePack(packId: String) = viewModelScope.launch { repo().pausePack(packId) }
    fun resumePack(packId: String) = viewModelScope.launch { repo().resumePack(packId) }
    fun removePack(packId: String) = viewModelScope.launch {
        repo().removePack(packId)
        refreshStorage()
    }

    suspend fun isPlayAllowed(entity: DownloadItemEntity): Boolean {
        if (!ProfileManager.isKidsActive() && !UserPreferences.isParentalControlActive) return true
        val item = toFilterable(entity) ?: return !ProfileManager.isKidsActive()
        return ParentalControlUtils.filterItems(listOf(item)).isNotEmpty()
    }

    override fun onCleared() {
        stopLiveProgress()
        reconcileJob?.cancel()
        super.onCleared()
    }

    private suspend fun applyParentalFilter(items: List<DownloadItemEntity>): List<DownloadItemEntity> {
        if (!ProfileManager.isKidsActive() && !UserPreferences.isParentalControlActive) return items
        return items.filter { entity ->
            val item = toFilterable(entity) ?: return@filter !ProfileManager.isKidsActive()
            ParentalControlUtils.filterItems(listOf(item)).isNotEmpty()
        }
    }

    private fun toFilterable(entity: DownloadItemEntity): com.dskja.betterstreamflix.adapters.AppAdapter.Item? {
        return when (entity.kind) {
            DownloadKind.MOVIE.name -> {
                val id = entity.contentKey.split('|').getOrNull(2) ?: entity.id
                Movie(
                    id = id,
                    title = entity.title,
                    poster = entity.posterUrl.takeIf { it.isNotBlank() },
                    providerName = entity.providerName,
                )
            }
            DownloadKind.EPISODE.name -> {
                val o = runCatching {
                    JSONObject(entity.videoTypeJson.ifBlank { "{}" })
                }.getOrNull()
                val showId = o?.optString("showId")?.takeIf { it.isNotBlank() }
                    ?: entity.contentKey.split('|').getOrNull(2)
                    ?: return null
                val showTitle = o?.optString("showTitle")?.takeIf { it.isNotBlank() }
                    ?: entity.title
                val showPoster = o?.optString("showPoster")?.takeIf { it.isNotBlank() }
                    ?: entity.posterUrl.takeIf { it.isNotBlank() }
                Episode(
                    id = entity.contentKey.split('|').lastOrNull() ?: entity.id,
                    number = o?.optInt("number", 1) ?: 1,
                    title = entity.title,
                    poster = entity.posterUrl.takeIf { it.isNotBlank() },
                    tvShow = TvShow(
                        id = showId,
                        title = showTitle,
                        poster = showPoster,
                        providerName = entity.providerName,
                    ),
                    season = Season(
                        id = "",
                        number = o?.optInt("seasonNumber", 1) ?: 1,
                        title = "",
                    ),
                )
            }
            else -> null
        }
    }

    private fun buildRows(
        items: List<DownloadItemEntity>,
        packs: List<com.dskja.betterstreamflix.download.DownloadSeasonPackEntity>,
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
                DownloadsSort.TITLE -> compareBy(
                    { it.title.lowercase() },
                    { it.sortIndex },
                )
                DownloadsSort.SIZE -> compareByDescending {
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
                add(DownloadRowUiModel.Header(appContext.getString(com.dskja.betterstreamflix.R.string.downloads_section_active)))
                addGroupedBySeries(active, collapsed, seriesTotals)
            }
            if (relevantPacks.isNotEmpty() && selectedFilter != DownloadsFilter.FAILED) {
                add(DownloadRowUiModel.Header(appContext.getString(com.dskja.betterstreamflix.R.string.downloads_section_seasons)))
                relevantPacks.forEach { add(DownloadRowUiModel.SeasonPack(it)) }
            }
            if (completed.isNotEmpty()) {
                add(DownloadRowUiModel.Header(appContext.getString(com.dskja.betterstreamflix.R.string.downloads_section_completed)))
                addGroupedBySeries(completed, collapsed, seriesTotals)
            }
            if (redownload.isNotEmpty()) {
                add(
                    DownloadRowUiModel.Header(
                        appContext.getString(com.dskja.betterstreamflix.R.string.downloads_section_redownload),
                    ),
                )
                addGroupedBySeries(redownload, collapsed, seriesTotals)
            }
            if (failed.isNotEmpty()) {
                add(DownloadRowUiModel.Header(appContext.getString(com.dskja.betterstreamflix.R.string.downloads_section_failed)))
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
        val packs: List<com.dskja.betterstreamflix.download.DownloadSeasonPackEntity>,
        val filter: DownloadsFilter,
        val sort: DownloadsSort,
        val collapsed: Set<String>,
    )

    companion object {
        private const val PROGRESS_POLL_MS = 500L
    }
}
