package com.dskja.betterstreamflix.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetDeepLinksTest {

    @Test
    fun liveHubAndChannel() {
        assertEquals(
            WidgetDeepLinks.Target.LiveHub,
            WidgetDeepLinks.parse(WidgetDeepLinks.buildLiveHubUri()),
        )
        val channel = WidgetDeepLinks.parse(
            WidgetDeepLinks.buildLiveChannelUri("ch1", "DaddyLive", "ESPN"),
        ) as WidgetDeepLinks.Target.LiveChannel
        assertEquals("ch1", channel.id)
        assertEquals("DaddyLive", channel.provider)
        assertEquals("ESPN", channel.title)
    }

    @Test
    fun rejectsForeignOrWatchLinks() {
        assertNull(WidgetDeepLinks.parse("https://example.com/live?id=1"))
        assertNull(WidgetDeepLinks.parse("betterstreamflix://watch?kind=movie&id=x&provider=Sflix"))
        assertNull(WidgetDeepLinks.parse("betterstreamflix://resolve?ws=x"))
    }
}
