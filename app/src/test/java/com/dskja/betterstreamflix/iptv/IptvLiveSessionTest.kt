package com.dskja.betterstreamflix.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IptvLiveSessionTest {

    @Before
    fun reset() {
        IptvLiveSession.clear()
    }

    @Test
    fun zappingMovesPreviousAndNext() {
        IptvLiveSession.remember(
            listOf(
                IptvLiveSession.Channel("1", "One"),
                IptvLiveSession.Channel("2", "Two"),
                IptvLiveSession.Channel("3", "Three"),
            ),
        )
        IptvLiveSession.setCurrent("2")
        assertTrue(IptvLiveSession.hasPrevious())
        assertTrue(IptvLiveSession.hasNext())
        assertEquals("One", IptvLiveSession.previous()?.name)
        assertEquals("Two", IptvLiveSession.next()?.name)
        assertEquals("Three", IptvLiveSession.next()?.name)
        assertFalse(IptvLiveSession.hasNext())
        assertNull(IptvLiveSession.next())
    }

    @Test
    fun peekDoesNotMutateCurrent() {
        IptvLiveSession.remember(
            listOf(
                IptvLiveSession.Channel("1", "One"),
                IptvLiveSession.Channel("2", "Two"),
                IptvLiveSession.Channel("3", "Three"),
            ),
        )
        IptvLiveSession.setCurrent("2")
        assertEquals("One", IptvLiveSession.peekPrevious()?.name)
        assertEquals("Three", IptvLiveSession.peekNext()?.name)
        assertEquals("2", IptvLiveSession.current()?.id)
        assertEquals(listOf("1", "3"), IptvLiveSession.neighbors(1).map { it.id })
    }

    @Test
    fun rememberPreservesCatalogOrder() {
        IptvLiveSession.remember(
            listOf(
                IptvLiveSession.Channel("a", "A"),
                IptvLiveSession.Channel("b", "B"),
                IptvLiveSession.Channel("c", "C"),
            ),
        )
        assertEquals(listOf("a", "b", "c"), IptvLiveSession.snapshot().map { it.id })
        IptvLiveSession.remember(listOf(IptvLiveSession.Channel("b", "B2")))
        assertEquals(listOf("a", "b", "c"), IptvLiveSession.snapshot().map { it.id })
        assertEquals("B2", IptvLiveSession.snapshot()[1].name)
    }

    @Test
    fun recentTracksZappedChannels() {
        IptvLiveSession.remember(
            listOf(
                IptvLiveSession.Channel("1", "One"),
                IptvLiveSession.Channel("2", "Two"),
                IptvLiveSession.Channel("3", "Three"),
            ),
        )
        IptvLiveSession.setCurrent("1")
        IptvLiveSession.next()
        IptvLiveSession.next()
        assertEquals(listOf("3", "2", "1"), IptvLiveSession.recent().map { it.id })
    }

    @Test
    fun toEpisodeTypeBuildsLiveSeason() {
        val episode = IptvLiveSession.toEpisodeType(
            IptvLiveSession.Channel(
                "abc",
                "CNN",
                logo = "https://logo",
                programNow = "Breaking news",
            ),
        )
        assertEquals("abc", episode.id)
        assertEquals("CNN", episode.tvShow.title)
        assertEquals("Live", episode.season.title)
        assertEquals(1, episode.number)
        assertEquals("Breaking news", episode.overview)
    }

    @Test
    fun singleChannelSessionNeedsACatalogWindow() {
        assertTrue(IptvLiveSession.needsWindowRefresh(1, 0, 250))
        // Short lists alone are not treated as complete until replaceWindow(complete=true).
        assertTrue(IptvLiveSession.needsWindowRefresh(36, 0, 250))
        IptvLiveSession.replaceWindow(
            (1..36).map { IptvLiveSession.Channel(it.toString(), "CH$it") },
            complete = true,
        )
        assertTrue(IptvLiveSession.isWindowComplete())
        assertFalse(IptvLiveSession.needsWindowRefresh(36, 0, 250))
        assertFalse(IptvLiveSession.needsWindowRefresh(36, 35, 250))
        IptvLiveSession.replaceWindow(
            (1..250).map { IptvLiveSession.Channel(it.toString(), "CH$it") },
            complete = false,
        )
        assertFalse(IptvLiveSession.isWindowComplete())
        assertFalse(IptvLiveSession.needsWindowRefresh(250, 120, 250))
        assertTrue(IptvLiveSession.needsWindowRefresh(250, 247, 250))
        IptvLiveSession.remember(listOf(IptvLiveSession.Channel("c", "C")))
        IptvLiveSession.replaceWindow(
            listOf(
                IptvLiveSession.Channel("a", "A"),
                IptvLiveSession.Channel("b", "B"),
                IptvLiveSession.Channel("c", "C"),
            ),
            complete = true,
        )
        assertEquals(listOf("a", "b", "c"), IptvLiveSession.snapshot().map { it.id })
    }

    @Test
    fun ensureCurrentPresentInsertsStubWhenMissing() {
        IptvLiveSession.remember(
            listOf(
                IptvLiveSession.Channel("1", "One"),
                IptvLiveSession.Channel("2", "Two"),
            ),
        )
        IptvLiveSession.ensureCurrentPresent("missing", fallbackName = "Stub")
        assertEquals("missing", IptvLiveSession.current()?.id)
        assertEquals("Stub", IptvLiveSession.current()?.name)
        assertTrue(IptvLiveSession.snapshot().any { it.id == "missing" })
    }

    @Test
    fun rememberMergesById() {
        IptvLiveSession.remember(listOf(IptvLiveSession.Channel("1", "A")))
        IptvLiveSession.remember(listOf(IptvLiveSession.Channel("1", "A-updated"), IptvLiveSession.Channel("2", "B")))
        assertEquals(2, IptvLiveSession.size())
        IptvLiveSession.setCurrent("1")
        assertEquals("A-updated", IptvLiveSession.current()?.name)
    }
}

