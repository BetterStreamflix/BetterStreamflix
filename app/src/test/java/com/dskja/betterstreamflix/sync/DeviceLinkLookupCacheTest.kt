package com.dskja.betterstreamflix.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DeviceLinkLookupCacheTest {

    @Before
    fun setUp() {
        DeviceLinkLookupCache.clearAllForTests()
    }

    @Test
    fun put_then_get_returnsEmailForPasswordRetry() {
        DeviceLinkLookupCache.put("ABCDEF", "host@example.com", null)
        val cached = DeviceLinkLookupCache.get("abc-def")
        assertNotNull(cached)
        assertEquals("host@example.com", cached!!.email)
        assertEquals("ABCDEF", cached.shortCode)
    }

    @Test
    fun clear_removesEntryAfterClaim() {
        DeviceLinkLookupCache.put("ABCDEF", "host@example.com", null)
        DeviceLinkLookupCache.clear("ABCDEF")
        assertNull(DeviceLinkLookupCache.get("ABCDEF"))
    }

    @Test
    fun expiredEntry_isDropped() {
        val past = java.time.Instant.now().minusSeconds(60).toString()
        DeviceLinkLookupCache.put("ABCDEF", "host@example.com", past)
        assertNull(DeviceLinkLookupCache.get("ABCDEF"))
    }
}
