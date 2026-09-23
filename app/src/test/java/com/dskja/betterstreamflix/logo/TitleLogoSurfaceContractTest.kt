package com.dskja.betterstreamflix.logo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM stand-in for instrumented/UI logo slot + toggle coverage. */
class TitleLogoSurfaceContractTest {

    @Test
    fun slot_noUrlShowsTitle() {
        assertEquals(
            TitleLogoSlot.State.SHOW_TITLE,
            TitleLogoSlot.state(logoUrl = null, hideUntilReady = true),
        )
        assertEquals(
            TitleLogoSlot.State.SHOW_TITLE,
            TitleLogoSlot.state(logoUrl = "  ", hideUntilReady = true),
        )
    }

    @Test
    fun slot_hideUntilReadyLoadsInvisible() {
        assertEquals(
            TitleLogoSlot.State.LOADING_LOGO,
            TitleLogoSlot.state(
                logoUrl = "https://image.tmdb.org/t/p/original/x.png",
                hideUntilReady = true,
            ),
        )
    }

    @Test
    fun slot_readyShowsLogo() {
        assertEquals(
            TitleLogoSlot.State.SHOW_LOGO,
            TitleLogoSlot.state(
                logoUrl = "https://image.tmdb.org/t/p/original/x.png",
                hideUntilReady = true,
                loadReady = true,
            ),
        )
    }

    @Test
    fun slot_failedFallsBackToTitle() {
        assertEquals(
            TitleLogoSlot.State.SHOW_TITLE,
            TitleLogoSlot.state(
                logoUrl = "https://image.tmdb.org/t/p/original/x.png",
                hideUntilReady = true,
                loadFailed = true,
            ),
        )
    }

    @Test
    fun upgradeGate_blankNeedsUpgrade() {
        assertTrue(TmdbLogoPicker.shouldUpgradeLogo(null))
        assertTrue(TmdbLogoPicker.shouldUpgradeLogo(""))
        assertTrue(TmdbLogoPicker.shouldUpgradeLogo("https://cdn.provider/logo.png"))
    }

    @Test
    fun upgradeGate_trustedTmdbSkipsUpgrade() {
        assertFalse(
            TmdbLogoPicker.shouldUpgradeLogo("https://image.tmdb.org/t/p/original/logo.png"),
        )
        assertTrue(TmdbLogoPicker.isTrustedTmdbLogo("https://image.tmdb.org/t/p/w500/logo.png"))
    }

    @Test
    fun telemetry_snapshotTracksCounters() {
        TmdbLogoTelemetry.reset()
        TmdbLogoTelemetry.recordCacheHit()
        TmdbLogoTelemetry.recordResolve()
        val snap = TmdbLogoTelemetry.snapshot()
        assertEquals(1, snap.cacheHits)
        assertEquals(1, snap.resolves)
        assertTrue(TmdbLogoTelemetry.debugSummary().contains("hits=1"))
        TmdbLogoTelemetry.reset()
    }
}
