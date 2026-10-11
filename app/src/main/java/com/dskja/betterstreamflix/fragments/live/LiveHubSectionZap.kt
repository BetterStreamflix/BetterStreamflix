package com.dskja.betterstreamflix.fragments.live

import com.dskja.betterstreamflix.iptv.IptvLiveSession
import com.dskja.betterstreamflix.iptv.IptvZapPreloader
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.IptvProvider
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.LiveHubCatalog

/**
 * Live Hub zap window is scoped to the **tapped shelf**, not a merge of every
 * Favorites/Sport/News/Channels row. Sport uses a dedicated ring: same-provider
 * sport siblings first (reliable zap), falling back to the full Sport shelf.
 *
 * [bindCategories] records per-show siblings; playback calls [preparePlayback]
 * → [IptvLiveSession.replaceWindow] with `complete = true` so [IptvLiveSession.ensureLoaded]
 * cannot widen past the hub ring.
 */
object LiveHubSectionZap {
    @Volatile
    private var sectionByShowId: Map<String, List<TvShow>> = emptyMap()

    @Volatile
    private var sectionKeyByShowId: Map<String, String> = emptyMap()

    fun bindCategories(categories: List<Category>) {
        val map = HashMap<String, List<TvShow>>()
        val keys = HashMap<String, String>()
        for (category in categories) {
            val shows = category.list.filterIsInstance<TvShow>()
            if (shows.isEmpty()) continue
            val identity = category.identityKey.orEmpty()
            for (show in shows) {
                map[show.id] = shows
                if (identity.isNotBlank()) {
                    keys[show.id] = identity
                }
            }
        }
        sectionByShowId = map
        sectionKeyByShowId = keys
    }

    fun clear() {
        sectionByShowId = emptyMap()
        sectionKeyByShowId = emptyMap()
    }

    fun sectionKeyOf(tvShow: TvShow): String? = sectionKeyByShowId[tvShow.id]

    /** True when [tvShow] came from a bound Live Hub shelf. */
    fun preparePlayback(tvShow: TvShow, provider: Provider?): Boolean {
        val section = sectionByShowId[tvShow.id] ?: return false
        val sectionKey = sectionKeyByShowId[tvShow.id]
        val ring = when (sectionKey) {
            LiveHubCatalog.SECTION_SPORT -> sportZapRing(tvShow, section)
            else -> section
        }
        val channels = ring.map { toChannel(it) }
        IptvLiveSession.replaceWindow(channels, provider, complete = true)
        IptvLiveSession.setCurrent(tvShow.id)
        val iptv = provider as? IptvProvider
        if (iptv != null) {
            warmRing(iptv, ring, aroundId = tvShow.id)
        }
        return true
    }

    /**
     * Dedicated Sport zap ring: prefer same-provider sport siblings so CH± stays
     * on a playable catalog; if the provider only contributed one sport tile,
     * keep the full Sport shelf order for discovery.
     */
    fun sportZapRing(anchor: TvShow, sportShelf: List<TvShow>): List<TvShow> {
        val providerName = anchor.providerName.orEmpty()
        if (providerName.isBlank()) return sportShelf
        val sameProvider = sportShelf.filter {
            it.providerName.equals(providerName, ignoreCase = true)
        }
        return if (sameProvider.size >= 2) sameProvider else sportShelf
    }

    /** Pre-warm the Sport ring from the hub (TV) without merging other shelves. */
    fun warmSportRing(sportCategory: Category?) {
        val shows = sportCategory?.list?.filterIsInstance<TvShow>().orEmpty()
        if (shows.isEmpty()) return
        val byProvider = shows.groupBy { it.providerName.orEmpty() }
        byProvider.forEach { (providerName, providerShows) ->
            if (providerName.isBlank()) return@forEach
            val provider = Provider.findByName(providerName) as? IptvProvider ?: return@forEach
            val ring = sportZapRing(providerShows.first(), providerShows)
            IptvLiveSession.replaceWindow(ring.map { toChannel(it) }, provider, complete = true)
            ring.firstOrNull()?.let { IptvLiveSession.setCurrent(it.id) }
            warmRing(provider, ring, aroundId = ring.firstOrNull()?.id)
        }
    }

    private fun warmRing(provider: IptvProvider, ring: List<TvShow>, aroundId: String?) {
        val anchorIdx = aroundId?.let { id -> ring.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
        val warmIds = linkedSetOf<TvShow>()
        warmIds += ring[anchorIdx]
        for (delta in 1..2) {
            ring.getOrNull(anchorIdx - delta)?.let { warmIds += it }
            ring.getOrNull(anchorIdx + delta)?.let { warmIds += it }
        }
        warmIds.take(5).forEach { show ->
            IptvZapPreloader.warmChannel(provider, toChannel(show))
        }
        IptvZapPreloader.warmNeighbors(provider, radius = 2)
    }

    private fun toChannel(show: TvShow): IptvLiveSession.Channel =
        IptvLiveSession.Channel(
            id = show.id,
            name = show.title,
            logo = show.poster,
            group = LiveHubCatalog.groupOf(show),
            programNow = LiveHubCatalog.programNowOf(show),
            programNext = LiveHubCatalog.programNextOf(show),
        )
}
