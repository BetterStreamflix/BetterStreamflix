package com.dskja.betterstreamflix.iptv

import com.dskja.betterstreamflix.models.Video
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Before
import org.junit.Test

class IptvZapPreloaderTest {

    @Before
    fun setUp() {
        IptvZapPreloader.elapsedRealtimeMs = { 10_000L }
        IptvZapPreloader.clear()
    }

    @After
    fun tearDown() {
        IptvZapPreloader.clear()
        // Restore default; production paths use SystemClock via this lambda.
        IptvZapPreloader.elapsedRealtimeMs = {
            try {
                android.os.SystemClock.elapsedRealtime()
            } catch (_: RuntimeException) {
                System.nanoTime() / 1_000_000L
            }
        }
    }

    @Test
    fun invalidate_clearsServersAndWarmedVideos() {
        val channelId = "ch-1"
        val server = Video.Server(id = "srv-1", name = "A", src = "https://example.com/a.m3u8")
        val video = Video(source = "https://cdn.example.com/live.m3u8")

        IptvZapPreloader.seedForTest(channelId, listOf(server), video)

        assertNotNull(IptvZapPreloader.peekCachedServers(channelId))
        assertNotNull(IptvZapPreloader.peekCachedVideo(server.id))
        assertEquals("https://cdn.example.com/live.m3u8", IptvZapPreloader.peekCachedVideo(server.id)?.source)

        IptvZapPreloader.invalidate(channelId)

        assertNull(IptvZapPreloader.peekCachedServers(channelId))
        assertNull(IptvZapPreloader.peekCachedVideo(server.id))
    }
}
