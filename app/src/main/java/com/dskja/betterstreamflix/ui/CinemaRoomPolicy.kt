package com.dskja.betterstreamflix.ui

import com.dskja.betterstreamflix.utils.ThemeManager

/**
 * Cinema Room — TV-first living-room Markenkern.
 *
 * Pure orchestration policy over Looks Locked, Ambient, Trailer→Play crossfade,
 * Skip Intro, End-Card, Clean Exit, and Fokus-A11y. JVM-safe for unit tests.
 */
object CinemaRoomPolicy {

    /** Baseline Trailer→Play dissolve (matches [TrailerStreamCrossfade.CROSSFADE_MS]). */
    const val TRAILER_CROSSFADE_BASE_MS = 320L

    /** Longer living-room dissolve when Cinema Room is on. */
    const val TRAILER_CROSSFADE_CINEMA_MS = 520L

    /** Soft ambient-image dissolve while Cinema Room owns the canvas. */
    const val AMBIENT_CROSSFADE_CINEMA_MS = 1_100

    fun resolveEnabled(explicit: Boolean?): Boolean = explicit ?: true

    fun resolveAmbientLightHints(explicit: Boolean?): Boolean = explicit ?: true

    /**
     * Ambient idle canvas may enter only when the Cinema Room master is on
     * and the nested Ambient sub-toggle is on.
     */
    fun ambientAllowed(
        cinemaRoomEnabled: Boolean,
        ambientPrefEnabled: Boolean,
    ): Boolean = cinemaRoomEnabled && ambientPrefEnabled

    /**
     * Prefer the profile-class locked look when Cinema Room is enabled.
     * Never unlock retired palettes — always collapse through [ThemeManager.resolveLook].
     */
    fun preferredLookOnEnable(isKidsProfile: Boolean): String =
        ThemeManager.defaultThemeForKids(isKidsProfile)

    /**
     * Soft look contract while Cinema Room is on: keep a locked look if already
     * one; otherwise hint the profile default (Dark adult / Kids Bright).
     */
    fun looksHint(currentLook: String?, isKidsProfile: Boolean): String {
        val resolved = ThemeManager.resolveLook(currentLook)
        return if (ThemeManager.isLockedLook(currentLook)) {
            resolved
        } else {
            preferredLookOnEnable(isKidsProfile)
        }
    }

    fun trailerCrossfadeMs(cinemaRoomEnabled: Boolean): Long =
        if (cinemaRoomEnabled) TRAILER_CROSSFADE_CINEMA_MS else TRAILER_CROSSFADE_BASE_MS

    /**
     * SE-006 / CC-008: under Stick Energy / reduce-motion, never pay the longer
     * cinema dissolve — cap at the base handoff.
     */
    fun trailerCrossfadeMs(
        cinemaRoomEnabled: Boolean,
        reduceMotion: Boolean,
    ): Long {
        if (reduceMotion) return TRAILER_CROSSFADE_BASE_MS
        return trailerCrossfadeMs(cinemaRoomEnabled)
    }

    fun ambientCrossfadeMs(cinemaRoomEnabled: Boolean): Int =
        if (cinemaRoomEnabled) AMBIENT_CROSSFADE_CINEMA_MS else AmbientModePolicy.CROSSFADE_MS

    /**
     * Skip Intro, End-Card, and Clean Exit stay available under Cinema Room.
     * Stick Energy / a11y may still gate Ambient Ken Burns via [AmbientModePolicy.highEndOk],
     * but playback chrome is never stripped by this master switch.
     */
    data class PlayerChromeInvariants(
        val skipIntroAvailable: Boolean,
        val endCardAvailable: Boolean,
        val cleanExitAvailable: Boolean,
    )

    @Suppress("UNUSED_PARAMETER")
    fun playerChromeInvariants(cinemaRoomEnabled: Boolean): PlayerChromeInvariants =
        // Cinema Room enhances chrome; it never disables the three pillars.
        PlayerChromeInvariants(
            skipIntroAvailable = true,
            endCardAvailable = true,
            cleanExitAvailable = true,
        )

    enum class FocusRestore {
        /** Exact Home tile from [com.dskja.betterstreamflix.player.PlayerCleanExit]. */
        CLEAN_EXIT,
        /** Generic Featured / shelf content focus. */
        CONTENT,
        /** No focus restore (e.g. fragment stopping). */
        NONE,
    }

    /**
     * Clean Exit token always wins over Ambient's generic [requestContentFocus].
     */
    fun focusRestorePriority(
        restoreFocus: Boolean,
        cleanExitPending: Boolean,
    ): FocusRestore {
        if (!restoreFocus) return FocusRestore.NONE
        if (cleanExitPending) return FocusRestore.CLEAN_EXIT
        return FocusRestore.CONTENT
    }

    /**
     * Ambient-light hints (palette glow) — Cinema Room + pref + high-end, never
     * under High Contrast / TalkBack / reduce-motion.
     */
    fun ambientLightHintsAllowed(
        cinemaRoomEnabled: Boolean,
        lightHintsPref: Boolean,
        highEndOk: Boolean,
        highContrast: Boolean,
    ): Boolean = cinemaRoomEnabled && lightHintsPref && highEndOk && !highContrast

    fun brandChromeVisible(
        cinemaRoomEnabled: Boolean,
        ambientActive: Boolean,
    ): Boolean = cinemaRoomEnabled && ambientActive
}
