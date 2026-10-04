package com.dskja.betterstreamflix.player

/**
 * Safe leave-player teardown policy for Mobile + TV.
 *
 * Root cause of Back crashes: calling [androidx.media3.exoplayer.ExoPlayer.release]
 * while [androidx.media3.ui.PlayerView] still owns a live Surface/Texture races OEM
 * surface/codec teardown (especially TV `surface_view`). The earlier
 * [PlaybackLifecycleGuard] pass stopped zombie audio by releasing immediately on Back,
 * which reintroduced that race.
 *
 * Correct order:
 * 1. Soft-stop on Back / destination change — silence audio, release MediaSession,
 *    detach PlayerView, remove listeners. Do **not** release ExoPlayer yet.
 * 2. Hard-release in `onDestroyView` (or when the view is already gone) after the
 *    PlayerView has cleared its surface.
 */
object PlayerTeardownPolicy {

    /** Soft-stop steps applied while the player UI may still be attached. */
    val softStopSteps: List<String> = listOf(
        "mark_tearing_down",
        "cancel_handlers",
        "unregister_lifecycle_guard",
        "detach_player_view",
        "release_media_session",
        "remove_player_listeners",
        "pause_stop_clear_media",
    )

    /** Full teardown including ExoPlayer.release(). */
    val hardReleaseSteps: List<String> = softStopSteps + "release_exo_player"

    /**
     * Hard-release is safe when:
     * - the fragment view is gone, or
     * - PlayerView is already detached and we are destroying, or
     * - in-place episode/server replace after PlayerView was unbound ([replacing]).
     *
     * Soft Back leave keeps [replacing]=false so Exo stays alive until destroy.
     */
    fun allowHardRelease(
        viewAttached: Boolean,
        playerViewDetached: Boolean,
        destroying: Boolean,
        replacing: Boolean = false,
    ): Boolean {
        if (!viewAttached) return true
        if (destroying && playerViewDetached) return true
        if (replacing && playerViewDetached) return true
        return false
    }

    /** Whether listener/UI work may still run. */
    fun allowListenerWork(tearingDown: Boolean, playerReleased: Boolean, hasBinding: Boolean): Boolean {
        return !tearingDown && !playerReleased && hasBinding
    }
}
