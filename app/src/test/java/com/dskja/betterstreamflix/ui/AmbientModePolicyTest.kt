package com.dskja.betterstreamflix.ui

import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pure AmbientModePolicy contract — enter/exit, pool, burn-in, high-end gate.
 */
class AmbientModePolicyTest {

    @Test
    fun shouldEnter_requiresIdlePoolAndHighEnd() {
        assertTrue(
            AmbientModePolicy.shouldEnter(
                enabled = true,
                highEndOk = true,
                idleElapsedMs = AmbientModePolicy.IDLE_ENTER_MS,
                poolSize = 3,
                modalBlocking = false,
                alreadyActive = false,
            ),
        )
        assertFalse(
            AmbientModePolicy.shouldEnter(
                enabled = false,
                highEndOk = true,
                idleElapsedMs = AmbientModePolicy.IDLE_ENTER_MS,
                poolSize = 3,
                modalBlocking = false,
                alreadyActive = false,
            ),
        )
        assertFalse(
            AmbientModePolicy.shouldEnter(
                enabled = true,
                highEndOk = false,
                idleElapsedMs = AmbientModePolicy.IDLE_ENTER_MS,
                poolSize = 3,
                modalBlocking = false,
                alreadyActive = false,
            ),
        )
        assertFalse(
            AmbientModePolicy.shouldEnter(
                enabled = true,
                highEndOk = true,
                idleElapsedMs = AmbientModePolicy.IDLE_ENTER_MS - 1,
                poolSize = 3,
                modalBlocking = false,
                alreadyActive = false,
            ),
        )
        assertFalse(
            AmbientModePolicy.shouldEnter(
                enabled = true,
                highEndOk = true,
                idleElapsedMs = AmbientModePolicy.IDLE_ENTER_MS,
                poolSize = 0,
                modalBlocking = false,
                alreadyActive = false,
            ),
        )
        assertFalse(
            AmbientModePolicy.shouldEnter(
                enabled = true,
                highEndOk = true,
                idleElapsedMs = AmbientModePolicy.IDLE_ENTER_MS,
                poolSize = 3,
                modalBlocking = true,
                alreadyActive = false,
            ),
        )
        assertFalse(
            AmbientModePolicy.shouldEnter(
                enabled = true,
                highEndOk = true,
                idleElapsedMs = AmbientModePolicy.IDLE_ENTER_MS,
                poolSize = 3,
                modalBlocking = false,
                alreadyActive = true,
            ),
        )
    }

    @Test
    fun highEndOk_blocksHardReduceTalkBackAnimatorAndHighContrast_allowsStickEnergyAlone() {
        // Stick Energy alone is not hardReduceEffects — Ambient may enter.
        assertTrue(
            AmbientModePolicy.highEndOk(
                hardReduceEffects = false,
                touchExploration = false,
                reduceMotion = false,
                highContrast = false,
            ),
        )
        assertFalse(
            AmbientModePolicy.highEndOk(
                hardReduceEffects = true,
                touchExploration = false,
                reduceMotion = false,
            ),
        )
        assertFalse(
            AmbientModePolicy.highEndOk(
                hardReduceEffects = false,
                touchExploration = true,
                reduceMotion = false,
            ),
        )
        assertFalse(
            AmbientModePolicy.highEndOk(
                hardReduceEffects = false,
                touchExploration = false,
                reduceMotion = true,
            ),
        )
        assertFalse(
            AmbientModePolicy.highEndOk(
                hardReduceEffects = false,
                touchExploration = false,
                reduceMotion = false,
                highContrast = true,
            ),
        )
    }

    @Test
    fun kenBurns_reducedWhenStickEnergy() {
        assertTrue(
            AmbientModePolicy.kenBurnsScaleEnd(stickEnergy = true) <
                AmbientModePolicy.kenBurnsScaleEnd(stickEnergy = false),
        )
        assertTrue(
            AmbientModePolicy.kenBurnsDurationMs(stickEnergy = true) <
                AmbientModePolicy.kenBurnsDurationMs(stickEnergy = false),
        )
    }

    @Test
    fun resolvePrefEnabled_defaultsTrue() {
        assertTrue(AmbientModePolicy.resolvePrefEnabled(null))
        assertTrue(AmbientModePolicy.resolvePrefEnabled(true))
        assertFalse(AmbientModePolicy.resolvePrefEnabled(false))
    }

    @Test
    fun nextImageIndex_wraps() {
        assertEquals(0, AmbientModePolicy.nextImageIndex(0, 0))
        assertEquals(0, AmbientModePolicy.nextImageIndex(0, 1))
        assertEquals(1, AmbientModePolicy.nextImageIndex(0, 3))
        assertEquals(0, AmbientModePolicy.nextImageIndex(2, 3))
    }

    @Test
    fun burnInOffset_staysWithinBounds() {
        for (tick in 0..40) {
            val (x, y) = AmbientModePolicy.burnInOffsetPx(tick)
            assertTrue(abs(x) <= AmbientModePolicy.MAX_SHIFT_PX + 0.01f)
            assertTrue(abs(y) <= AmbientModePolicy.MAX_SHIFT_PX + 0.01f)
        }
    }

    @Test
    fun buildPool_dedupesAndPrefersFeatured() {
        val featured = Movie(
            id = "m1",
            title = "Alpha",
            banner = "https://image.tmdb.org/t/p/w500/a.jpg",
        )
        val cwSame = Movie(
            id = "m1",
            title = "Alpha",
            poster = "https://image.tmdb.org/t/p/w185/a-poster.jpg",
        )
        val favorite = TvShow(
            id = "t1",
            title = "Beta",
            banner = "https://image.tmdb.org/t/p/w780/b.jpg",
        )
        val pool = AmbientModePolicy.buildPool(
            featured = listOf(featured),
            continueWatching = listOf(cwSame),
            favorites = listOf(favorite),
        )
        assertEquals(2, pool.size)
        assertEquals("Alpha", pool[0].title)
        assertEquals("Beta", pool[1].title)
    }

    @Test
    fun imageOf_episodeUsesShowArtwork() {
        val show = TvShow(
            id = "t9",
            title = "Severance",
            banner = "https://cdn.example.com/sev.jpg",
        )
        val episode = Episode(
            id = "e1",
            number = 1,
            title = "Good News",
            poster = null,
            tvShow = show,
        )
        val image = AmbientModePolicy.imageOf(episode)!!
        assertEquals("Severance", image.title)
        assertTrue(image.url.contains("sev.jpg"))
    }

    @Test
    fun shouldExitOnKey_keepsStripNav_exitsOnActivateBackAndOther() {
        assertTrue(AmbientModePolicy.shouldExitOnKey(AmbientModePolicy.KEYCODE_BACK, true))
        assertFalse(
            AmbientModePolicy.shouldExitOnKey(AmbientModePolicy.KEYCODE_DPAD_LEFT, true),
        )
        // Activate exits Ambient (click forwarded by controller).
        assertTrue(
            AmbientModePolicy.shouldExitOnKey(AmbientModePolicy.KEYCODE_DPAD_CENTER, true),
        )
        assertTrue(
            AmbientModePolicy.isStripActivateKey(AmbientModePolicy.KEYCODE_ENTER),
        )
        assertTrue(
            AmbientModePolicy.shouldExitOnKey(AmbientModePolicy.KEYCODE_DPAD_LEFT, false),
        )
        // DPAD_UP (19) wakes Home.
        assertTrue(AmbientModePolicy.shouldExitOnKey(19, true))
    }
}
