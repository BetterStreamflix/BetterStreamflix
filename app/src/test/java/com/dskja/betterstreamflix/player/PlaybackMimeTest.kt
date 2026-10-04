package com.dskja.betterstreamflix.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackMimeTest {
    @Test
    fun hlsUrlOverridesGenericMp4Mime() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            PlaybackMime.forPlayback("video/mp4", "https://cdn.example/master.m3u8"),
        )
    }

    @Test
    fun explicitNonGenericMimeIsKept() {
        assertEquals(
            "application/x-mpegURL",
            PlaybackMime.forPlayback("application/x-mpegURL", "https://cdn.example/master.m3u8"),
        )
    }
}
