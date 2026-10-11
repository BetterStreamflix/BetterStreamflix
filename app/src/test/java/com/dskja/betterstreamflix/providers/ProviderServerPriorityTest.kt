package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.models.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderServerPriorityTest {

    @Before
    fun setUp() {
        ProviderServerPriority.memoryStore = mutableMapOf()
    }

    @Test
    fun pinToggleAndOrder() {
        assertTrue(ProviderServerPriority.toggle("SerienStream", "VOE"))
        assertTrue(ProviderServerPriority.isPinnedName("SerienStream", "voe"))
        assertFalse(ProviderServerPriority.toggle("SerienStream", "VOE"))
        assertFalse(ProviderServerPriority.isPinnedName("SerienStream", "voe"))
    }

    @Test
    fun applyPinsAfterPreferred() {
        ProviderServerPriority.pin("SerienStream", "vidoza")
        ProviderServerPriority.pin("SerienStream", "voe")
        val servers = listOf(
            Video.Server(id = "1", name = "Streamtape"),
            Video.Server(id = "2", name = "Vidoza"),
            Video.Server(id = "3", name = "VOE"),
            Video.Server(id = "4", name = "Doodstream"),
        )
        val ordered = ProviderServerPriority.applyPins(
            providerName = "SerienStream",
            servers = servers,
            preferredServerName = "Doodstream",
        )
        assertEquals(
            listOf("Doodstream", "VOE", "Vidoza", "Streamtape"),
            ordered.map { it.name },
        )
    }

    @Test
    fun serializeParseRoundTrip() {
        val root = mapOf("SerienStream" to listOf("voe", "vidoza"))
        val json = ProviderServerPriority.serialize(root)
        val parsed = ProviderServerPriority.parse(json)
        assertEquals(listOf("voe", "vidoza"), parsed["SerienStream"])
    }
}
