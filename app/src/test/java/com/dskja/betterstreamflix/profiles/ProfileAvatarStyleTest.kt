package com.dskja.betterstreamflix.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAvatarStyleTest {

    @Test
    fun colorFor_knownKeysAreDistinct() {
        val crimson = ProfileAvatarStyle.colorFor("crimson")
        val ocean = ProfileAvatarStyle.colorFor("ocean")
        assertNotEquals(crimson, ocean)
    }

    @Test
    fun colorFor_unknownFallsBackToCrimson() {
        assertEquals(
            ProfileAvatarStyle.colorFor("crimson"),
            ProfileAvatarStyle.colorFor("unknown-key"),
        )
    }

    @Test
    fun initialFor_usesFirstLetter() {
        assertEquals("A", ProfileAvatarStyle.initialFor("Alex"))
        assertEquals("?", ProfileAvatarStyle.initialFor("  "))
    }

    @Test
    fun initialFor_usesTwoLettersForFullName() {
        assertEquals("AJ", ProfileAvatarStyle.initialFor("Alex Jordan"))
    }

    @Test
    fun paletteFor_allKeysHaveMotifs() {
        ProfileManager.avatarKeys.forEach { key ->
            val palette = ProfileAvatarStyle.paletteFor(key)
            assertEquals(key, palette.key)
            assertNotEquals(0, palette.start)
            assertNotEquals(0, palette.end)
            assertTrue(palette.titleRes != 0)
        }
    }

    @Test
    fun all_returnsTwentySignals() {
        assertEquals(20, ProfileAvatarStyle.all().size)
        assertEquals(ProfileManager.avatarKeys.size, ProfileAvatarStyle.all().size)
    }

    @Test
    fun featured_returnsEightHeroStyles() {
        val featured = ProfileAvatarStyle.featured()
        assertEquals(8, featured.size)
        assertEquals("crimson", featured.first().key)
    }

    @Test
    fun featured_includesSelectedWhenOutsideCuratedSet() {
        val featured = ProfileAvatarStyle.featured("peach")
        assertEquals(9, featured.size)
        assertEquals("peach", featured.first().key)
    }
}
