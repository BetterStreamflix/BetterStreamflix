package com.betterstreamflix.app.watchlist

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

    private fun recentEpisode(id: String, number: Int, title: String? = null) =
        com.betterstreamflix.app.models.Episode(
            id = id,
            number = number,
            title = title,
            released = java.time.LocalDate.now().minusDays(2).toString(),
        )

    @Test
    fun selectCapsAtFiveAndKeepsFavoriteOrder() {
        val eps = (1..8).associate { i ->
            "p:show$i" to recentEpisode("e$i", i, "Ep $i")
        }
        val keys = (1..8).map { "p:show$it" }
        val picked = WatchlistDigestPlanner.selectDigestEpisodes(keys, eps, max = 5)
        assertEquals(5, picked.size)
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5"), picked.map { it.id })
    }

    @Test
    fun selectSkipsMissingShowsAndDedupesKeys() {
        val eps = mapOf(
            "p:a" to recentEpisode("ea", 1),
            "p:c" to recentEpisode("ec", 3),
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
    fun selectSkipsStaleAirDates() {
        val stale = com.betterstreamflix.app.models.Episode(
            id = "old",
            number = 1,
            released = java.time.LocalDate.now().minusDays(40).toString(),
        )
        val picked = WatchlistDigestPlanner.selectDigestEpisodes(
            favoriteKeysInOrder = listOf("p:a"),
            nextByShowKey = mapOf("p:a" to stale),
        )
        assertTrue(picked.isEmpty())
    }

    @Test
    fun maxEpisodesConstantIsFive() {
        assertEquals(5, WatchlistDigestPlanner.MAX_EPISODES)
    }
}
