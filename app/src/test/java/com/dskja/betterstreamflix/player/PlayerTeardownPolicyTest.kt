package com.dskja.betterstreamflix.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTeardownPolicyTest {

    @Test
    fun softStop_doesNotIncludeExoRelease() {
        assertFalse(PlayerTeardownPolicy.softStopSteps.contains("release_exo_player"))
        assertTrue(PlayerTeardownPolicy.hardReleaseSteps.last() == "release_exo_player")
        assertEquals(
            PlayerTeardownPolicy.softStopSteps.size + 1,
            PlayerTeardownPolicy.hardReleaseSteps.size,
        )
    }

    @Test
    fun softStop_releasesSessionBeforePlayerWork() {
        val steps = PlayerTeardownPolicy.softStopSteps
        assertTrue(steps.indexOf("detach_player_view") < steps.indexOf("release_media_session"))
        assertTrue(steps.indexOf("release_media_session") < steps.indexOf("pause_stop_clear_media"))
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
    fun allowListenerWork_rejectsTeardownStates() {
        assertTrue(PlayerTeardownPolicy.allowListenerWork(false, false, true))
        assertFalse(PlayerTeardownPolicy.allowListenerWork(true, false, true))
        assertFalse(PlayerTeardownPolicy.allowListenerWork(false, true, true))
        assertFalse(PlayerTeardownPolicy.allowListenerWork(false, false, false))
    }
}
