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
    fun all_returnsEightSignals() {
        assertEquals(8, ProfileAvatarStyle.all().size)
    }
}
