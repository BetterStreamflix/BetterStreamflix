package com.dskja.betterstreamflix.download

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTrackPlanTest {
    @Test
    fun bestOptionDownloadsEveryTrack() {
        val options = DownloadTrackPlan.build(
            tracks = sample(),
            bestLabel = "Best",
            dataSaverLabel = "Saver",
        )
        assertEquals("Best", options.first().label)
        assertTrue(options.first().streamKeys.isEmpty())
    }

    @Test
    fun renditionKeepsAudioAndTextKeys() {
        val options = DownloadTrackPlan.build(sample(), "Best", "Saver")
        val hd = options.first { it.label == "1080p" }
        assertEquals(3, hd.streamKeys.size)
        assertTrue(hd.streamKeys.any { it.groupIndex == 0 && it.streamIndex == 0 })
        assertTrue(hd.streamKeys.any { it.groupIndex == 1 })
        assertTrue(hd.streamKeys.any { it.groupIndex == 2 })
    }

    @Test
    fun dataSaverUsesLowestVideoPlusAudio() {
        val options = DownloadTrackPlan.build(sample(), "Best", "Saver")
        val saver = options.last()
        assertEquals("Saver", saver.label)
        assertTrue(saver.streamKeys.any { it.groupIndex == 0 && it.streamIndex == 1 })
        assertTrue(saver.streamKeys.any { it.groupIndex == 1 })
    }

    private fun sample() = listOf(
        DownloadTrackPlan.TrackRef(C.TRACK_TYPE_VIDEO, 0, 0, 0, 1080),
        DownloadTrackPlan.TrackRef(C.TRACK_TYPE_VIDEO, 0, 0, 1, 480),
        DownloadTrackPlan.TrackRef(C.TRACK_TYPE_AUDIO, 0, 1, 0, 0),
        DownloadTrackPlan.TrackRef(C.TRACK_TYPE_TEXT, 0, 2, 0, 0),
    )
}