class IptvLivePlaybackTest {

    @Test
    fun liveConfigurationHasStableOffsets() {
        val config = IptvLivePlayback.liveConfiguration()
        assertEquals(IptvLivePlayback.TARGET_OFFSET_MS, config.targetOffsetMs)
        assertTrue(config.minOffsetMs > 0)
        assertTrue(config.maxOffsetMs > config.targetOffsetMs)
        assertTrue(config.maxPlaybackSpeed > 1f)
    }

    @Test
    fun sportsProvidersUseDeeperTargetOffset() {
        val sports = IptvLivePlayback.liveConfigurationForProvider("SportsBite")
        val generic = IptvLivePlayback.liveConfigurationForProvider("Pluto TV Us")
        assertEquals(IptvLivePlayback.SPORTS_TARGET_OFFSET_MS, sports.targetOffsetMs)
        assertEquals(IptvLivePlayback.TARGET_OFFSET_MS, generic.targetOffsetMs)
    }
}

class IptvChannelWindowTest {

    @Test
    fun windowsAroundCurrentChannel() {
        val channels = (1..10).map { IptvLiveSession.Channel(it.toString(), "CH$it") }
        val window = IptvChannelWindow.fromChannels(channels, aroundId = "5", limit = 4)
        assertEquals(listOf("3", "4", "5", "6"), window.map { it.id })
    }

    @Test
    fun forceIncludesMissingAroundId() {
        val channels = (1..5).map { IptvLiveSession.Channel(it.toString(), "CH$it") }
        val stub = IptvLiveSession.Channel("missing", "Stub")
        val window = IptvChannelWindow.fromChannels(
            channels,
            aroundId = "missing",
            limit = 3,
            forceInclude = stub,
        )
        assertEquals("missing", window.first().id)
        assertEquals(3, window.size)
        assertTrue(window.any { it.id == "missing" })
    }
}

class IptvProgramGuideTest {

    @Test
    fun sportsSlotFormatsLiveAnd247() {
        val live = IptvProgramGuide.sportsSlot(
            alwaysLive = false,
            category = "Football",
            tag = "Premier League",
            viewers = 1200,
        )
        assertEquals("Premier League · Football · 1k watching", live.now)

        val allDay = IptvProgramGuide.sportsSlot(
            alwaysLive = true,
            category = "News",
            tag = "",
        )
        assertEquals("24/7 live", allDay.now)
        assertEquals("News", allDay.next)
    }

    @Test
    fun formatMetaLineJoinsNowAndNext() {
        val channel = IptvLiveSession.Channel(
            id = "1",
            name = "ESPN",
            programNow = "NBA Finals",
            programNext = "Postgame",
        )
        assertEquals("NBA Finals  ·  next: Postgame", IptvProgramGuide.formatMetaLine(channel))
    }
}
