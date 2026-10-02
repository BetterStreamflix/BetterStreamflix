package com.dskja.betterstreamflix.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PlaybackLifecycleGuardTest {

    @Test
    fun stopActivePlayback_invokesRegisteredHandleOnce() {
        val calls = AtomicInteger(0)
        val handle = PlaybackLifecycleGuard.StopHandle {
            calls.incrementAndGet()
        }
        PlaybackLifecycleGuard.register(handle)
        PlaybackLifecycleGuard.stopActivePlayback()
        assertEquals(1, calls.get())
        // Cleared after stop — second call is a no-op.
        PlaybackLifecycleGuard.stopActivePlayback()
        assertEquals(1, calls.get())
    }

    @Test
    fun unregister_preventsStop() {
        val calls = AtomicInteger(0)
        val handle = PlaybackLifecycleGuard.StopHandle {
            calls.incrementAndGet()
        }
        PlaybackLifecycleGuard.register(handle)
        PlaybackLifecycleGuard.unregister(handle)
        PlaybackLifecycleGuard.stopActivePlayback()
        assertEquals(0, calls.get())
    }

    @Test
    fun register_replacesPreviousHandle() {
        val first = AtomicInteger(0)
        val second = AtomicInteger(0)
        val h1 = PlaybackLifecycleGuard.StopHandle { first.incrementAndGet() }
        val h2 = PlaybackLifecycleGuard.StopHandle { second.incrementAndGet() }
        PlaybackLifecycleGuard.register(h1)
        PlaybackLifecycleGuard.register(h2)
        PlaybackLifecycleGuard.stopActivePlayback()
        assertEquals(0, first.get())
        assertEquals(1, second.get())
        assertFalse(first.get() > 0)
        assertTrue(second.get() == 1)
    }
}
