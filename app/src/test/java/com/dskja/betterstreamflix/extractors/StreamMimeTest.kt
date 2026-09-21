package com.dskja.betterstreamflix.extractors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamMimeTest {

    @Test
    fun infersHlsFromM3u8() {
        assertEquals(
            "application/x-mpegURL",
            StreamMime.infer("https://cdn.example/hls/master.m3u8?token=1"),
        )
    }

    @Test
    fun infersDashFromMpd() {
        assertEquals(
            "application/dash+xml",
            StreamMime.infer("https://cdn.example/manifest.mpd"),
        )
    }

    @Test
    fun coalescePrefersExplicit() {
        assertEquals(
            "video/mp4",
            StreamMime.coalesce("video/mp4", "https://cdn.example/master.m3u8"),
        )
    }

    @Test
    fun unknownIsNull() {
        assertNull(StreamMime.infer("https://cdn.example/stream"))
    }

    @Test
    fun infersHlsFromPathHintWithoutExtension() {
        assertEquals(
            "application/x-mpegURL",
            StreamMime.infer("https://cdn.example/hls/tokenized?sig=abc"),
        )
    }

    @Test
    fun infersHlsFromFormatQuery() {
        assertEquals(
            "application/x-mpegURL",
            StreamMime.infer("https://cdn.example/play?format=m3u8&id=1"),
        )
    }
}
