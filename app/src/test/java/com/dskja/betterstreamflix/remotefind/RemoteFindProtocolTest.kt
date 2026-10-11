package com.dskja.betterstreamflix.remotefind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFindProtocolTest {

    @Test
    fun helloAndKeyboardRoundTrip() {
        assertEquals(
            RemoteFindProtocol.Inbound.Hello("abc12345abc12345"),
            RemoteFindProtocol.parseInbound(
                RemoteFindProtocol.encodeHello("abc12345abc12345"),
            ),
        )
        assertEquals(
            RemoteFindProtocol.Inbound.KeySet("matrix"),
            RemoteFindProtocol.parseInbound(RemoteFindProtocol.encodeKeySet("matrix")),
        )
        assertEquals(
            RemoteFindProtocol.Inbound.KeyAppend(" neo"),
            RemoteFindProtocol.parseInbound(RemoteFindProtocol.encodeKeyAppend(" neo")),
        )
        assertEquals(
            RemoteFindProtocol.Inbound.KeyBack,
            RemoteFindProtocol.parseInbound(RemoteFindProtocol.encodeKeyBack()),
        )
        assertEquals(
            RemoteFindProtocol.Inbound.Submit,
            RemoteFindProtocol.parseInbound(RemoteFindProtocol.encodeSubmit()),
        )
    }

    @Test
    fun padAndNav() {
        val pad = RemoteFindProtocol.parseInbound(RemoteFindProtocol.encodePad(12.5f, -3f))
        assertTrue(pad is RemoteFindProtocol.Inbound.Pad)
        pad as RemoteFindProtocol.Inbound.Pad
        assertEquals(12.5f, pad.dx, 0.01f)
        assertEquals(-3f, pad.dy, 0.01f)
        assertEquals(
            RemoteFindProtocol.Inbound.Tap,
            RemoteFindProtocol.parseInbound(RemoteFindProtocol.encodeTap()),
        )
        assertEquals(
            RemoteFindProtocol.Inbound.Back,
            RemoteFindProtocol.parseInbound(RemoteFindProtocol.encodeBack()),
        )
    }

    @Test
    fun sanitizeStripsControlChars() {
        val text = RemoteFindProtocol.sanitizeText("hi\u0000there\nworld")
        assertEquals("hi there world", text)
    }
}
