package com.betterstreamflix.app.download

import com.betterstreamflix.app.profiles.ProfileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProfileFolderTest {

    @Test
    fun profileFolderSegment_defaultUsesDisplayName() {
        assertEquals(
            "profiles/Default",
            DownloadStorage.profileFolderSegment(
                profileId = ProfileManager.DEFAULT_PROFILE_ID,
                displayName = "Default",
            ),
        )
    }

    @Test
    fun profileFolderSegment_nonDefaultIncludesId() {
        val segment = DownloadStorage.profileFolderSegment(
            profileId = "kids01abc",
            displayName = "Kids",
        )
        assertEquals("profiles/Kids__kids01abc", segment)
        assertTrue(segment.startsWith("profiles/"))
        assertTrue(segment.contains("kids01abc"))
    }

    @Test
    fun profileFolderSegment_sanitizesIllegalNameChars() {
        val segment = DownloadStorage.profileFolderSegment(
            profileId = "abc123",
            displayName = "A/B:C",
        )
        assertEquals("profiles/A_B_C__abc123", segment)
        assertFalse(segment.contains("A/B"))
        assertFalse(segment.contains(":"))
    }
}
