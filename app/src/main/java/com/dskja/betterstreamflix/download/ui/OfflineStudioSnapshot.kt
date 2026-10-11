package com.dskja.betterstreamflix.download.ui

import com.dskja.betterstreamflix.download.DownloadHealth
import com.dskja.betterstreamflix.download.DownloadItemEntity
import com.dskja.betterstreamflix.download.DownloadItemState
import com.dskja.betterstreamflix.download.DownloadLane
import com.dskja.betterstreamflix.download.DownloadLaneFocus
import com.dskja.betterstreamflix.download.DownloadLanePolicy
import com.dskja.betterstreamflix.download.DownloadSeasonPackEntity
import com.dskja.betterstreamflix.download.OfflineExpiryPolicy
import com.dskja.betterstreamflix.download.SmartDownloadsPolicy

/**
 * Pure Offline Studio hub snapshot — aggregates Lane / Health / Expiry / Packs / Smart quota
 * for Care + Controls surfaces without touching the download engine.
 */
data class OfflineStudioSnapshot(
    val activeCount: Int,
    val watchingLaneCount: Int,
    val queueLaneCount: Int,
    val smartLaneCount: Int,
    val completedCount: Int,
    val packCount: Int,
    val health: DownloadHealth.Summary,
    val expiryCount: Int,
    val expiryTitles: List<String>,
    val expiryIds: List<String>,
    val smartEnabled: Boolean,
    val smartLookahead: Int,
    val smartSeriesQuota: Int,
    val autoDeleteWatched: Boolean,
    val wifiOnly: Boolean,
    val softLimitGb: Int,
    val maxConcurrent: Int,
    /** Sum of series-quota occupancy across all series (approximate load). */
    val seriesQuotaOccupied: Int,
    val seriesWithQuota: Int,
) {
    val hasCareWork: Boolean
        get() = health.hasUnhealthy || expiryCount > 0

    companion object {
        fun build(
            items: List<DownloadItemEntity>,
            packs: List<DownloadSeasonPackEntity>,
            smartEnabled: Boolean,
            smartLookahead: Int,
            smartSeriesQuota: Int,
            autoDeleteWatched: Boolean,
            wifiOnly: Boolean,
            softLimitGb: Int,
            maxConcurrent: Int,
            nowMs: Long = System.currentTimeMillis(),
            watchingFocus: DownloadLanePolicy.WatchingFocus? = DownloadLaneFocus.current(),
        ): OfflineStudioSnapshot {
            val unique = items
                .groupBy { it.contentKey }
                .map { (_, rows) -> rows.maxBy { it.updatedAt } }
            val active = unique.filter { DownloadItemState.fromKey(it.state).isActive }
            val focus = watchingFocus
            var watching = 0
            var queue = 0
            var smart = 0
            active.forEach { item ->
                when (DownloadLanePolicy.laneOf(item, focus)) {
                    DownloadLane.WATCHING -> watching++
                    DownloadLane.QUEUE -> queue++
                    DownloadLane.SMART_AUTO -> smart++
                }
            }
            val health = DownloadHealth.summarize(unique)
            val stale = OfflineExpiryPolicy.selectStale(unique, nowMs = nowMs, max = 20)
            val (titles, _) = OfflineExpiryPolicy.formatTitleLines(stale)
            val occupancyBySeries = unique
                .mapNotNull { item ->
                    SmartDownloadsPolicy.seriesKeyOf(item)?.let { key ->
                        key to SmartDownloadsPolicy.countsTowardSeriesQuota(item)
                    }
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, flags) -> flags.count { it } }
            return OfflineStudioSnapshot(
                activeCount = active.size,
                watchingLaneCount = watching,
                queueLaneCount = queue,
                smartLaneCount = smart,
                completedCount = unique.count { it.state == DownloadItemState.COMPLETED.name },
                packCount = packs.size,
                health = health,
                expiryCount = stale.size,
                expiryTitles = titles,
                expiryIds = stale.map { it.id },
                smartEnabled = smartEnabled,
                smartLookahead = SmartDownloadsPolicy.clampLookahead(smartLookahead),
                smartSeriesQuota = SmartDownloadsPolicy.clampSeriesQuota(smartSeriesQuota),
                autoDeleteWatched = autoDeleteWatched,
                wifiOnly = wifiOnly,
                softLimitGb = softLimitGb,
                maxConcurrent = maxConcurrent,
                seriesQuotaOccupied = occupancyBySeries.values.sum(),
                seriesWithQuota = occupancyBySeries.size,
            )
        }
    }
}
