package com.dskja.betterstreamflix.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTeardownPolicyTest {

    @Test
    fun softStop_doesNotStopClearOrReleaseExo() {
        assertFalse(PlayerTeardownPolicy.softStopSteps.contains("stop_clear_media"))
        assertFalse(PlayerTeardownPolicy.softStopSteps.contains("release_exo_player"))
        assertFalse(PlayerTeardownPolicy.softStopSteps.contains("defer_release_exo_player"))
        assertFalse(PlayerTeardownPolicy.allowStopClearOnSoftLeave())
        assertTrue(PlayerTeardownPolicy.hardReleaseSteps.contains("stop_clear_media"))
        assertTrue(PlayerTeardownPolicy.hardReleaseSteps.last() == "defer_release_exo_player")
        assertEquals(
            PlayerTeardownPolicy.softStopSteps.size + 2,
            PlayerTeardownPolicy.hardReleaseSteps.size,
        )
    }

    @Test
    fun softStop_pausesBeforeDetachAndSessionRelease() {
        val steps = PlayerTeardownPolicy.softStopSteps
        assertTrue(steps.indexOf("pause_playback") < steps.indexOf("detach_player_view"))
        assertTrue(steps.indexOf("detach_player_view") < steps.indexOf("release_media_session"))
        assertTrue(steps.indexOf("release_media_session") < steps.indexOf("remove_player_listeners"))
    }

    @Test
    fun hardRelease_alwaysDefersExoRelease() {
        assertTrue(PlayerTeardownPolicy.shouldDeferExoRelease(hardRelease = true))
        assertFalse(PlayerTeardownPolicy.shouldDeferExoRelease(hardRelease = false))
    }

    @Test
    fun allowHardRelease_whenViewGone() {
        assertTrue(
            PlayerTeardownPolicy.allowHardRelease(
                viewAttached = false,
                playerViewDetached = false,
                destroying = false,
            ),
        )
    }

    @Test
    fun allowHardRelease_blockedWhileViewAttachedWithoutDetach() {
        assertFalse(
            PlayerTeardownPolicy.allowHardRelease(
                viewAttached = true,
                playerViewDetached = false,
                destroying = true,
            ),
        )
    }

    @Test
    fun allowHardRelease_duringDestroyAfterDetach() {
        assertTrue(
            PlayerTeardownPolicy.allowHardRelease(
                viewAttached = true,
                playerViewDetached = true,
                destroying = true,
            ),
        )
    }

    @Test
    fun allowHardRelease_blockedOnLiveBackPress() {
        // Back path: view still attached, soft-stop only.
        assertFalse(
            PlayerTeardownPolicy.allowHardRelease(
                viewAttached = true,
                playerViewDetached = true,
                destroying = false,
            ),
        )
    }

    @Test
    fun allowHardRelease_whenReplacingAfterDetach() {
        assertTrue(
            PlayerTeardownPolicy.allowHardRelease(
                viewAttached = true,
                playerViewDetached = true,
                destroying = false,
                replacing = true,
            ),
        )
        assertFalse(
            PlayerTeardownPolicy.allowHardRelease(
                viewAttached = true,
                playerViewDetached = false,
                destroying = false,
                replacing = true,
            ),
        )
    }

    @Test
    fun allowReuseAfterHardRelease_requiresNewInstance() {
        assertTrue(PlayerTeardownPolicy.allowReuseAfterHardRelease(playerReleased = false, newPlayerAssigned = false))
        assertFalse(PlayerTeardownPolicy.allowReuseAfterHardRelease(playerReleased = true, newPlayerAssigned = false))
        assertTrue(PlayerTeardownPolicy.allowReuseAfterHardRelease(playerReleased = true, newPlayerAssigned = true))
    }

    @Test
    fun allowListenerWork_rejectsTeardownStates() {
        assertTrue(PlayerTeardownPolicy.allowListenerWork(false, false, true))
        assertFalse(PlayerTeardownPolicy.allowListenerWork(true, false, true))
        assertFalse(PlayerTeardownPolicy.allowListenerWork(false, true, true))
        assertFalse(PlayerTeardownPolicy.allowListenerWork(false, false, false))
    }
}
