package com.betterstreamflix.app.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeDisplayLabelsTest {
    @Test
    fun badgeIsZeroPadded() {
        assertEquals("E01", EpisodeDisplayLabels.badge(1))
        assertEquals("E09", EpisodeDisplayLabels.badge(9))
        assertEquals("E10", EpisodeDisplayLabels.badge(10))
        assertEquals("E100", EpisodeDisplayLabels.badge(100))
    }

    @Test
    fun seasonEpisodeIsZeroPadded() {
        assertEquals("S01E01", EpisodeDisplayLabels.seasonEpisode(1, 1))
        assertEquals("S02E10", EpisodeDisplayLabels.seasonEpisode(2, 10))
        assertEquals("S00E03", EpisodeDisplayLabels.seasonEpisode(0, 3))
    }

    @Test
    fun readableTitlePrefersNonBlank() {
        assertEquals(
            "Pilot",
            EpisodeDisplayLabels.readableTitle("Pilot", 1) { "Episode $it" },
        )
        assertEquals(
            "Pilot",
            EpisodeDisplayLabels.readableTitle("  Pilot  ", 1) { "Episode $it" },
        )
        assertEquals(
            "Episode 3",
            EpisodeDisplayLabels.readableTitle(null, 3) { "Episode $it" },
        )
        assertEquals(
            "Episode 3",
            EpisodeDisplayLabels.readableTitle("   ", 3) { "Episode $it" },
        )
    }
}
