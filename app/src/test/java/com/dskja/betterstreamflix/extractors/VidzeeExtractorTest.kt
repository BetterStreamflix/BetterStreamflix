package com.dskja.betterstreamflix.extractors

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VidzeeExtractorTest {

    @Test
    fun parseServerLink_movieQuery() {
        val params = VidzeeDirectStream.parseServerLink(
            "https://player.vidzee.wtf/api/server?id=550&sr=0",
        )
        assertEquals("550", params!!.tmdbId)
        assertNull(params.season)
        assertNull(params.episode)
        assertEquals(false, params.isEpisode)
    }

    @Test
    fun parseServerLink_episodeQuery() {
        val params = VidzeeDirectStream.parseServerLink(
            "https://player.vidzee.wtf/api/server?id=1399&ss=1&ep=2&sr=3",
        )
        assertEquals("1399", params!!.tmdbId)
        assertEquals("1", params.season)
        assertEquals("2", params.episode)
        assertEquals(true, params.isEpisode)
        assertEquals(3, params.sr)
    }

    @Test
    fun preferredDirectServers_mapsSrToBackend() {
        assertEquals(
            listOf("ipcloud", "dcloud", "tik"),
            VidzeeDirectStream.preferredDirectServers(sr = 9, isEpisode = true),
        )
        assertEquals(
            listOf("dcloud", "tik", "ipcloud"),
            VidzeeDirectStream.preferredDirectServers(sr = 1, isEpisode = false),
        )
        assertEquals(
            listOf("dcloud", "tik", "ipcloud"),
            VidzeeDirectStream.preferredDirectServers(sr = 0, isEpisode = false),
        )
    }

    @Test
    fun parseServerLink_missingIdReturnsNull() {
        assertNull(
            VidzeeDirectStream.parseServerLink(
                "https://player.vidzee.wtf/api/server?sr=0",
            ),
        )
    }

    @Test
    fun directStreamEndpoints_movieOrderPrefersDcloud() {
        val endpoints = VidzeeDirectStream.directStreamEndpoints(
            coreApi = "https://core.vidzee.wtf",
            tmdbId = "550",
            season = null,
            episode = null,
            sr = 0,
        )
        assertEquals(
            listOf("dcloud", "tik", "ipcloud"),
            endpoints.map { it.first },
        )
        assertEquals(
            "https://core.vidzee.wtf/streams/movie/550?s=dcloud&e=0",
            endpoints.first().second,
        )
    }

    @Test
    fun directStreamEndpoints_tvOrderPrefersIpcloud() {
        val endpoints = VidzeeDirectStream.directStreamEndpoints(
            coreApi = "https://core.vidzee.wtf",
            tmdbId = "1399",
            season = "1",
            episode = "2",
        )
        assertEquals(
            listOf("ipcloud", "dcloud", "tik"),
            endpoints.map { it.first },
        )
        assertEquals(
            "https://core.vidzee.wtf/streams/tv/1399/1/2?s=ipcloud&e=0",
            endpoints.first().second,
        )
    }

    @Test
    fun parseDirectStreamResponse_goldenJson() {
        val video = VidzeeDirectStream.parseDirectStreamResponse(
            raw = GOLDEN_DIRECT_JSON,
            mainUrl = "https://player.vidzee.wtf",
        )
        assertEquals("https://cdn.example/path/master.m3u8", video!!.source)
        assertEquals(MimeTypes.APPLICATION_M3U8, video.type)
        assertEquals("https://player.vidzee.wtf/", video.headers!!["Referer"])
        assertEquals("https://player.vidzee.wtf", video.headers!!["Origin"])
        assertEquals("Bearer abc", video.headers!!["Authorization"])
        assertTrue(video.headers!!["User-Agent"]!!.contains("Chrome/140"))
    }

    @Test
    fun parseDirectStreamResponse_mp4WithoutHeadersObject() {
        val video = VidzeeDirectStream.parseDirectStreamResponse(
            raw = """{"url":"https://cdn.example/file.mp4"}""",
            mainUrl = "https://player.vidzee.wtf",
        )
        assertEquals("https://cdn.example/file.mp4", video!!.source)
        assertEquals(MimeTypes.VIDEO_MP4, video.type)
    }

    @Test
    fun parseDirectStreamResponse_blankUrlReturnsNull() {
        assertNull(
            VidzeeDirectStream.parseDirectStreamResponse(
                raw = """{"url":"   "}""",
                mainUrl = "https://player.vidzee.wtf",
            ),
        )
    }

    companion object {
        private const val GOLDEN_DIRECT_JSON = """
            {
              "url": "https://cdn.example/path/master.m3u8",
              "headers": {
                "Authorization": "Bearer abc"
              }
            }
        """
    }
}
