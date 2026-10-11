package com.dskja.betterstreamflix.live

import android.content.Context
import android.content.SharedPreferences
import com.dskja.betterstreamflix.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ChannelPinStoreTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private val store = linkedMapOf<String, String>()

    @Before
    fun setUp() {
        store.clear()
        context = mock(Context::class.java)
        prefs = mock(SharedPreferences::class.java)
        editor = mock(SharedPreferences.Editor::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenAnswer { inv ->
            store[inv.getArgument(0)] = inv.getArgument(1)
            editor
        }
        `when`(editor.apply()).thenAnswer { null }
        `when`(prefs.getString(eq("CHANNEL_PINS_JSON"), eq(null))).thenAnswer {
            store["CHANNEL_PINS_JSON"]
        }
        `when`(prefs.contains(anyString())).thenAnswer { inv ->
            store.containsKey(inv.getArgument(0))
        }
        ChannelPinStore.clear(context)
        store.clear()
    }

    @Test
    fun unpinAllSticksWithoutReseed() {
        val fav = show("live1", "DaddyLive TV", "News")
        ChannelPinStore.pin(context, fav)
        ChannelPinStore.clear(context)
        assertTrue(ChannelPinStore.list(context).isEmpty())
        assertTrue(prefs.contains("CHANNEL_PINS_JSON"))
        val seeded = ChannelPinStore.seedFromFavoritesIfEmpty(context, listOf(fav))
        assertTrue(seeded.isEmpty())
        assertTrue(ChannelPinStore.list(context).isEmpty())
    }

    private fun show(id: String, provider: String, title: String = id) =
        TvShow(id = id, title = title).apply { providerName = provider }

    @Test
    fun pinToggleAndOrderNewestFirst() {
        val a = show("a", "DaddyLive TV", "Alpha")
        val b = show("b", "DaddyLive TV", "Beta")
        assertTrue(ChannelPinStore.pin(context, a))
        assertTrue(ChannelPinStore.pin(context, b))
        val keys = ChannelPinStore.list(context).map { it.key }
        assertEquals(listOf("DaddyLive TV|b", "DaddyLive TV|a"), keys)
        assertTrue(ChannelPinStore.isPinned(context, a))
        assertFalse(ChannelPinStore.toggle(context, a))
        assertFalse(ChannelPinStore.isPinned(context, a))
        assertTrue(ChannelPinStore.toggle(context, a))
        assertEquals("DaddyLive TV|a", ChannelPinStore.list(context).first().key)
    }

    @Test
    fun maxPinsEvictsOldest() {
        repeat(ChannelPinStore.MAX_PINS + 3) { i ->
            ChannelPinStore.pin(context, show("id$i", "IPTV-All World", "Ch $i"))
        }
        val pins = ChannelPinStore.list(context)
        assertEquals(ChannelPinStore.MAX_PINS, pins.size)
        assertEquals("IPTV-All World|id${ChannelPinStore.MAX_PINS + 2}", pins.first().key)
        assertFalse(pins.any { it.id == "id0" })
    }

    @Test
    fun resolveShowsPrefersCatalogEnrichment() {
        ChannelPinStore.pin(context, show("espn", "DaddyLive TV", "ESPN"))
        val catalog = listOf(
            TvShow(id = "espn", title = "ESPN HD").apply {
                providerName = "DaddyLive TV"
                poster = "https://example.com/espn.png"
                programNow = "Live"
            },
        )
        val resolved = ChannelPinStore.resolveShows(context, catalog)
        assertEquals(1, resolved.size)
        assertEquals("ESPN HD", resolved[0].title)
        assertEquals("https://example.com/espn.png", resolved[0].poster)
        assertTrue(resolved[0].isFavorite)
    }

    @Test
    fun pinKeyUsesProviderAndId() {
        val pin = ChannelPinStore.Pin("42", "StreamSports99", "Fox")
        assertEquals("StreamSports99|42", pin.key)
    }
}
