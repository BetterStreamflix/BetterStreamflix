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

    /**
     * True when the row still occupies a Smart Downloads series slot.
     * Only Smart-enqueued rows count — manual / season QUEUE downloads do not
     * consume Smart quota (OS-005).
     */
    fun countsTowardSeriesQuota(item: DownloadItemEntity): Boolean {
        if (item.kind != DownloadKind.EPISODE.name) return false
        if (!item.smartEnqueued) return false
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
     * Explicit one-tap season enqueue: user asked for the whole season.
     * Not capped by Smart series quota — only by storage / network elsewhere.
     * [quota] retained for API compatibility; ignored for budget size.
     */
    fun seasonEnqueueBudget(
        missingInSeason: Int,
        occupancy: Int,
        quota: Int,
    ): Int {
        // Explicit season is a user QUEUE action — do not apply Smart quota.
        @Suppress("UNUSED_PARAMETER")
        val ignoredOccupancy = occupancy
        @Suppress("UNUSED_PARAMETER")
        val ignoredQuota = quota
        return missingInSeason.coerceAtLeast(0)
    }

    fun seriesKeyOf(item: DownloadItemEntity): String? =
        DownloadSeriesFolders.seriesRef(item)?.key

    fun seriesKeyForShow(providerName: String, tvShowId: String): String =
        "series|$providerName|$tvShowId"
}
