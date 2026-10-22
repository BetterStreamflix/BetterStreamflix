package com.betterstreamflix.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleartextTrafficPolicyTest {

    @Test
    fun requiresGlobalCleartext_isTrueForIptv() {
        assertTrue(CleartextTrafficPolicy.requiresGlobalCleartext)
    }

    @Test
    fun iptvJustification_mentionsIptvAndDomainScopeLimit() {
        val text = CleartextTrafficPolicy.iptvJustification
        assertTrue(text.contains("IPTV", ignoreCase = true))
        assertTrue(text.contains("domain", ignoreCase = true))
        assertEquals(true, text.isNotBlank())
    }
}
