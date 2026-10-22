package com.betterstreamflix.app.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAvatarStyleTest {

    @Test
    fun colorFor_knownKeysAreDistinct() {
        val popcorn = ProfileAvatarStyle.colorFor("popcorn")
        val penguin = ProfileAvatarStyle.colorFor("penguin")
        assertNotEquals(popcorn, penguin)
    }

    @Test
    fun colorFor_unknownFallsBackToFirst() {
        assertEquals(
            ProfileAvatarStyle.colorFor("popcorn"),
            ProfileAvatarStyle.colorFor("unknown-key"),
        )
    }

    @Test
    fun resolveKey_mapsLegacyPaletteKeys() {
        assertEquals("popcorn", ProfileAvatarStyle.resolveKey("crimson"))
        assertEquals("robot", ProfileAvatarStyle.resolveKey("slate"))
        assertEquals("heart", ProfileAvatarStyle.resolveKey("rose"))
    }

    @Test
    fun resolveKey_passesThroughValidAndDefaultsUnknown() {
        assertEquals("fox", ProfileAvatarStyle.resolveKey("fox"))
        assertEquals("popcorn", ProfileAvatarStyle.resolveKey("bogus"))
        assertEquals("popcorn", ProfileAvatarStyle.resolveKey(null))
        assertEquals("popcorn", ProfileAvatarStyle.resolveKey(""))
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
    fun avatarFor_allKeysHaveDrawables() {
        ProfileManager.avatarKeys.forEach { key ->
            val avatar = ProfileAvatarStyle.avatarFor(key)
            assertEquals(key, avatar.key)
            assertNotEquals(0, avatar.accent)
            assertTrue(avatar.titleRes != 0)
            assertTrue(avatar.drawableRes != 0)
        }
    }

    @Test
    fun all_returnsTwentyAvatars() {
        assertEquals(20, ProfileAvatarStyle.all().size)
        assertEquals(ProfileManager.avatarKeys.size, ProfileAvatarStyle.all().size)
    }

    @Test
    fun featured_returnsEightHeroAvatars() {
        val featured = ProfileAvatarStyle.featured()
        assertEquals(8, featured.size)
        assertEquals("popcorn", featured.first().key)
    }

    @Test
    fun featured_includesSelectedWhenOutsideCuratedSet() {
        val featured = ProfileAvatarStyle.featured("ninja")
        assertEquals(9, featured.size)
        assertEquals("ninja", featured.first().key)
    }

    @Test
    fun featured_resolvesLegacySelectedKey() {
        val featured = ProfileAvatarStyle.featured("peach")
        assertEquals(9, featured.size)
        assertEquals("bolt", featured.first().key)
    }
}
