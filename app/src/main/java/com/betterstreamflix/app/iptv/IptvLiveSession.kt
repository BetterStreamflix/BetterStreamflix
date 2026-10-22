package com.betterstreamflix.app.iptv

import com.betterstreamflix.app.BetterStreamflixApp
import com.betterstreamflix.app.R
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.models.Video
import com.betterstreamflix.app.providers.IptvProvider
import com.betterstreamflix.app.providers.Provider
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
        /** Owning IPTV provider — required for multi-source Live Hub zap. */
        val providerName: String? = null,
    )

    private val channels = CopyOnWriteArrayList<Channel>()
    private val recentIds = CopyOnWriteArrayList<String>()
    @Volatile
    private var currentId: String? = null
    @Volatile
    private var providerName: String? = null
    /**
     * True when the last [replaceWindow] from [listLiveChannels] returned fewer
     * channels than requested — the full catalog is already in memory.
     */
    @Volatile
    private var windowComplete: Boolean = false

    fun clear() {
        channels.clear()
        recentIds.clear()
        currentId = null
        providerName = null
        windowComplete = false
        IptvZapPreloader.clear()
    }

    fun isWindowComplete(): Boolean = windowComplete

    /**
     * @param clearOnProviderChange When false (Live Hub multi-provider warm), merge
     * into the existing zap list without wiping prior providers or the preloader.
     */
    fun remember(
        list: List<Channel>,
        provider: Provider? = null,
        clearOnProviderChange: Boolean = true,
    ) {
        if (list.isEmpty()) return
        val name = provider?.name
        synchronized(this) {
            if (clearOnProviderChange &&
                name != null &&
                providerName != null &&
                providerName != name
            ) {
                channels.clear()
                recentIds.clear()
                windowComplete = false
                IptvZapPreloader.clear()
            }
            providerName = name ?: providerName
            // ZP-006: merging must not keep a prior complete=true short window sticky.
            windowComplete = false
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
        // Guide-window warm-up: prefetch the visible head of the catalog so
        // pin strips / PiP previews don't cold-load per row.
        channels.take(GUIDE_PREFETCH_COUNT).forEach { ch ->
            runCatching {
                com.betterstreamflix.app.logo.ChannelLogoLoader.prefetch(
                    com.betterstreamflix.app.BetterStreamflixApp.instance,
                    ch.id,
                    ch.logo,
                )
            }
        }
    }

    private const val GUIDE_PREFETCH_COUNT = 24

    fun contains(id: String): Boolean = channels.any { it.id == id }

    private fun mergeChannel(prior: Channel?, incoming: Channel): Channel {
        if (prior == null) {
            return incoming.copy(
                logo = com.betterstreamflix.app.logo.ChannelLogoStore
                    .effectiveUrl(incoming.id, incoming.logo),
            )
        }
        return incoming.copy(
            logo = com.betterstreamflix.app.logo.ChannelLogoStore.effectiveUrl(
                incoming.id,
                incoming.logo ?: prior.logo,
            ),
            group = incoming.group ?: prior.group,
            programNow = incoming.programNow ?: prior.programNow,
            programNext = incoming.programNext ?: prior.programNext,
            providerName = incoming.providerName ?: prior.providerName,
        )
    }

    fun rememberShows(
        shows: List<TvShow>,
        provider: Provider? = null,
        clearOnProviderChange: Boolean = true,
    ) {
        remember(
            shows.map {
                Channel(
                    id = it.id,
                    name = it.title,
                    logo = it.poster,
                    programNow = it.overview?.takeIf { o ->
                        o.length in 1..80 && !o.contains('\n')
                    },
                    providerName = it.providerName ?: provider?.name,
                )
            },
            provider,
            clearOnProviderChange = clearOnProviderChange,
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

    /**
     * Direct play remembers a single channel, and a full window still has to
     * recenter once the playhead sits on either edge. Otherwise prev/next vanish.
     */
    fun needsWindowRefresh(size: Int, index: Int, limit: Int = 250): Boolean {
        if (limit <= 1) return false
        if (size <= 1 || index < 0) return true
        // Explicit catalog completeness — do not infer from a short list alone.
        if (windowComplete) return false
        val edge = 4
        return index < edge || index > size - 1 - edge
    }

    /** Replace the zap list with catalog order from [listLiveChannels]. */
    fun replaceWindow(
        list: List<Channel>,
        provider: Provider? = null,
        complete: Boolean? = null,
    ) {
        if (list.isEmpty()) return
        synchronized(this) {
            val name = provider?.name
            if (name != null && providerName != null && providerName != name) {
                recentIds.clear()
                IptvZapPreloader.clear()
            }
            providerName = name ?: providerName
            channels.clear()
            // Stamp providerName on every channel so zap → getServers stays on-catalog.
            channels.addAll(
                list.map { channel ->
                    if (channel.providerName.isNullOrBlank() && name != null) {
                        channel.copy(providerName = name)
                    } else {
                        channel
                    }
                },
            )
            if (complete != null) {
                windowComplete = complete
            }
        }
    }

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
                title = liveSeasonTitle(),
            ),
        )

    private fun liveSeasonTitle(): String =
        runCatching {
            BetterStreamflixApp.instance.getString(R.string.player_live_season)
        }.getOrDefault("Live")

    /**
     * Ensure [aroundId] is in the zap list (stub if the catalog window missed it),
     * then mark it current. No-op when [aroundId] is blank.
     */
    fun ensureCurrentPresent(aroundId: String?, fallbackName: String? = null) {
        if (aroundId.isNullOrBlank()) return
        if (channels.any { it.id == aroundId }) {
            setCurrent(aroundId)
            return
        }
        val stub = Channel(
            id = aroundId,
            name = fallbackName?.takeIf { it.isNotBlank() } ?: aroundId,
        )
        synchronized(this) {
            if (channels.none { it.id == aroundId }) {
                channels.add(0, stub)
            }
        }
        setCurrent(aroundId)
    }

    suspend fun ensureLoaded(provider: Provider, aroundId: String?) {
        if (provider !is IptvProvider) return
        val sameProvider = providerName == provider.name
        val index = if (aroundId.isNullOrBlank()) {
            currentIndex()
        } else {
            channels.indexOfFirst { it.id == aroundId }
        }
        val contains = aroundId.isNullOrBlank() || index >= 0
        if (sameProvider &&
            channels.isNotEmpty() &&
            contains &&
            !needsWindowRefresh(channels.size, index, limit = 250)
        ) {
            aroundId?.let { ensureCurrentPresent(it) }
            IptvZapPreloader.warmNeighbors(provider)
            return
        }
        val limit = 250
        val loaded = provider.listLiveChannels(aroundId = aroundId, limit = limit)
        if (loaded.isEmpty()) {
            aroundId?.let { ensureCurrentPresent(it) }
            return
        }
        val withCurrent = if (!aroundId.isNullOrBlank() && loaded.none { it.id == aroundId }) {
            IptvChannelWindow.fromChannels(
                loaded,
                aroundId = aroundId,
                limit = limit,
                forceInclude = Channel(id = aroundId, name = aroundId),
            )
        } else {
            loaded
        }
        val sameOrder = withCurrent.map { it.id } == channels.map { it.id }
        if (!sameOrder || !sameProvider) {
            // Fewer than requested ⇒ provider exhausted the catalog.
            replaceWindow(withCurrent, provider, complete = withCurrent.size < limit)
        }
        aroundId?.let { ensureCurrentPresent(it) }
        IptvZapPreloader.warmNeighbors(provider)
    }
}
