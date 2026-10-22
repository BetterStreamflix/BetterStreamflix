package com.betterstreamflix.app.taste

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteSnapshotIdentityTest {

    @Test
    fun hardHiddenMatchesIdentityOrTmdbKey() {
        val snap = TasteSnapshot(
            negatives = setOf("movie:local-1", "tmdb:movie:99"),
        )
        assertTrue(snap.isHardHidden("movie:local-1", null))
        assertTrue(snap.isHardHidden("movie:other", "tmdb:movie:99"))
        assertFalse(snap.isHardHidden("movie:other", "tmdb:movie:1"))
    }

    @Test
    fun softDemoteStacksIdentityAndTmdb() {
        val snap = TasteSnapshot(
            softDemotes = mapOf(
                "movie:1" to -3f,
                "tmdb:movie:9" to -3f,
            ),
        )
        assertTrue(snap.softDemote("movie:1", "tmdb:movie:9") <= -5.9f)
    }

    @Test
    fun kidsProfileOmitsIntenseMood() {
        val kids = TasteMood.entriesForProfile(kidsProfile = true)
        assertFalse(kids.contains(TasteMood.INTENSE))
        assertTrue(kids.contains(TasteMood.KIDS))
    }
}
