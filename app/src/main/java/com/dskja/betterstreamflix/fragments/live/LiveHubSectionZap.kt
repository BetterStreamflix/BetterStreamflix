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
 * sport siblings only (ZP-005).
 *
 * [bindCategories] records per-show siblings keyed by `provider|id` (ZP-004);
 * playback calls [preparePlayback] → [IptvLiveSession.replaceWindow] with
 * `complete = true` so [IptvLiveSession.ensureLoaded] cannot widen past the hub ring.
 *
 * [warmSportRing] only warms via the preloader — it must not mutate the global
 * [IptvLiveSession] window (ZP-001 / ZP-002).
 */
object LiveHubSectionZap {
    @Volatile
    private var sectionByShowKey: Map<String, List<TvShow>> = emptyMap()

    @Volatile
    private var sectionKeyByShowKey: Map<String, String> = emptyMap()

    /** Soft cache for Sender-Leiste enrichment after hub unbind (SL-009). */
    @Volatile
    private var lastBoundShows: List<TvShow> = emptyList()

    fun bindCategories(categories: List<Category>) {
        val map = HashMap<String, List<TvShow>>()
        val keys = HashMap<String, String>()
        val bound = ArrayList<TvShow>()
        for (category in categories) {
            val shows = category.list.filterIsInstance<TvShow>()
            if (shows.isEmpty()) continue
            val identity = category.identityKey.orEmpty()
            for (show in shows) {
                val showKey = LiveHubCatalog.identityKey(show)
                map[showKey] = shows
                if (identity.isNotBlank()) {
                    keys[showKey] = identity
                }
                bound += show
            }
        }
        sectionByShowKey = map
        sectionKeyByShowKey = keys
        lastBoundShows = bound.distinctBy { LiveHubCatalog.identityKey(it) }
    }

    fun clear() {
        sectionByShowKey = emptyMap()
        sectionKeyByShowKey = emptyMap()
        // ZP-008: stop hub warms; keep LRU for in-flight playback.
        IptvZapPreloader.cancelInflight()
    }

    fun enrichmentCatalog(): List<TvShow> = lastBoundShows

    fun sectionKeyOf(tvShow: TvShow): String? =
        sectionKeyByShowKey[LiveHubCatalog.identityKey(tvShow)]

    /** True when [tvShow] came from a bound Live Hub shelf. */
    fun preparePlayback(tvShow: TvShow, provider: Provider?): Boolean {
        val showKey = LiveHubCatalog.identityKey(tvShow)
        val section = sectionByShowKey[showKey] ?: return false
        val sectionKey = sectionKeyByShowKey[showKey]
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
     * Dedicated Sport zap ring: always same-provider siblings so CH± stays
     * on a playable catalog (ZP-005). Size may be 1.
     */
    fun sportZapRing(anchor: TvShow, sportShelf: List<TvShow>): List<TvShow> {
        val providerName = anchor.providerName.orEmpty()
        if (providerName.isBlank()) return listOf(anchor)
        val sameProvider = sportShelf.filter {
            it.providerName.equals(providerName, ignoreCase = true)
        }
        return sameProvider.ifEmpty { listOf(anchor) }
    }

    /**
     * Pre-warm the Sport ring from the hub without mutating [IptvLiveSession]
     * (ZP-001 / ZP-002).
     */
    fun warmSportRing(sportCategory: Category?) {
        val shows = sportCategory?.list?.filterIsInstance<TvShow>().orEmpty()
        if (shows.isEmpty()) return
        val byProvider = shows.groupBy { it.providerName.orEmpty() }
        byProvider.forEach { (providerName, providerShows) ->
            if (providerName.isBlank()) return@forEach
            val provider = runCatching {
                Provider.findByName(providerName) as? IptvProvider
            }.getOrNull() ?: return@forEach
            val ring = sportZapRing(providerShows.first(), providerShows)
            // Warm ±2 per provider without replaceWindow / setCurrent / preloader clear.
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
        // ZP-002: warm per provider without session-dependent warmNeighbors
        // (which would require setCurrent / could clear across provider changes).
        warmIds.take(5).forEach { show ->
            IptvZapPreloader.warmChannel(provider, toChannel(show))
        }
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
