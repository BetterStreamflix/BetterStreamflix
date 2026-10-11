package com.dskja.betterstreamflix.taste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteGraphStoreTest {

    @Test
    fun parseSignalsRoundTripShape() {
        val raw = """
            [{"k":"movie:1","kind":"not_interested","t":10,"title":"A","genres":["35"],"tmdb":"1","tv":false},
             {"k":"tvshow:2","kind":"hidden","t":20,"title":"B","genres":[],"tmdb":null,"tv":true}]
        """.trimIndent()
        val parsed = TasteGraphStore.parseSignals(raw)
        assertEquals(2, parsed.size)
        assertEquals(TasteSignalKind.NOT_INTERESTED, parsed[0].kind)
        assertEquals("movie:1", parsed[0].identityKey)
        assertEquals(listOf("35"), parsed[0].genreIds)
        assertEquals(TasteSignalKind.HIDDEN, parsed[1].kind)
        assertTrue(parsed[1].isTv)
    }

    @Test
    fun parseAffinity() {
        val map = TasteGraphStore.parseAffinity("""{"35":1.5,"16":0.25}""")
        assertEquals(1.5f, map["35"]!!, 0.001f)
        assertEquals(0.25f, map["16"]!!, 0.001f)
    }

    @Test
    fun parseTokens() {
        val tokens = TasteGraphStore.parseTokens("""["batman","dark"]""")
        assertEquals(listOf("batman", "dark"), tokens)
    }
}
