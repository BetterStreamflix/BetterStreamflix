package com.betterstreamflix.app.providers

import com.betterstreamflix.app.models.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderServerAbTest {

    @Before
    fun setUp() {
        ProviderServerAb.memoryStore = mutableMapOf()
        ProviderServerAb.clearSession()
    }

    @Test
    fun scoreUnknownIsNeutral() {
        assertEquals(0.5, ProviderServerAb.score(null), 0.0001)
        assertEquals(0.5, ProviderServerAb.score(ProviderServerAb.Stats()), 0.0001)
    }

    @Test
    fun successesRaiseScoreAboveFailures() {
        val good = ProviderServerAb.Stats(successes = 8, failures = 1, lastSuccessAtMs = 1L)
        val bad = ProviderServerAb.Stats(successes = 1, failures = 8, lastSuccessAtMs = 1L)
        assertTrue(ProviderServerAb.score(good) > ProviderServerAb.score(bad))
    }

    @Test
    fun prioritizePutsStableHostersFirst() {
        ProviderServerAb.memoryStore = mutableMapOf(
            "SerienStream" to mutableMapOf(
                "voe" to ProviderServerAb.Stats(successes = 10, failures = 0, lastSuccessAtMs = 1L),
                "vidoza" to ProviderServerAb.Stats(successes = 0, failures = 10, lastSuccessAtMs = 0L),
            ),
        )
        val servers = listOf(
            Video.Server(id = "1", name = "Vidoza"),
            Video.Server(id = "2", name = "Streamtape"),
            Video.Server(id = "3", name = "VOE"),
        )
        val ranked = ProviderServerAb.prioritize("SerienStream", servers)
        assertEquals("VOE", ranked.first().name)
        assertEquals("Vidoza", ranked.last().name)
    }

    @Test
    fun preferredServerStaysFirstWhenRequested() {
        ProviderServerAb.memoryStore = mutableMapOf(
            "SerienStream" to mutableMapOf(
                "voe" to ProviderServerAb.Stats(successes = 10, failures = 0, lastSuccessAtMs = 1L),
            ),
        )
        val servers = listOf(
            Video.Server(id = "1", name = "Vidoza"),
            Video.Server(id = "2", name = "VOE"),
        )
        val ranked = ProviderServerAb.prioritize(
            "SerienStream",
            servers,
            preferredServerName = "Vidoza",
        )
        assertEquals("Vidoza", ranked.first().name)
        assertEquals("VOE", ranked[1].name)
    }

    @Test
    fun recordSuccessAndFailureRoundTrip() {
        val server = Video.Server(id = "x", name = "VOE")
        ProviderServerAb.recordSuccess("SerienStream", server, nowMs = 1000L)
        ProviderServerAb.clearSession()
        ProviderServerAb.recordFailure("SerienStream", server)
        val top = ProviderServerAb.topServers("SerienStream", limit = 1)
        assertEquals(1, top.size)
        assertEquals("voe", top[0].first)
        assertEquals(1, top[0].second.successes)
        assertEquals(1, top[0].second.failures)
    }

    @Test
    fun serializeParseRoundTrip() {
        val root = mapOf(
            "SerienStream" to mapOf(
                "voe" to ProviderServerAb.Stats(3, 1, 42L),
            ),
        )
        val json = ProviderServerAb.serialize(root)
        val parsed = ProviderServerAb.parse(json)
        assertEquals(3, parsed["SerienStream"]?.get("voe")?.successes)
        assertEquals(1, parsed["SerienStream"]?.get("voe")?.failures)
        assertEquals(42L, parsed["SerienStream"]?.get("voe")?.lastSuccessAtMs)
    }

    @Test
    fun learnedStableServersTitleCasesNames() {
        ProviderServerAb.memoryStore = mutableMapOf(
            "HDFilme" to mutableMapOf(
                "voe" to ProviderServerAb.Stats(5, 0, 1L),
                "doodstream" to ProviderServerAb.Stats(4, 1, 1L),
            ),
        )
        val learned = ProviderHealth.learnedStableServers("HDFilme", limit = 2)
        assertEquals(listOf("Voe", "Doodstream"), learned)
    }
}
