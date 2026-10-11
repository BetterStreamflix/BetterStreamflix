package com.dskja.betterstreamflix.watchlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class WatchlistDigestPolicyTest {

    @Test
    fun cooldownConstantIsSevenDays() {
        assertEquals(7L * 24 * 60 * 60 * 1000, WatchlistDigestPolicy.COOLDOWN_MS)
        assertEquals(TimeUnit.DAYS.toMillis(7), WatchlistDigestPolicy.COOLDOWN_MS)
    }

    @Test
    fun maxHomeCardShowsIsPositive() {
        assertTrue(WatchlistDigestPolicy.MAX_HOME_CARD_SHOWS in 1..24)
    }
}

class WatchlistDigestPlannerTest {

    @Test
    fun selectCapsAtFiveAndKeepsFavoriteOrder() {
        val eps = (1..8).associate { i ->
            "p:show$i" to com.dskja.betterstreamflix.models.Episode(
                id = "e$i",
                number = i,
                title = "Ep $i",
            )
        }
        val keys = (1..8).map { "p:show$it" }
        val picked = WatchlistDigestPlanner.selectDigestEpisodes(keys, eps, max = 5)
        assertEquals(5, picked.size)
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5"), picked.map { it.id })
    }

    @Test
    fun selectSkipsMissingShowsAndDedupesKeys() {
        val eps = mapOf(
            "p:a" to com.dskja.betterstreamflix.models.Episode(id = "ea", number = 1),
            "p:c" to com.dskja.betterstreamflix.models.Episode(id = "ec", number = 3),
        )
        val keys = listOf("p:a", "p:b", "p:a", "p:c")
        val picked = WatchlistDigestPlanner.selectDigestEpisodes(keys, eps, max = 5)
        assertEquals(listOf("ea", "ec"), picked.map { it.id })
    }

    @Test
    fun selectEmptyWhenNoNext() {
        val picked = WatchlistDigestPlanner.selectDigestEpisodes(
            favoriteKeysInOrder = listOf("p:a"),
            nextByShowKey = emptyMap(),
        )
        assertTrue(picked.isEmpty())
    }

    @Test
    fun maxEpisodesConstantIsFive() {
        assertEquals(5, WatchlistDigestPlanner.MAX_EPISODES)
    }
}
