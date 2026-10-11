package com.dskja.betterstreamflix.ui

import com.dskja.betterstreamflix.utils.ThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cinema Room orchestration contract — master gate, ambient, looks, crossfade,
 * player chrome invariants, focus priority, a11y light-hints.
 */
class CinemaRoomPolicyTest {

    @Test
    fun resolveEnabled_defaultsOn() {
        assertTrue(CinemaRoomPolicy.resolveEnabled(null))
        assertTrue(CinemaRoomPolicy.resolveEnabled(true))
        assertFalse(CinemaRoomPolicy.resolveEnabled(false))
    }

    @Test
    fun ambientAllowed_requiresMasterAndSubToggle() {
        assertTrue(CinemaRoomPolicy.ambientAllowed(true, true))
        assertFalse(CinemaRoomPolicy.ambientAllowed(false, true))
        assertFalse(CinemaRoomPolicy.ambientAllowed(true, false))
        assertFalse(CinemaRoomPolicy.ambientAllowed(false, false))
    }

    @Test
    fun preferredLookOnEnable_kidsVsAdult() {
        assertEquals(ThemeManager.KIDS_BRIGHT, CinemaRoomPolicy.preferredLookOnEnable(true))
        assertEquals(ThemeManager.CINEMA_INK, CinemaRoomPolicy.preferredLookOnEnable(false))
    }

    @Test
    fun looksHint_keepsLockedLook_collapsesRetired() {
        assertEquals(
            ThemeManager.CINEMA_INK_LIGHT,
            CinemaRoomPolicy.looksHint(ThemeManager.CINEMA_INK_LIGHT, isKidsProfile = false),
        )
        assertEquals(
            ThemeManager.CINEMA_INK,
            CinemaRoomPolicy.looksHint(ThemeManager.NERO_AMOLED_OLED, isKidsProfile = false),
        )
        assertEquals(
            ThemeManager.KIDS_BRIGHT,
            CinemaRoomPolicy.looksHint(ThemeManager.SUNSET_CINEMA, isKidsProfile = true),
        )
    }

    @Test
    fun trailerCrossfadeMs_longerWhenCinemaOn() {
        assertEquals(
            CinemaRoomPolicy.TRAILER_CROSSFADE_BASE_MS,
            CinemaRoomPolicy.trailerCrossfadeMs(false),
        )
        assertEquals(
            CinemaRoomPolicy.TRAILER_CROSSFADE_CINEMA_MS,
            CinemaRoomPolicy.trailerCrossfadeMs(true),
        )
        assertTrue(
            CinemaRoomPolicy.trailerCrossfadeMs(true) >
                CinemaRoomPolicy.trailerCrossfadeMs(false),
        )
    }

    @Test
    fun ambientCrossfadeMs_cinemaSoftensDissolve() {
        assertEquals(
            AmbientModePolicy.CROSSFADE_MS,
            CinemaRoomPolicy.ambientCrossfadeMs(false),
        )
        assertEquals(
            CinemaRoomPolicy.AMBIENT_CROSSFADE_CINEMA_MS,
            CinemaRoomPolicy.ambientCrossfadeMs(true),
        )
    }

    @Test
    fun playerChromeInvariants_neverStripPillars() {
        val on = CinemaRoomPolicy.playerChromeInvariants(true)
        val off = CinemaRoomPolicy.playerChromeInvariants(false)
        assertTrue(on.skipIntroAvailable && on.endCardAvailable && on.cleanExitAvailable)
        assertTrue(off.skipIntroAvailable && off.endCardAvailable && off.cleanExitAvailable)
    }

    @Test
    fun focusRestorePriority_cleanExitWins() {
        assertEquals(
            CinemaRoomPolicy.FocusRestore.NONE,
            CinemaRoomPolicy.focusRestorePriority(restoreFocus = false, cleanExitPending = true),
        )
        assertEquals(
            CinemaRoomPolicy.FocusRestore.CLEAN_EXIT,
            CinemaRoomPolicy.focusRestorePriority(restoreFocus = true, cleanExitPending = true),
        )
        assertEquals(
            CinemaRoomPolicy.FocusRestore.CONTENT,
            CinemaRoomPolicy.focusRestorePriority(restoreFocus = true, cleanExitPending = false),
        )
    }

    @Test
    fun ambientLightHintsAllowed_a11yHardOff() {
        assertTrue(
            CinemaRoomPolicy.ambientLightHintsAllowed(
                cinemaRoomEnabled = true,
                lightHintsPref = true,
                highEndOk = true,
                highContrast = false,
            ),
        )
        assertFalse(
            CinemaRoomPolicy.ambientLightHintsAllowed(
                cinemaRoomEnabled = false,
                lightHintsPref = true,
                highEndOk = true,
                highContrast = false,
            ),
        )
        assertFalse(
            CinemaRoomPolicy.ambientLightHintsAllowed(
                cinemaRoomEnabled = true,
                lightHintsPref = false,
                highEndOk = true,
                highContrast = false,
            ),
        )
        assertFalse(
            CinemaRoomPolicy.ambientLightHintsAllowed(
                cinemaRoomEnabled = true,
                lightHintsPref = true,
                highEndOk = false,
                highContrast = false,
            ),
        )
        assertFalse(
            CinemaRoomPolicy.ambientLightHintsAllowed(
                cinemaRoomEnabled = true,
                lightHintsPref = true,
                highEndOk = true,
                highContrast = true,
            ),
        )
    }

    @Test
    fun brandChromeVisible_onlyWhenCinemaAmbientActive() {
        assertTrue(CinemaRoomPolicy.brandChromeVisible(true, true))
        assertFalse(CinemaRoomPolicy.brandChromeVisible(false, true))
        assertFalse(CinemaRoomPolicy.brandChromeVisible(true, false))
    }

    @Test
    fun highEndOk_delegatesAmbientGate() {
        // Cinema Room reuses AmbientModePolicy.highEndOk — TalkBack / reduce-motion / HC hard off.
        // Stick Energy alone is not hardReduceEffects.
        assertFalse(
            AmbientModePolicy.highEndOk(
                hardReduceEffects = false,
                touchExploration = true,
                reduceMotion = false,
            ),
        )
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
                hardReduceEffects = false,
                touchExploration = false,
                reduceMotion = false,
                highContrast = true,
            ),
        )
    }
}

