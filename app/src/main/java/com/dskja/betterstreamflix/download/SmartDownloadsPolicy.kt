package com.dskja.betterstreamflix.download

/**
 * Pure helpers for Smart Downloads: lookahead, per-series quota, and
 * which Room rows count toward that quota.
 */
object SmartDownloadsPolicy {
    const val DEFAULT_LOOKAHEAD = 3
    const val DEFAULT_SERIES_QUOTA = 3
    const val MIN_LOOKAHEAD = 1
    const val MAX_LOOKAHEAD = 5
    const val MIN_SERIES_QUOTA = 1
    const val MAX_SERIES_QUOTA = 10

    fun clampLookahead(value: Int): Int = value.coerceIn(MIN_LOOKAHEAD, MAX_LOOKAHEAD)

    fun clampSeriesQuota(value: Int): Int = value.coerceIn(MIN_SERIES_QUOTA, MAX_SERIES_QUOTA)

    /** True when the row still occupies a Smart Downloads series slot. */
    fun countsTowardSeriesQuota(item: DownloadItemEntity): Boolean {
        if (item.kind != DownloadKind.EPISODE.name) return false
        return when (DownloadItemState.fromKey(item.state)) {
            DownloadItemState.QUEUED,
            DownloadItemState.PREPARING,
            DownloadItemState.DOWNLOADING,
            DownloadItemState.PAUSED,
            -> true
            DownloadItemState.COMPLETED -> !item.watchedOffline
            else -> false
        }
    }

    fun seriesOccupancy(items: List<DownloadItemEntity>, seriesKey: String): Int =
        items.count { countsTowardSeriesQuota(it) && seriesKeyOf(it) == seriesKey }

    fun slotsRemaining(occupancy: Int, quota: Int): Int =
        (clampSeriesQuota(quota) - occupancy).coerceAtLeast(0)

    /**
     * How many missing upcoming episodes to enqueue: min(lookahead gaps, quota slots).
     */
    fun enqueueBudget(
        missingUpcoming: Int,
        occupancy: Int,
        lookahead: Int,
        quota: Int,
    ): Int {
        val want = missingUpcoming.coerceAtMost(clampLookahead(lookahead))
        return want.coerceAtMost(slotsRemaining(occupancy, quota))
    }

    /**
     * Explicit one-tap season enqueue: fill remaining series-quota slots only
     * (no lookahead cap — user asked for the whole season).
     */
    fun seasonEnqueueBudget(
        missingInSeason: Int,
        occupancy: Int,
        quota: Int,
    ): Int = missingInSeason.coerceAtLeast(0).coerceAtMost(slotsRemaining(occupancy, quota))

    fun seriesKeyOf(item: DownloadItemEntity): String? =
        DownloadSeriesFolders.seriesRef(item)?.key

    fun seriesKeyForShow(providerName: String, tvShowId: String): String =
        "series|$providerName|$tvShowId"
}
