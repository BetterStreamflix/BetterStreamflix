package com.dskja.betterstreamflix.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamFailureReasonTest {

    @Test
    fun classifiesCaptcha() {
        val p = StreamFailureReason.classify(Exception("Cloudflare captcha challenge"))
        assertEquals(StreamFailureReason.Kind.CAPTCHA, p.kind)
        assertEquals(StreamFailureReason.Action.SOLVE_CAPTCHA, p.action)
    }

    @Test
    fun classifiesSerienStreamGateWithoutCfKeywords() {
        val p = StreamFailureReason.classify(
            Exception("SerienStream stream gate is still active. Complete verification, then Continue."),
        )
        assertEquals(StreamFailureReason.Kind.CAPTCHA, p.kind)
        assertEquals(StreamFailureReason.Action.SOLVE_CAPTCHA, p.action)
    }

    @Test
    fun classifiesTmdbDeSerienStreamIdAsCaptcha() {
        val p = StreamFailureReason.classify(
            Exception("tmdbde:serienstream:/serie/foo/staffel-1/episode-2 failed"),
        )
        assertEquals(StreamFailureReason.Kind.CAPTCHA, p.kind)
    }

    @Test
    fun classifiesGeo451() {
        val p = StreamFailureReason.classify(Exception("Response code: 451"))
        assertEquals(StreamFailureReason.Kind.GEO, p.kind)
        assertEquals(StreamFailureReason.Action.NONE, p.action)
    }

    @Test
    fun looseGeoTokenAloneIsNotGeo() {
        val p = StreamFailureReason.classify(Exception("geometry metadata missing"))
        assertFalse(p.kind == StreamFailureReason.Kind.GEO)
    }

    @Test
    fun classifiesTimeout() {
        val p = StreamFailureReason.classify(Exception("getVideo(VOE) timed out after 40000ms"))
        assertEquals(StreamFailureReason.Kind.TIMEOUT, p.kind)
    }

    @Test
    fun failedToConnectAloneIsNotTimeout() {
        val p = StreamFailureReason.classify(Exception("Failed to connect to hoster.example"))
        assertFalse(p.kind == StreamFailureReason.Kind.TIMEOUT)
    }

    @Test
    fun classifiesDomainDead() {
        val p = StreamFailureReason.classify(Exception("Unable to resolve host serienstream.invalid"))
        assertEquals(StreamFailureReason.Kind.DOMAIN_DEAD, p.kind)
    }

    @Test
    fun serversExhaustedFallsBackToAllServers() {
        val p = StreamFailureReason.classify(
            error = Exception("Source not found"),
            serversExhausted = true,
        )
        assertEquals(StreamFailureReason.Kind.ALL_SERVERS, p.kind)
    }
}
