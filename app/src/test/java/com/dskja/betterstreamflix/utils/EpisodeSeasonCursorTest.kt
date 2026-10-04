package com.dskja.betterstreamflix.utils

import com.dskja.betterstreamflix.models.Season
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeSeasonCursorTest {

    @Test
    fun skipsCurrentAndWalksLaterSeasonsInOrder() {
        val seasons = listOf(
            Season(id = "s3", number = 3),
            Season(id = "s0", number = 0),
            Season(id = "s1", number = 1),
            Season(id = "s2", number = 2),
            Season(id = "s2b", number = 2),
        )
        val next = EpisodeSeasonCursor.upcoming(1, seasons)
        assertEquals(listOf(2, 3), next.map { it.number })
        assertEquals("s2", next.first().id)
    }

    @Test
    fun capsHowManySeasonsAutoplayWillProbe() {
        val seasons = (1..8).map { Season(id = "s$it", number = it) }
        assertEquals(listOf(2, 3, 4, 5), EpisodeSeasonCursor.upcoming(1, seasons).map { it.number })
    }
}
