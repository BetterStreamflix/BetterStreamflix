package com.dskja.betterstreamflix.utils

import com.dskja.betterstreamflix.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveHubCatalogTest {

    @Test
    fun classifySportsByTitle() {
        assertEquals(
            LiveHubCatalog.Section.SPORT,
            LiveHubCatalog.classify("ESPN USA HD"),
        )
        assertEquals(
            LiveHubCatalog.Section.SPORT,
            LiveHubCatalog.classify("Sky Sports Main Event"),
        )
    }

    @Test
    fun classifyNewsByTitleAndGroup() {
        assertEquals(
            LiveHubCatalog.Section.NEWS,
            LiveHubCatalog.classify("CNN International"),
        )
        assertEquals(
            LiveHubCatalog.Section.NEWS,
            LiveHubCatalog.classify("Local 12", group = "News"),
        )
    }

    @Test
    fun classifyChannelsFallback() {
        assertEquals(
            LiveHubCatalog.Section.CHANNELS,
            LiveHubCatalog.classify("Discovery Channel"),
        )
    }

    @Test
    fun buildCategoriesSplitsSportNewsChannels() {
        val shows = listOf(
            TvShow(id = "1", title = "ESPN", providerName = "DaddyLive TV"),
            TvShow(id = "2", title = "BBC News", providerName = "IPTV-All World"),
            TvShow(id = "3", title = "Discovery", providerName = "DaddyLive TV"),
            TvShow(id = "4", title = "Sky Sports UK", providerName = "StreamSports99"),
        )
        val cats = LiveHubCatalog.buildCategories(
            shows = shows,
            sportLabel = "Sport",
            newsLabel = "News",
            channelsLabel = "Channels",
        )
        assertEquals(3, cats.size)
        assertEquals(LiveHubCatalog.SECTION_SPORT, cats[0].identityKey)
        assertEquals(LiveHubCatalog.SECTION_NEWS, cats[1].identityKey)
        assertEquals(LiveHubCatalog.SECTION_CHANNELS, cats[2].identityKey)
        assertEquals(2, cats[0].list.size)
        assertEquals(1, cats[1].list.size)
        assertEquals(1, cats[2].list.size)
    }

    @Test
    fun hubSourcesIncludeLiveProviders() {
        val names = LiveHubCatalog.hubSources(includeQuarantined = false).map { it.name }
        assertTrue(names.contains("DaddyLive TV"))
        assertTrue(names.contains("StreamSports99"))
        assertTrue(names.contains("IPTV-All World"))
        assertTrue(!names.contains("SportsBite"))
    }

    @Test
    fun hubSourcesCanIncludeQuarantinedSportsBite() {
        val names = LiveHubCatalog.hubSources(includeQuarantined = true).map { it.name }
        assertTrue(names.contains("SportsBite"))
    }
}
