package com.dskja.betterstreamflix.ui

import com.dskja.betterstreamflix.models.Video
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrailerStreamCrossfadeTest {

    @Before
    fun setUp() {
        TrailerPlayPipeline.elapsedRealtimeMs = { 10_000L }
        TrailerStreamCrossfade.elapsedRealtimeMs = { 10_000L }
        TrailerPlayPipeline.clear()
        TrailerStreamCrossfade.clear()
    }

    @After
    fun tearDown() {
        TrailerPlayPipeline.clear()
        TrailerStreamCrossfade.clear()
        TrailerPlayPipeline.elapsedRealtimeMs = {
            try {
                android.os.SystemClock.elapsedRealtime()
            } catch (_: RuntimeException) {
                System.nanoTime() / 1_000_000L
            }
        }
        TrailerStreamCrossfade.elapsedRealtimeMs = {
            try {
                android.os.SystemClock.elapsedRealtime()
            } catch (_: RuntimeException) {
                System.nanoTime() / 1_000_000L
            }
        }
    }

    @Test
    fun arm_storesCoverAndContentId() {
        val videoType = Video.Type.Movie(
            id = "m1",
            title = "Title",
            releaseDate = "2024",
            poster = "https://image.tmdb.org/t/p/w500/poster.jpg",
            imdbId = null,
        )
        TrailerStreamCrossfade.arm("m1", videoType)
        val handoff = TrailerStreamCrossfade.peek()
        assertNotNull(handoff)
        assertEquals("m1", handoff!!.contentId)
        assertNotNull(handoff.coverUrl)
        assertTrue(handoff.coverUrl!!.contains("poster.jpg") || handoff.coverUrl!!.contains("tmdb"))
    }

    @Test
    fun consumeFor_matchesContentIdOnce() {
        val videoType = Video.Type.Movie(
            id = "m1",
            title = "Title",
            releaseDate = "2024",
            poster = "https://cdn.example.com/p.jpg",
            imdbId = null,
        )
        TrailerStreamCrossfade.arm("m1", videoType)
        assertNull(TrailerStreamCrossfade.consumeFor("other"))
        assertNotNull(TrailerStreamCrossfade.consumeFor("m1"))
        assertNull(TrailerStreamCrossfade.peek())
    }

    @Test
    fun arm_marksServerHotWhenPipelineWarmed() {
        val server = Video.Server(id = "srv-1", name = "A", src = "https://example.com/a")
        TrailerPlayPipeline.seedForTest("m1", listOf(server), providerName = "Test")
        val videoType = Video.Type.Movie(
            id = "m1",
            title = "Title",
            releaseDate = "2024",
            poster = "https://cdn.example.com/p.jpg",
            imdbId = null,
        )
        TrailerStreamCrossfade.arm("m1", videoType)
        assertTrue(TrailerStreamCrossfade.peek()!!.serverHot)
        assertTrue(TrailerPlayPipeline.isServersHot("m1"))
    }

    @Test
    fun awaitServersHot_returnsTrueWhenAlreadyHot() = runBlocking {
        val server = Video.Server(id = "srv-1", name = "A", src = "https://example.com/a")
        TrailerPlayPipeline.seedForTest("m1", listOf(server))
        assertTrue(TrailerPlayPipeline.awaitServersHot("m1", 50L))
    }

    @Test
    fun awaitServersHot_returnsFalseWhenColdAndIdle() = runBlocking {
        assertFalse(TrailerPlayPipeline.awaitServersHot("missing", 50L))
    }

    @Test
    fun coverUrlFor_episodePrefersShowBanner() {
        val episode = Video.Type.Episode(
            id = "e1",
            number = 2,
            title = "Ep",
            poster = "https://cdn.example.com/ep.jpg",
            overview = null,
            tvShow = Video.Type.Episode.TvShow(
                id = "s1",
                title = "Show",
                poster = "https://cdn.example.com/show.jpg",
                banner = "https://cdn.example.com/banner.jpg",
                releaseDate = null,
                imdbId = null,
            ),
            season = Video.Type.Episode.Season(number = 1, title = null),
        )
        val cover = TrailerStreamCrossfade.coverUrlFor(episode)
        assertNotNull(cover)
        assertTrue(cover!!.contains("banner.jpg") || cover.contains("ep.jpg") || cover.contains("show.jpg"))
    }
}
