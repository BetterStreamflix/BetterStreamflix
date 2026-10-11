package com.dskja.betterstreamflix.download.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskja.betterstreamflix.download.DownloadConnectivityMonitor
import com.dskja.betterstreamflix.download.DownloadController
import com.dskja.betterstreamflix.download.DownloadEventBridge
import com.dskja.betterstreamflix.download.DownloadItemEntity
import com.dskja.betterstreamflix.download.DownloadItemState
import com.dskja.betterstreamflix.download.DownloadRepository
import com.dskja.betterstreamflix.download.DownloadSeriesFolders
import com.dskja.betterstreamflix.download.DownloadStats
import com.dskja.betterstreamflix.download.DownloadStorage
import com.dskja.betterstreamflix.download.StreamflixDownloadManager
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DownloadsViewModel(
    private val appContext: Context,
) : ViewModel() {
    private val repo = DownloadRepository.get(appContext)
    private val filter = MutableStateFlow(DownloadsFilter.ALL)
    private val sort = MutableStateFlow(DownloadsSort.NEWEST)
    private var progressJob: Job? = null
    private var reconcileJob: Job? = null

    val selectedFilter: StateFlow<DownloadsFilter> = filter.asStateFlow()
    val selectedSort: StateFlow<DownloadsSort> = sort.asStateFlow()

    val storageLabel: StateFlow<String> = MutableStateFlow(
        DownloadStats.storageSummary(appContext),
    )

    val lowSpace: StateFlow<Boolean> = MutableStateFlow(DownloadStorage.isLowSpace(appContext))

    private val wifiOnlyPreferenceTick = MutableStateFlow(0)

    /** Call from Downloads onResume so the Wi‑Fi-only banner tracks Settings toggles. */
    fun refreshWifiPreference() {
        wifiOnlyPreferenceTick.value = wifiOnlyPreferenceTick.value + 1
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

    val rows: StateFlow<List<DownloadRowUiModel>> = combine(
        repo.observeAll(),
        repo.observeSeasonPacks(),
        combine(filter, sort) { f, s -> f to s },
    ) { items, packs, (selectedFilter, selectedSort) ->
        buildRows(items, packs, selectedFilter, selectedSort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(value: DownloadsFilter) {
        filter.value = value
    }

    fun setSort(value: DownloadsSort) {
        sort.value = value
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
                repo.pauseForConnectivity()
            } else if (!enabled) {
                repo.resumeConnectivityHeld()
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
            runCatching { repo.reconcileOrphanedCompleted() }
        }
    }

    fun refreshStorage() {
        (storageLabel as MutableStateFlow).value = DownloadStats.storageSummary(appContext)
        (lowSpace as MutableStateFlow).value = DownloadStorage.isLowSpace(appContext)
    }

    fun pause(id: String) = viewModelScope.launch { repo.pause(id) }
    fun resume(id: String) = viewModelScope.launch { repo.resume(id) }
    fun remove(id: String) = viewModelScope.launch {
        repo.remove(id)
        refreshStorage()
    }
    fun pauseAll() = viewModelScope.launch { repo.pauseAll() }
    fun resumeAll() = viewModelScope.launch { repo.resumeAll() }
    fun clearCompleted() = viewModelScope.launch {
        repo.clearCompleted()
        refreshStorage()
    }
    fun clearFailed() = viewModelScope.launch {
        repo.clearFailed()
        refreshStorage()
    }
    fun clearWatched() = viewModelScope.launch {
        repo.clearWatched()
        refreshStorage()
    }
    fun retryAllFailed() = viewModelScope.launch {
        DownloadController.retryAllFailed(appContext)
        refreshStorage()
    }
    fun pausePack(packId: String) = viewModelScope.launch { repo.pausePack(packId) }
    fun resumePack(packId: String) = viewModelScope.launch { repo.resumePack(packId) }
    fun removePack(packId: String) = viewModelScope.launch {
        repo.removePack(packId)
        refreshStorage()
    }

    override fun onCleared() {
        stopLiveProgress()
        reconcileJob?.cancel()
        super.onCleared()
    }

    private fun buildRows(
        items: List<DownloadItemEntity>,
        packs: List<com.dskja.betterstreamflix.download.DownloadSeasonPackEntity>,
        selectedFilter: DownloadsFilter,
        selectedSort: DownloadsSort,
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
        val sorter: Comparator<DownloadItemEntity> =
            when (selectedSort) {
                DownloadsSort.NEWEST -> compareByDescending<DownloadItemEntity> { it.createdAt }
                    .thenBy { it.sortIndex }
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
        return buildList {
            val relevantPacks = packs.filter { pack ->
                filtered.any { it.seasonPackId == pack.id }
            }
            if (active.isNotEmpty()) {
                add(DownloadRowUiModel.Header(appContext.getString(com.dskja.betterstreamflix.R.string.downloads_section_active)))
                addGroupedBySeries(active)
            }
            if (relevantPacks.isNotEmpty() && selectedFilter != DownloadsFilter.FAILED) {
                add(DownloadRowUiModel.Header(appContext.getString(com.dskja.betterstreamflix.R.string.downloads_section_seasons)))
                relevantPacks.forEach { add(DownloadRowUiModel.SeasonPack(it)) }
            }
            if (completed.isNotEmpty()) {
                add(DownloadRowUiModel.Header(appContext.getString(com.dskja.betterstreamflix.R.string.downloads_section_completed)))
                addGroupedBySeries(completed)
            }
            if (redownload.isNotEmpty()) {
                add(
                    DownloadRowUiModel.Header(
                        appContext.getString(com.dskja.betterstreamflix.R.string.downloads_section_redownload),
                    ),
                )
                addGroupedBySeries(redownload)
            }
            if (failed.isNotEmpty()) {
                add(DownloadRowUiModel.Header(appContext.getString(com.dskja.betterstreamflix.R.string.downloads_section_failed)))
                addGroupedBySeries(failed)
            }
        }
    }

    private fun MutableList<DownloadRowUiModel>.addGroupedBySeries(items: List<DownloadItemEntity>) {
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
                val totalBytes = episodes.sumOf { maxOf(it.contentLength, it.bytesDownloaded) }
                add(
                    DownloadRowUiModel.SeriesFolder(
                        seriesKey = ref.key,
                        title = ref.title,
                        episodeCount = episodes.size,
                        totalBytes = totalBytes,
                        posterUrl = ref.posterUrl,
                    ),
                )
                episodes.forEach { add(DownloadRowUiModel.Item(it)) }
            }
        movies.forEach { add(DownloadRowUiModel.Item(it)) }
    }

    companion object {
        private const val PROGRESS_POLL_MS = 500L
    }
}
