package com.betterstreamflix.app.iptv

import com.betterstreamflix.app.models.Video
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
        val provider = "DaddyLive TV"
        val channelId = "ch-1"
        val server = Video.Server(id = "srv-1", name = "A", src = "https://example.com/a.m3u8")
        val video = Video(source = "https://cdn.example.com/live.m3u8")

        IptvZapPreloader.seedForTest(provider, channelId, listOf(server), video)

        assertNotNull(IptvZapPreloader.peekCachedServers(provider, channelId))
        assertNotNull(IptvZapPreloader.peekCachedVideo(server.id))
        assertEquals("https://cdn.example.com/live.m3u8", IptvZapPreloader.peekCachedVideo(server.id)?.source)

        IptvZapPreloader.invalidate(provider, channelId)

        assertNull(IptvZapPreloader.peekCachedServers(provider, channelId))
        assertNull(IptvZapPreloader.peekCachedVideo(server.id))
    }

    @Test
    fun cacheKeyIsProviderAndChannelId() {
        val serverA = Video.Server(id = "srv-a", name = "A", src = "https://a.example/a.m3u8")
        val serverB = Video.Server(id = "srv-b", name = "B", src = "https://b.example/b.m3u8")
        val channelId = "42"

        IptvZapPreloader.seedForTest("DaddyLive TV", channelId, listOf(serverA))
        IptvZapPreloader.seedForTest("StreamSports99", channelId, listOf(serverB))

        assertEquals(
            "srv-a",
            IptvZapPreloader.peekCachedServers("DaddyLive TV", channelId)?.first()?.id,
        )
        assertEquals(
            "srv-b",
            IptvZapPreloader.peekCachedServers("StreamSports99", channelId)?.first()?.id,
        )
        assertEquals("DaddyLive TV|42", IptvZapPreloader.cacheKey("DaddyLive TV", "42"))
    }
}
