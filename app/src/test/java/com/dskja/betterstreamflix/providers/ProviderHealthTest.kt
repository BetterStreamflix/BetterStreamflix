package com.dskja.betterstreamflix.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHealthTest {

    @Test
    fun quarantineContainsKnownFragileProviders() {
        assertTrue(ProviderHealth.isQuarantinedName("Fanpelis"))
        assertTrue(ProviderHealth.isQuarantinedName("Kidraz"))
        assertTrue(ProviderHealth.isQuarantinedName("MKissa"))
        assertTrue(ProviderHealth.isQuarantinedName("StreamingIta"))
        assertTrue(ProviderHealth.isQuarantinedName("SportsBite"))
        assertFalse(ProviderHealth.isQuarantinedName("Eurostreaming"))
        assertFalse(ProviderHealth.isQuarantinedName("Altadefinizione"))
        assertFalse(ProviderHealth.isQuarantinedName("SerienStream"))
        assertFalse(ProviderHealth.isQuarantinedName("StreamingCommunity"))
        assertFalse(ProviderHealth.isQuarantinedName("DaddyLive TV"))
        assertFalse(ProviderHealth.isQuarantinedName("MAGISTV"))
    }

    @Test
    fun smokeListCoversCoreMarkets() {
        val names = ProviderHealth.topSmokeNames
        assertTrue(names.contains("SFlix"))
        assertTrue(names.contains("SerienStream"))
        assertTrue(names.contains("GuardaFlix"))
        assertTrue(names.contains("Eurostreaming"))
        assertTrue(names.contains("Altadefinizione"))
        assertTrue(names.contains("HDFilme"))
        assertTrue(names.contains("Frembed"))
        assertTrue(names.contains("Filmpalast"))
        assertFalse(names.contains("Sflix"))
    }

    @Test
    fun pickerRankSinksOpenCircuits() {
        ProviderSmoke.noteHomeSuccess("RankProbe")
        val healthy = ProviderHealth.pickerRank("SerienStream")
        repeat(ProviderSmoke.CIRCUIT_FAILURE_THRESHOLD) {
            ProviderSmoke.noteHomeFailure("RankProbe")
        }
        val open = ProviderHealth.pickerRank("RankProbe")
        assertTrue(open > healthy)
        ProviderSmoke.noteHomeSuccess("RankProbe")
    }
}
