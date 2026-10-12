package com.dskja.betterstreamflix.fragments.live

import com.dskja.betterstreamflix.iptv.IptvLiveSession
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.LiveHubCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveHubSectionZapTest {

    @Before
    @After
    fun reset() {
        LiveHubSectionZap.clear()
        IptvLiveSession.clear()
    }

    @Test
    fun sportZapRingPrefersSameProviderWhenMultiple() {
        val sport = listOf(
            TvShow(id = "a", title = "ESPN", providerName = "DaddyLive TV"),
            TvShow(id = "b", title = "Sky Sports", providerName = "DaddyLive TV"),
            TvShow(id = "c", title = "DAZN", providerName = "StreamSports99"),
        )
        val ring = LiveHubSectionZap.sportZapRing(sport[0], sport)
        assertEquals(2, ring.size)
        assertTrue(ring.all { it.providerName == "DaddyLive TV" })
    }

    @Test
    fun sportZapRingKeepsSameProviderEvenWhenSingleton() {
        val sport = listOf(
            TvShow(id = "a", title = "ESPN", providerName = "DaddyLive TV"),
            TvShow(id = "c", title = "DAZN", providerName = "StreamSports99"),
        )
        val ring = LiveHubSectionZap.sportZapRing(sport[0], sport)
        // ZP-005: never mix providers for zap.
        assertEquals(1, ring.size)
        assertEquals("DaddyLive TV", ring[0].providerName)
    }

    @Test
    fun preparePlaybackScopesWindowAndKeepsEpg() {
        val sport = Category(
            "Sport",
            listOf(
                TvShow(id = "a", title = "ESPN", providerName = "DaddyLive TV").apply {
                    overview = "ESPN\nUnited States · Sports"
                    programNow = "Sports"
                },
                TvShow(id = "b", title = "Sky Sports", providerName = "DaddyLive TV").apply {
                    programNow = "Premier League"
                },
            ),
        ).also { it.identityKey = LiveHubCatalog.SECTION_SPORT }
        val news = Category(
            "News",
            listOf(
                TvShow(id = "n1", title = "CNN", providerName = "IPTV-All World").apply {
                    programNow = "Breaking"
                },
            ),
        ).also { it.identityKey = LiveHubCatalog.SECTION_NEWS }

        LiveHubSectionZap.bindCategories(listOf(sport, news))
        val ok = LiveHubSectionZap.preparePlayback(
            sport.list[0] as TvShow,
            provider = null,
        )
        assertTrue(ok)
        assertTrue(IptvLiveSession.isWindowComplete())
        assertEquals(2, IptvLiveSession.size())
        assertEquals("Sports", IptvLiveSession.current()?.programNow)
        assertFalse(IptvLiveSession.contains("n1"))
    }

    @Test
    fun preparePlaybackReturnsFalseWhenUnbound() {
        assertFalse(
            LiveHubSectionZap.preparePlayback(
                TvShow(id = "orphan", title = "X", providerName = "DaddyLive TV"),
                provider = null,
            ),
        )
    }

    @Test
    fun warmSportRingDoesNotClobberSessionWindow() {
        // Pre-existing playback window must survive hub warm (ZP-001).
        IptvLiveSession.replaceWindow(
            listOf(
                IptvLiveSession.Channel("keep-1", "Keep One"),
                IptvLiveSession.Channel("keep-2", "Keep Two"),
            ),
            provider = null,
            complete = true,
        )
        IptvLiveSession.setCurrent("keep-1")
        assertEquals(2, IptvLiveSession.size())
        assertTrue(IptvLiveSession.isWindowComplete())

        val sport = Category(
            "Sport",
            listOf(
                TvShow(id = "a", title = "ESPN", providerName = "DaddyLive TV"),
                TvShow(id = "b", title = "Sky", providerName = "DaddyLive TV"),
                TvShow(id = "c", title = "DAZN", providerName = "StreamSports99"),
            ),
        ).also { it.identityKey = LiveHubCatalog.SECTION_SPORT }

        LiveHubSectionZap.warmSportRing(sport)

        assertEquals(2, IptvLiveSession.size())
        assertEquals("keep-1", IptvLiveSession.current()?.id)
        assertTrue(IptvLiveSession.isWindowComplete())
        assertTrue(IptvLiveSession.contains("keep-2"))
    }

    @Test
    fun bindCategoriesKeysByProviderAndId() {
        val a = TvShow(id = "same", title = "A", providerName = "DaddyLive TV")
        val b = TvShow(id = "same", title = "B", providerName = "StreamSports99")
        val sport = Category("Sport", listOf(a, b)).also {
            it.identityKey = LiveHubCatalog.SECTION_SPORT
        }
        LiveHubSectionZap.bindCategories(listOf(sport))
        assertTrue(LiveHubSectionZap.preparePlayback(a, null))
        // Same id, different provider — still bound independently.
        assertEquals(LiveHubCatalog.SECTION_SPORT, LiveHubSectionZap.sectionKeyOf(b))
    }
}
