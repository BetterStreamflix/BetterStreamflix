package com.dskja.betterstreamflix.player

/**
 * Safe leave-player teardown policy for Mobile + TV.
 *
 * Root cause of Back crashes (still after soft-stop):
 * 1. Calling [androidx.media3.exoplayer.ExoPlayer.release] while
 *    [androidx.media3.ui.PlayerView] is still draining a Surface/Texture.
 * 2. Calling [androidx.media3.common.Player.stop] / [androidx.media3.common.Player.clearMediaItems]
 *    on soft-leave in the same frame as surface detach (OEM codec race).
 * 3. Touching a released ExoPlayer again after [HARD_REPLACE] reset flags too early.
 *
 * Correct order:
 * 1. Soft-stop on Back / destination change — pause, detach PlayerView, release
 *    MediaSession, remove listeners. Do **not** stop/clear/release Exo yet.
 * 2. Hard-release in `onDestroyView` (or in-place replace) — stop/clear, then
 *    **post** [androidx.media3.exoplayer.ExoPlayer.release] to the next main-looper
 *    frame so PlayerView can finish clearing its surface.
 */
object PlayerTeardownPolicy {

    /** Soft-stop steps applied while the player UI may still be attached. */
    val softStopSteps: List<String> = listOf(
        "mark_tearing_down",
        "cancel_handlers",
        "unregister_lifecycle_guard",
        "pause_playback",
        "detach_player_view",
        "release_media_session",
        "remove_player_listeners",
    )

    /** Full teardown including deferred ExoPlayer.release(). */
    val hardReleaseSteps: List<String> = softStopSteps + listOf(
        "stop_clear_media",
        "defer_release_exo_player",
    )

    /**
     * Soft leave must not stop/clear/release — those race OEM surface teardown.
     * Audio is silenced via pause + MediaSession release + PlayerView detach.
     */
    fun allowStopClearOnSoftLeave(): Boolean = false

    /** ExoPlayer.release must always be deferred off the detach call stack. */
    fun shouldDeferExoRelease(hardRelease: Boolean): Boolean = hardRelease

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

    /**
     * After a hard release starts, [playerReleased] must stay true until a **new**
     * ExoPlayer instance is assigned. Resetting the flag early lets later code
     * call pause/stop on a released instance.
     */
    fun allowReuseAfterHardRelease(playerReleased: Boolean, newPlayerAssigned: Boolean): Boolean {
        if (!playerReleased) return true
        return newPlayerAssigned
    }

    /** Whether listener/UI work may still run. */
    fun allowListenerWork(tearingDown: Boolean, playerReleased: Boolean, hasBinding: Boolean): Boolean {
        return !tearingDown && !playerReleased && hasBinding
    }
}
