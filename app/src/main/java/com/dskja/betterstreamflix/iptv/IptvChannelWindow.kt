package com.dskja.betterstreamflix.iptv

import com.dskja.betterstreamflix.models.TvShow

/**
 * Shared windowing for [IptvProvider.listLiveChannels] so catalog order stays
 * stable while the live player only keeps a usable zap window in memory.
 */
object IptvChannelWindow {

    fun <T> around(
        items: List<T>,
        aroundId: String?,
        limit: Int,
        /** When [aroundId] is absent from [items], prepend this stub so zap still has a playhead. */
        forceInclude: T? = null,
        idOf: (T) -> String,
    ): List<T> {
        if (limit <= 0) return emptyList()
        if (items.isEmpty()) {
            return if (forceInclude != null && !aroundId.isNullOrBlank() && idOf(forceInclude) == aroundId) {
                listOf(forceInclude)
            } else {
                emptyList()
            }
        }
        if (aroundId.isNullOrBlank()) return items.take(limit)
        val idx = items.indexOfFirst { idOf(it) == aroundId }
        if (idx < 0) {
            val stub = forceInclude?.takeIf { idOf(it) == aroundId }
            if (stub == null) return items.take(limit)
            val rest = items.filterNot { idOf(it) == aroundId }.take((limit - 1).coerceAtLeast(0))
            return listOf(stub) + rest
        }
        val start = (idx - limit / 2).coerceAtLeast(0)
        val window = items.drop(start).take(limit)
        // Guarantee the playhead stays in the window even if edge math ever drifts.
        if (window.any { idOf(it) == aroundId }) return window
        val current = items[idx]
        return (listOf(current) + window.filterNot { idOf(it) == aroundId }).take(limit)
    }

    fun fromChannels(
        channels: List<IptvLiveSession.Channel>,
        aroundId: String?,
        limit: Int,
        forceInclude: IptvLiveSession.Channel? = null,
    ): List<IptvLiveSession.Channel> =
        around(channels, aroundId, limit, forceInclude = forceInclude) { it.id }

    fun fromShows(
        shows: List<TvShow>,
        aroundId: String?,
        limit: Int,
        groupOf: (TvShow) -> String? = { null },
        programNowOf: (TvShow) -> String? = { null },
        programNextOf: (TvShow) -> String? = { null },
    ): List<IptvLiveSession.Channel> =
        around(shows, aroundId, limit) { it.id }.map { show ->
            IptvLiveSession.Channel(
                id = show.id,
                name = show.title,
                logo = show.poster,
                group = groupOf(show),
                programNow = programNowOf(show),
                programNext = programNextOf(show),
            )
        }
}
