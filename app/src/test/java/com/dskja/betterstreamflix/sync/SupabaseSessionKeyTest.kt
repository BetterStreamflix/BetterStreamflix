package com.dskja.betterstreamflix.sync

import com.dskja.betterstreamflix.profiles.ProfileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseSessionKeyTest {

    @Test
    fun sessionKey_defaultProfileKeepsLegacySuffixFreeKey() {
        val fingerprint = "https://example.supabase.co\u0000anon"
        val key = SupabaseProvider.sessionKey(ProfileManager.DEFAULT_PROFILE_ID, fingerprint)
        assertEquals("streamflix_supabase_session-${fingerprint.hashCode()}", key)
        assertFalse(key.endsWith("-default"))
    }

    @Test
    fun sessionKey_nonDefaultProfileIsNamespaced() {
        val fingerprint = "https://example.supabase.co\u0000anon"
        val key = SupabaseProvider.sessionKey("kids01", fingerprint)
        assertTrue(key.endsWith("-kids01"))
        assertTrue(key.startsWith("streamflix_supabase_session-"))
    }
}
