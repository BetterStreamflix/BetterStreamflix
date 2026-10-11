package com.dskja.betterstreamflix.remotefind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFindLinksTest {

    @Test
    fun deepLinkRoundTrip_codeOnlyNoToken() {
        val link = RemoteFindLinks.buildDeepLink(
            wsUrl = "ws://192.168.1.20:47822",
            code = "XK9M2P",
        )
        assertTrue(link.startsWith("betterstreamflix://remotefind?"))
        assertTrue(!link.contains("token="))
        val target = RemoteFindLinks.parse(link)
        assertNotNull(target)
        assertEquals("ws://192.168.1.20:47822", target!!.wsUrl)
        assertNull(target.token)
        assertEquals("XK9M2P", target.code)
    }

    @Test
    fun httpsMirrorIsCodeOnly() {
        val link = RemoteFindLinks.buildHttpsLink("ABC234")
        assertEquals("https://betterstreamflix.app/remotefind?code=ABC234", link)
        val target = RemoteFindLinks.parse(link)
        assertNull(target?.wsUrl)
        assertNull(target?.token)
        assertEquals("ABC234", target?.code)
    }

    @Test
    fun legacyTokenLinksStillParse() {
        val target = RemoteFindLinks.parse(
            "betterstreamflix://remotefind?ws=ws%3A%2F%2F10.0.0.5%3A47822&token=tokentokentoken12&code=ABC234",
        )
        assertEquals("tokentokentoken12", target?.token)
        assertEquals("ABC234", target?.code)
    }

    @Test
    fun rejectsForeignHosts() {
        assertNull(
            RemoteFindLinks.parse(
                "https://evil.example/remotefind?ws=ws://1.2.3.4:9&token=abcdef12abcdef12",
            ),
        )
        assertNull(RemoteFindLinks.parse("betterstreamflix://cast?token=abcdef12abcdef12"))
    }

    @Test
    fun shortCodeHelpers() {
        assertEquals(6, RemoteFindCode.newShortCode().length)
        assertTrue(RemoteFindCode.isValid(RemoteFindCode.newShortCode()))
        // Ambiguous glyphs remap into generator alphabet (0/O→Q, 1/I/L→J).
        assertEquals("ABCQJ2", RemoteFindCode.normalize("abc012"))
        assertNull(RemoteFindCode.normalize("short"))
        assertEquals("ABCDEF", RemoteFindCode.normalize("ABC-DEF"))
    }

    @Test
    fun discoveryOfferParse_tokenless() {
        val offer = RemoteFindDiscovery.parseOffer(
            "remotefind:offer:XK9M2P:ws://192.168.0.8:47822",
            fromHost = "192.168.0.8",
        )
        assertNotNull(offer)
        assertEquals("XK9M2P", offer!!.code)
        assertEquals("ws://192.168.0.8:47822", offer.wsUrl)
    }

    @Test
    fun discoveryOfferParse_legacyTokenStillAccepted() {
        val offer = RemoteFindDiscovery.parseOffer(
            "remotefind:offer:XK9M2P:abcdef12abcdef12:ws://192.168.0.8:47822",
            fromHost = "192.168.0.8",
        )
        assertNotNull(offer)
        assertEquals("XK9M2P", offer!!.code)
        assertEquals("ws://192.168.0.8:47822", offer.wsUrl)
    }
}
