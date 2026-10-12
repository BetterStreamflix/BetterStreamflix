package com.dskja.betterstreamflix.sync

import com.dskja.betterstreamflix.ui.LeanbackDensityPolicy
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
        val local = RemoteEpisodeNote(
            userId = "u1",
            episodeId = "ep1",
            text = "local",
            clientUpdatedAtMillis = 50L,
        )
        val remote = RemoteEpisodeNote(
            userId = "u1",
            episodeId = "ep1",
            text = "remote",
            clientUpdatedAtMillis = 90L,
        )
        assertEquals("remote", CloudNotesLooksSync.mergeNote(remote, local).text)
    }

    @Test
    fun mergeNote_tiePrefersLocal() {
        val local = RemoteEpisodeNote(
            userId = "u1",
            episodeId = "ep1",
            text = "local",
            clientUpdatedAtMillis = 90L,
        )
        val remote = RemoteEpisodeNote(
            userId = "u1",
            episodeId = "ep1",
            text = "remote",
            clientUpdatedAtMillis = 90L,
        )
        assertEquals("local", CloudNotesLooksSync.mergeNote(remote, local).text)
    }

    @Test
    fun mergeNote_nullRemoteKeepsLocal() {
        val local = RemoteEpisodeNote(
            userId = "u1",
            episodeId = "ep1",
            text = "only",
            clientUpdatedAtMillis = 10L,
        )
        assertEquals(local, CloudNotesLooksSync.mergeNote(null, local))
    }

    @Test
    fun mergeLook_tiePrefersLocal() {
        val local = RemoteProfilePrefs("u1", ThemeManager.CINEMA_INK_LIGHT, 40L)
        val remote = RemoteProfilePrefs("u1", ThemeManager.CINEMA_INK, 40L)
        assertEquals(ThemeManager.CINEMA_INK_LIGHT, CloudNotesLooksSync.mergeLook(remote, local).look)
    }

    @Test
    fun mergeLook_remoteWinsCarriesDensity() {
        val local = RemoteProfilePrefs(
            "u1",
            ThemeManager.CINEMA_INK,
            40L,
            leanbackDensity = LeanbackDensityPolicy.COMPACT,
        )
        val remote = RemoteProfilePrefs(
            "u1",
            ThemeManager.CINEMA_INK_LIGHT,
            90L,
            leanbackDensity = LeanbackDensityPolicy.COMFORT,
        )
        val merged = CloudNotesLooksSync.mergeLook(remote, local)
        assertEquals(ThemeManager.CINEMA_INK_LIGHT, merged.look)
        assertEquals(LeanbackDensityPolicy.COMFORT, merged.leanbackDensity)
    }

    @Test
    fun resolveLeanbackDensityForSync_defaultsAndNormalizes() {
        assertEquals(
            LeanbackDensityPolicy.COMFORT,
            CloudNotesLooksSync.resolveLeanbackDensityForSync(null),
        )
        assertEquals(
            LeanbackDensityPolicy.COMPACT,
            CloudNotesLooksSync.resolveLeanbackDensityForSync("COMPACT"),
        )
        assertEquals(
            LeanbackDensityPolicy.COMFORT,
            CloudNotesLooksSync.resolveLeanbackDensityForSync("typo"),
        )
    }

    @Test
    fun shouldApplyRemoteLeanbackDensity_requiresNonBlank() {
        assertFalse(CloudNotesLooksSync.shouldApplyRemoteLeanbackDensity(null))
        assertFalse(CloudNotesLooksSync.shouldApplyRemoteLeanbackDensity(""))
        assertFalse(CloudNotesLooksSync.shouldApplyRemoteLeanbackDensity("   "))
        assertTrue(CloudNotesLooksSync.shouldApplyRemoteLeanbackDensity(LeanbackDensityPolicy.COMPACT))
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

    @Test
    fun noteQueueKey_includesProviderAndShow() {
        val a = RemoteEpisodeNote(
            userId = "u1",
            episodeId = "ep1",
            providerName = "SerienStream",
            showId = "showA",
            text = "a",
            clientUpdatedAtMillis = 1L,
        )
        val b = RemoteEpisodeNote(
            userId = "u1",
            episodeId = "ep1",
            providerName = "AniWorld",
            showId = "showB",
            text = "b",
            clientUpdatedAtMillis = 1L,
        )
        assertTrue(a.queueKey != b.queueKey)
        assertTrue(a.queueKey.contains("SerienStream"))
        assertTrue(b.queueKey.contains("AniWorld"))
    }
}
