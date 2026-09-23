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
        idOf: (T) -> String,
    ): List<T> {
        if (items.isEmpty() || limit <= 0) return emptyList()
        if (aroundId.isNullOrBlank()) return items.take(limit)
        val idx = items.indexOfFirst { idOf(it) == aroundId }
        if (idx < 0) return items.take(limit)
        val start = (idx - limit / 2).coerceAtLeast(0)
        return items.drop(start).take(limit)
    }

    fun fromChannels(
        channels: List<IptvLiveSession.Channel>,
        aroundId: String?,
        limit: Int,
    ): List<IptvLiveSession.Channel> =
        around(channels, aroundId, limit) { it.id }

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
