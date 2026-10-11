package com.dskja.betterstreamflix.fragments.live

import com.dskja.betterstreamflix.iptv.IptvLiveSession
import com.dskja.betterstreamflix.iptv.IptvZapPreloader
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.IptvProvider
import com.dskja.betterstreamflix.providers.Provider

/**
 * Live Hub zap window is scoped to the **tapped shelf**, not a merge of every
 * Sport/News/Channels row. [bindCategories] records per-show siblings; playback
 * calls [preparePlayback] → [IptvLiveSession.replaceWindow].
 */
object LiveHubSectionZap {
    @Volatile
    private var sectionByShowId: Map<String, List<TvShow>> = emptyMap()

    fun bindCategories(categories: List<Category>) {
        val map = HashMap<String, List<TvShow>>()
        for (category in categories) {
            val shows = category.list.filterIsInstance<TvShow>()
            if (shows.isEmpty()) continue
            for (show in shows) {
                map[show.id] = shows
            }
        }
        sectionByShowId = map
    }

    fun clear() {
        sectionByShowId = emptyMap()
    }

    /** True when [tvShow] came from a bound Live Hub shelf. */
    fun preparePlayback(tvShow: TvShow, provider: Provider?): Boolean {
        val section = sectionByShowId[tvShow.id] ?: return false
        val channels = section.map {
            IptvLiveSession.Channel(id = it.id, name = it.title, logo = it.poster)
        }
        IptvLiveSession.replaceWindow(channels, provider, complete = true)
        IptvLiveSession.setCurrent(tvShow.id)
        val iptv = provider as? IptvProvider
        if (iptv != null) {
            section.take(3).forEach { show ->
                IptvZapPreloader.warmChannel(
                    iptv,
                    IptvLiveSession.Channel(id = show.id, name = show.title, logo = show.poster),
                )
            }
        }
        return true
    }
}
