package com.dskja.betterstreamflix.logo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TmdbLogoCacheConcurrencyTest {

    @Test
    fun singleFlight_coalescesParallelWaiters() = runBlocking {
        TmdbLogoCache.clearAll()
        val key = "flight-test"
        val leader = CompletableDeferred<String?>()
        val winner = TmdbLogoCache.putInflight(key, leader)
        assertSame(leader, winner)

        val second = CompletableDeferred<String?>()
        val again = TmdbLogoCache.putInflight(key, second)
        assertSame(leader, again)

        val waiters = List(4) {
            async {
                TmdbLogoCache.getInflight(key)!!.await()
            }
        }
        delay(20)
        leader.complete("https://image.tmdb.org/t/p/original/x.png")
        TmdbLogoCache.removeInflight(key, leader)
        val results = waiters.awaitAll()
        assertEquals(4, results.size)
        assertTrue(results.all { it == "https://image.tmdb.org/t/p/original/x.png" })
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
