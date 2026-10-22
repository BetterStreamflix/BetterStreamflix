package com.betterstreamflix.app.utils

import com.betterstreamflix.app.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun classifyBbcSportAsSportNotNews() {
        assertEquals(
            LiveHubCatalog.Section.SPORT,
            LiveHubCatalog.classify("BBC Sport"),
        )
        assertEquals(
            LiveHubCatalog.Section.SPORT,
            LiveHubCatalog.classify("BBC SPORT HD"),
        )
    }

    @Test
    fun classifyBbcNewsAsNews() {
        assertEquals(
            LiveHubCatalog.Section.NEWS,
            LiveHubCatalog.classify("BBC News"),
        )
    }

    @Test
    fun classifyPremierLeagueAsSportNotBarePremier() {
        assertEquals(
            LiveHubCatalog.Section.SPORT,
            LiveHubCatalog.classify("Sky Premier League"),
        )
        // "Premiere" cinema-style titles must not match sport via bare "premier".
        assertEquals(
            LiveHubCatalog.Section.CHANNELS,
            LiveHubCatalog.classify("Premiere Cinema"),
        )
    }

    @Test
    fun classifySportBeforeNewsInBlob() {
        assertEquals(
            LiveHubCatalog.Section.SPORT,
            LiveHubCatalog.classify("ESPN News Brief"),
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
    fun buildCategoriesPrependsFavoritesShelf() {
        val shows = listOf(
            TvShow(id = "1", title = "ESPN", providerName = "DaddyLive TV"),
            TvShow(id = "2", title = "BBC News", providerName = "IPTV-All World"),
        )
        val favorites = listOf(
            TvShow(id = "1", title = "ESPN", providerName = "DaddyLive TV").apply {
                isFavorite = true
                programNow = "USA"
            },
        )
        val cats = LiveHubCatalog.buildCategories(
            shows = shows,
            sportLabel = "Sport",
            newsLabel = "News",
            channelsLabel = "Channels",
            favorites = favorites,
            favoritesLabel = "Favorites",
        )
        assertEquals(LiveHubCatalog.SECTION_FAVORITES, cats[0].identityKey)
        assertEquals(1, cats[0].list.size)
        assertEquals("ESPN", (cats[0].list[0] as TvShow).title)
    }

    @Test
    fun programNowOfDoesNotInventGroupAsEpg() {
        val show = TvShow(
            id = "x",
            title = "ESPN",
            overview = "ESPN\nUnited States · Sports",
            providerName = "DaddyLive TV",
        )
        assertNull(LiveHubCatalog.programNowOf(show))
        assertEquals("Sports", LiveHubCatalog.groupOf(show))
        LiveHubCatalog.enrichEpgLite(show)
        assertNull(show.programNow)
    }

    @Test
    fun programNowOfPrefersExplicitField() {
        val show = TvShow(id = "x", title = "ESPN", providerName = "DaddyLive TV").apply {
            programNow = "NBA Finals"
            overview = "ESPN\nUnited States · Sports"
        }
        assertEquals("NBA Finals", LiveHubCatalog.programNowOf(show))
    }

    @Test
    fun groupFromOverviewBlank() {
        assertNull(LiveHubCatalog.groupFromOverview(null))
        assertNull(LiveHubCatalog.groupFromOverview("Title only"))
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
