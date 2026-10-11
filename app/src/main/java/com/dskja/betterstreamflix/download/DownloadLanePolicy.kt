package com.dskja.betterstreamflix.download

/**
 * Pure Download Lane rules: classify rows and pick which ids may occupy
 * parallel Media3 slots (currently watching > Queue > Smart-Auto).
 */
object DownloadLanePolicy {

    data class WatchingFocus(
        val providerName: String,
        val contentKey: String,
        val seriesKey: String? = null,
    )

    data class Plan(
        /** Rows that should be QUEUED/DOWNLOADING (within slot budget). */
        val runIds: Set<String>,
        /** Active / lane-held rows that must yield slots (pause with STOP_LANE). */
        val holdIds: Set<String>,
    )

    fun laneOf(item: DownloadItemEntity, focus: WatchingFocus?): DownloadLane {
        if (matchesWatching(item, focus)) return DownloadLane.WATCHING
        if (item.smartEnqueued) return DownloadLane.SMART_AUTO
        return DownloadLane.QUEUE
    }

    fun matchesWatching(item: DownloadItemEntity, focus: WatchingFocus?): Boolean {
        if (focus == null) return false
        if (focus.providerName.isNotBlank() &&
            item.providerName.isNotBlank() &&
            !item.providerName.equals(focus.providerName, ignoreCase = true)
        ) {
            return false
        }
        if (item.contentKey == focus.contentKey) return true
        val series = SmartDownloadsPolicy.seriesKeyOf(item) ?: return false
        return focus.seriesKey != null && series == focus.seriesKey
    }

    fun isLaneHeld(item: DownloadItemEntity): Boolean =
        DownloadItemState.fromKey(item.state) == DownloadItemState.PAUSED &&
            item.errorCode == DownloadErrorCode.LANE_HOLD.name

    /**
     * Eligible for a parallel slot: actively downloading/queued, or paused only
     * because a higher lane needed the slot. User / Wi‑Fi / storage pauses stay out.
     */
    fun isLaneCandidate(item: DownloadItemEntity): Boolean {
        return when (DownloadItemState.fromKey(item.state)) {
            DownloadItemState.QUEUED,
            DownloadItemState.PREPARING,
            DownloadItemState.DOWNLOADING,
            -> true
            DownloadItemState.PAUSED -> isLaneHeld(item)
            else -> false
        }
    }

    /**
     * Pick up to [slots] runnable item ids, highest lane first, then oldest created.
     */
    fun plan(
        items: List<DownloadItemEntity>,
        slots: Int,
        focus: WatchingFocus?,
    ): Plan {
        val budget = slots.coerceAtLeast(1)
        val candidates = items.filter(::isLaneCandidate)
        if (candidates.isEmpty()) {
            return Plan(runIds = emptySet(), holdIds = emptySet())
        }
        val ranked = candidates.sortedWith(
            compareBy<DownloadItemEntity> { laneOf(it, focus).rank }
                .thenBy { it.createdAt }
                .thenBy { it.id },
        )
        val run = ranked.take(budget).map { it.id }.toSet()
        val hold = ranked.map { it.id }.filterNot { it in run }.toSet()
        return Plan(runIds = run, holdIds = hold)
    }

    fun watchingFocusFrom(
        providerName: String,
        contentKey: String,
        seriesKey: String?,
    ): WatchingFocus = WatchingFocus(
        providerName = providerName,
        contentKey = contentKey,
        seriesKey = seriesKey,
    )
}
