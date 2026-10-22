package com.betterstreamflix.app.ui

import com.betterstreamflix.app.models.WatchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailWatchLabelsTest {

    @Test
    fun progressPercent_midResume() {
        val history = WatchItem.WatchHistory(
            lastEngagementTimeUtcMillis = 0L,
            lastPlaybackPositionMillis = 30_000L,
            durationMillis = 100_000L,
        )
        assertEquals(30, DetailWatchLabels.progressPercent(history))
    }

    @Test
    fun progressPercent_missingOrInvalid() {
        assertEquals(0, DetailWatchLabels.progressPercent(null))
        assertEquals(
            0,
            DetailWatchLabels.progressPercent(
                WatchItem.WatchHistory(
                    lastEngagementTimeUtcMillis = 0L,
                    lastPlaybackPositionMillis = 10L,
                    durationMillis = 0L,
                ),
            ),
        )
    }

    @Test
    fun showsEpisodePicker_onlyWithoutAnEpisode() {
        assertTrue(DetailWatchLabels.showsEpisodePicker(iptv = false, episodeNumber = null))
        assertFalse(DetailWatchLabels.showsEpisodePicker(iptv = true, episodeNumber = null))
        assertFalse(DetailWatchLabels.showsEpisodePicker(iptv = false, episodeNumber = 2))
    }

    @Test
    fun formatPosition_minutesAndHours() {
        assertEquals("1:05", DetailWatchLabels.formatPosition(65_000L))
        assertEquals("1:02:03", DetailWatchLabels.formatPosition(3_723_000L))
    }

    @Test
    fun remainingMinutes_roundsUpLeftTime() {
        val history = WatchItem.WatchHistory(
            lastEngagementTimeUtcMillis = 0L,
            lastPlaybackPositionMillis = 30_000L,
            durationMillis = 100_000L,
        )
        // 70s left → 2 minutes
        assertEquals(2, DetailWatchLabels.remainingMinutes(history))
        assertEquals(null, DetailWatchLabels.remainingMinutes(null))
        assertEquals(
            null,
            DetailWatchLabels.remainingMinutes(
                WatchItem.WatchHistory(
                    lastEngagementTimeUtcMillis = 0L,
                    lastPlaybackPositionMillis = 100_000L,
                    durationMillis = 100_000L,
                ),
            ),
        )
    }
}
