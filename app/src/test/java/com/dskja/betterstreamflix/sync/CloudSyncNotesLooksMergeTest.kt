package com.dskja.betterstreamflix.sync

import com.dskja.betterstreamflix.utils.ThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncNotesLooksMergeTest {

    @Test
    fun noteLocalWins_onEqualClock() {
        assertTrue(CloudNotesLooksSync.noteLocalWins(100L, 100L))
        assertTrue(CloudNotesLooksSync.noteLocalWins(200L, 100L))
        assertFalse(CloudNotesLooksSync.noteLocalWins(50L, 100L))
    }

    @Test
    fun mergeNote_prefersNewerRemote() {
        val local = RemoteEpisodeNote("u1", "ep1", "local", 50L)
        val remote = RemoteEpisodeNote("u1", "ep1", "remote", 90L)
        assertEquals("remote", CloudNotesLooksSync.mergeNote(remote, local).text)
    }

    @Test
    fun mergeNote_tiePrefersLocal() {
        val local = RemoteEpisodeNote("u1", "ep1", "local", 90L)
        val remote = RemoteEpisodeNote("u1", "ep1", "remote", 90L)
        assertEquals("local", CloudNotesLooksSync.mergeNote(remote, local).text)
    }

    @Test
    fun mergeNote_nullRemoteKeepsLocal() {
        val local = RemoteEpisodeNote("u1", "ep1", "only", 10L)
        assertEquals(local, CloudNotesLooksSync.mergeNote(null, local))
    }

    @Test
    fun mergeLook_tiePrefersLocal() {
        val local = RemoteProfilePrefs("u1", ThemeManager.CINEMA_INK_LIGHT, 40L)
        val remote = RemoteProfilePrefs("u1", ThemeManager.CINEMA_INK, 40L)
        assertEquals(ThemeManager.CINEMA_INK_LIGHT, CloudNotesLooksSync.mergeLook(remote, local).look)
    }

    @Test
    fun clampLook_kidsAlwaysKidsBright() {
        assertEquals(
            ThemeManager.KIDS_BRIGHT,
            CloudNotesLooksSync.clampLookForActiveProfile(ThemeManager.CINEMA_INK, kidsActive = true),
        )
    }

    @Test
    fun clampLook_adultMapsKidsBrightToCinemaInk() {
        assertEquals(
            ThemeManager.CINEMA_INK,
            CloudNotesLooksSync.clampLookForActiveProfile(ThemeManager.KIDS_BRIGHT, kidsActive = false),
        )
        assertEquals(
            ThemeManager.CINEMA_INK_LIGHT,
            CloudNotesLooksSync.clampLookForActiveProfile(ThemeManager.CINEMA_INK_LIGHT, kidsActive = false),
        )
    }
}
