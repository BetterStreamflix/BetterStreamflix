package com.dskja.betterstreamflix.providers

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeoutException

class ProviderSmokeTest {

    @Before
    fun clearState() {
        // Success clears circuit state between tests.
        ProviderSmoke.noteHomeSuccess("CircuitProbe")
    }

    @Test
    fun circuitOpensAfterThresholdFailures() {
        val name = "CircuitProbe"
        repeat(ProviderSmoke.CIRCUIT_FAILURE_THRESHOLD) {
            ProviderSmoke.noteHomeFailure(name)
        }
        assertTrue(ProviderSmoke.isHomeCircuitOpen(name))
        assertTrue(ProviderSmoke.failureCount(name) >= ProviderSmoke.CIRCUIT_FAILURE_THRESHOLD)
        assertTrue(ProviderSmoke.circuitHint(name)!!.contains(name))
    }

    @Test
    fun successClearsCircuit() {
        val name = "CircuitProbe"
        repeat(ProviderSmoke.CIRCUIT_FAILURE_THRESHOLD) {
            ProviderSmoke.noteHomeFailure(name)
        }
        ProviderSmoke.noteHomeSuccess(name)
        assertFalse(ProviderSmoke.isHomeCircuitOpen(name))
        assertEquals(0, ProviderSmoke.failureCount(name))
        assertNull(ProviderSmoke.lastFailureAt(name))
    }

    @Test
    fun belowThresholdDoesNotOpenCircuit() {
        val name = "CircuitProbeSoft"
        ProviderSmoke.noteHomeSuccess(name)
        repeat(ProviderSmoke.CIRCUIT_FAILURE_THRESHOLD - 1) {
            ProviderSmoke.noteHomeFailure(name)
        }
        assertFalse(ProviderSmoke.isHomeCircuitOpen(name))
        assertTrue(ProviderSmoke.failureCount(name) > 0)
        ProviderSmoke.noteHomeSuccess(name)
    }

    @Test
    fun withProviderTimeoutSurfacesTimeoutExceptionNotCancellation() = runBlocking {
        try {
            ProviderSmoke.withProviderTimeout(50L, "hangProbe") {
                delay(500L)
                "ok"
            }
            fail("expected TimeoutException")
        } catch (e: TimeoutException) {
            assertTrue(e.message!!.contains("hangProbe"))
        }
    }

    @Test
    fun withProviderTimeoutReturnsValueWhenFast() = runBlocking {
        val value = ProviderSmoke.withProviderTimeout(500L, "fastProbe") {
            delay(10L)
            42
        }
        assertEquals(42, value)
    }

    @Test
    fun timeoutConstantsArePositive() {
        assertTrue(ProviderSmoke.HOME_TIMEOUT_MS > 0)
        assertTrue(ProviderSmoke.SERVERS_TIMEOUT_MS > 0)
        assertTrue(ProviderSmoke.CATALOG_TIMEOUT_MS > 0)
        assertTrue(ProviderSmoke.SEARCH_TIMEOUT_MS > 0)
        assertTrue(ProviderSmoke.DETAIL_TIMEOUT_MS > 0)
        assertTrue(ProviderSmoke.CW_ENRICH_TIMEOUT_MS > 0)
        assertTrue(ProviderSmoke.CW_ENRICH_TIMEOUT_MS < ProviderSmoke.HOME_TIMEOUT_MS)
        assertTrue(ProviderSmoke.TMDB_HOME_TIMEOUT_MS > 0)
        assertTrue(ProviderSmoke.TMDB_HOME_TIMEOUT_MS <= ProviderSmoke.HOME_TIMEOUT_MS)
        assertTrue(ProviderSmoke.TMDB_SHELF_TIMEOUT_MS > 0)
        assertTrue(ProviderSmoke.TMDB_SHELF_TIMEOUT_MS < ProviderSmoke.TMDB_HOME_TIMEOUT_MS)
        assertTrue(ProviderSmoke.TMDB_ENRICH_TIMEOUT_MS > 0)
        assertTrue(ProviderSmoke.TMDB_LOGO_TIMEOUT_MS > 0)
    }
}
