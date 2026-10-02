package com.dskja.betterstreamflix.player

import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks the foreground in-app player so Activities can force-stop audio when
 * navigating away (Back, bottom nav → Home, etc.) even if Fragment teardown races.
 *
 * Prevents zombie ExoPlayer / MediaSession audio after leaving the player screen.
 */
object PlaybackLifecycleGuard {
    fun interface StopHandle {
        /** Soft-stop local playback (silence + detach); ExoPlayer.release runs in onDestroyView. */
        fun stopAndRelease()
    }

    private val active = AtomicReference<StopHandle?>(null)

    fun register(handle: StopHandle) {
        active.set(handle)
    }

    fun unregister(handle: StopHandle) {
        active.compareAndSet(handle, null)
    }

    /** Stop whatever player is registered, if any. Safe from any thread. */
    fun stopActivePlayback() {
        active.getAndSet(null)?.let { handle ->
            runCatching { handle.stopAndRelease() }
        }
    }
}
