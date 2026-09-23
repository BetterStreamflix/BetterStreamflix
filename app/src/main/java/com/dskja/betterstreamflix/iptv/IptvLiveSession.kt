package com.dskja.betterstreamflix.iptv

import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.IptvProvider
import com.dskja.betterstreamflix.providers.Provider
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory channel guide for IPTV live playback — powers prev/next zapping
 * without leaving the player.
 */
object IptvLiveSession {

    data class Channel(
        val id: String,
        val name: String,
        val logo: String? = null,
        val group: String? = null,
        /** Short “now playing” label when the provider exposes schedule/EPG-lite. */
        val programNow: String? = null,
        /** Optional upcoming program label. */
        val programNext: String? = null,
    )

    private val channels = CopyOnWriteArrayList<Channel>()
    private val recentIds = CopyOnWriteArrayList<String>()
    @Volatile
    private var currentId: String? = null
    @Volatile
    private var providerName: String? = null

    fun clear() {
        channels.clear()
        recentIds.clear()
        currentId = null
        providerName = null
        IptvZapPreloader.clear()
    }

    fun remember(list: List<Channel>, provider: Provider? = null) {
        if (list.isEmpty()) return
        val name = provider?.name
        synchronized(this) {
            if (name != null && providerName != null && providerName != name) {
                channels.clear()
                recentIds.clear()
                IptvZapPreloader.clear()
            }
            providerName = name ?: providerName
            // Preserve existing zap order; update in place; append new ids in list order.
            val existing = LinkedHashMap<String, Channel>()
            channels.forEach { existing[it.id] = it }
            list.forEach { incoming ->
                val prior = existing[incoming.id]
                existing[incoming.id] = mergeChannel(prior, incoming)
            }
            // When session was empty, prefer catalog order from [list].
            if (channels.isEmpty()) {
                channels.clear()
                channels.addAll(list.map { mergeChannel(null, it) })
            } else {
                channels.clear()
                channels.addAll(existing.values)
            }
        }
    }

    private fun mergeChannel(prior: Channel?, incoming: Channel): Channel {
        if (prior == null) return incoming
        return incoming.copy(
            logo = incoming.logo ?: prior.logo,
            group = incoming.group ?: prior.group,
            programNow = incoming.programNow ?: prior.programNow,
            programNext = incoming.programNext ?: prior.programNext,
        )
    }

    fun rememberShows(shows: List<TvShow>, provider: Provider? = null) {
        remember(
            shows.map {
                Channel(
                    id = it.id,
                    name = it.title,
                    logo = it.poster,
                    programNow = it.overview?.takeIf { o ->
                        o.length in 1..80 && !o.contains('\n')
                    },
                )
            },
            provider,
        )
    }

    fun setCurrent(id: String) {
        currentId = id
        touchRecent(id)
    }

    private fun touchRecent(id: String) {
        synchronized(this) {
            recentIds.remove(id)
            recentIds.add(0, id)
            while (recentIds.size > 24) {
                recentIds.removeAt(recentIds.lastIndex)
            }
        }
    }

    fun recent(limit: Int = 8): List<Channel> {
        val byId = channels.associateBy { it.id }
        return recentIds.mapNotNull { byId[it] }.take(limit)
    }

    fun current(): Channel? = currentId?.let { id -> channels.firstOrNull { it.id == id } }

    fun currentIndex(): Int {
        val id = currentId ?: return -1
        return channels.indexOfFirst { it.id == id }
    }

    fun size(): Int = channels.size

    fun hasPrevious(): Boolean = currentIndex() > 0

    fun hasNext(): Boolean {
        val idx = currentIndex()
        return idx >= 0 && idx < channels.lastIndex
    }

    fun peekPrevious(): Channel? {
        val snapshot = channels.toList()
        val id = currentId ?: return null
        val idx = snapshot.indexOfFirst { it.id == id }
        if (idx <= 0) return null
        return snapshot[idx - 1]
    }

    fun peekNext(): Channel? {
        val snapshot = channels.toList()
        val id = currentId ?: return null
        val idx = snapshot.indexOfFirst { it.id == id }
        if (idx < 0 || idx >= snapshot.lastIndex) return null
        return snapshot[idx + 1]
    }

    /** Channels within [radius] of the current index (excludes current). */
    fun neighbors(radius: Int = 1): List<Channel> {
        if (radius <= 0) return emptyList()
        val snapshot = channels.toList()
        val id = currentId ?: return emptyList()
        val idx = snapshot.indexOfFirst { it.id == id }
        if (idx < 0) return emptyList()
        val out = ArrayList<Channel>(radius * 2)
        for (delta in 1..radius) {
            snapshot.getOrNull(idx - delta)?.let { out += it }
            snapshot.getOrNull(idx + delta)?.let { out += it }
        }
        return out
    }

    fun previous(): Channel? {
        val channel = peekPrevious() ?: return null
        currentId = channel.id
        touchRecent(channel.id)
        return channel
    }

    fun next(): Channel? {
        val channel = peekNext() ?: return null
        currentId = channel.id
        touchRecent(channel.id)
        return channel
    }

    fun snapshot(): List<Channel> = channels.toList()

    fun toEpisodeType(channel: Channel): Video.Type.Episode =
        Video.Type.Episode(
            id = channel.id,
            number = 1,
            title = channel.name,
            poster = channel.logo,
            overview = IptvProgramGuide.formatMetaLine(channel),
            tvShow = Video.Type.Episode.TvShow(
                id = channel.id,
                title = channel.name,
                poster = channel.logo,
                banner = channel.logo,
                releaseDate = null,
                imdbId = null,
            ),
            season = Video.Type.Episode.Season(
                number = 1,
                title = "Live",
            ),
        )

    suspend fun ensureLoaded(provider: Provider, aroundId: String?) {
        if (provider !is IptvProvider) return
        if (channels.isNotEmpty() &&
            (aroundId == null || channels.any { it.id == aroundId }) &&
            providerName == provider.name
        ) {
            aroundId?.let { setCurrent(it) }
            IptvZapPreloader.warmNeighbors(provider)
            return
        }
        val loaded = provider.listLiveChannels(aroundId = aroundId, limit = 250)
        remember(loaded, provider)
        aroundId?.let { setCurrent(it) }
        IptvZapPreloader.warmNeighbors(provider)
    }
}
