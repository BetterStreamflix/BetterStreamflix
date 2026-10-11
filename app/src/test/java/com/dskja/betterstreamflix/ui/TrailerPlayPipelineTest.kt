package com.dskja.betterstreamflix.ui

import com.dskja.betterstreamflix.models.Video
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TrailerPlayPipelineTest {

    @Before
    fun setUp() {
        TrailerPlayPipeline.elapsedRealtimeMs = { 10_000L }
        TrailerPlayPipeline.clear()
    }

    @After
    fun tearDown() {
        TrailerPlayPipeline.clear()
        TrailerPlayPipeline.elapsedRealtimeMs = {
            try {
                android.os.SystemClock.elapsedRealtime()
            } catch (_: RuntimeException) {
                System.nanoTime() / 1_000_000L
            }
        }
    }

    @Test
    fun seedAndPeek_returnsServersAndFirstVideo() {
        val server = Video.Server(id = "srv-1", name = "A", src = "https://example.com/a")
        val video = Video(source = "https://cdn.example.com/vod.m3u8")
        TrailerPlayPipeline.seedForTest("movie-1", listOf(server), providerName = "Test", video = video)

        val servers = TrailerPlayPipeline.peekCachedServers("movie-1")
        assertNotNull(servers)
        assertEquals(1, servers!!.size)
        assertEquals("srv-1", servers.first().id)
        assertEquals(
            "https://cdn.example.com/vod.m3u8",
            TrailerPlayPipeline.peekCachedVideo("srv-1")?.source,
        )
    }

    @Test
    fun invalidate_clearsServersAndWarmedVideos() {
        val server = Video.Server(id = "srv-1", name = "A", src = "https://example.com/a")
        val video = Video(source = "https://cdn.example.com/vod.m3u8")
        TrailerPlayPipeline.seedForTest("movie-1", listOf(server), video = video)

        TrailerPlayPipeline.invalidate("movie-1")

        assertNull(TrailerPlayPipeline.peekCachedServers("movie-1"))
        assertNull(TrailerPlayPipeline.peekCachedVideo("srv-1"))
    }

    @Test
    fun peek_expiresAfterTtl() {
        val server = Video.Server(id = "srv-1", name = "A", src = "https://example.com/a")
        TrailerPlayPipeline.seedForTest("movie-1", listOf(server))
        TrailerPlayPipeline.elapsedRealtimeMs = { 10_000L + 181_000L }

        assertNull(TrailerPlayPipeline.peekCachedServers("movie-1"))
    }

    @Test
    fun beginSession_storesActiveSession() {
        val session = TrailerPlayPipeline.Session(
            target = TrailerPlayPipeline.Target(
                contentId = "m1",
                videoType = Video.Type.Movie(
                    id = "m1",
                    title = "T",
                    releaseDate = "",
                    poster = "",
                    imdbId = null,
                ),
            ),
            onStreamNow = {},
        )
        TrailerPlayPipeline.beginSession(session)
        assertEquals(session, TrailerPlayPipeline.activeSession)
        TrailerPlayPipeline.endSession()
        assertNull(TrailerPlayPipeline.activeSession)
    }
}
