package com.dskja.betterstreamflix.platform.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PluginManagerTest {

    @Before
    fun reset() {
        PluginRegistry.clear()
        PluginEvents.clear()
    }

    @Test
    fun registerKeepsSourcesSideBySide() {
        PluginRegistry.register(
            object : SourcePlugin {
                override val manifest = PluginManifest(
                    id = "builtin:test",
                    name = "Builtin Test",
                    version = "1",
                    language = "en",
                    source = PluginManifest.Source.BUILTIN,
                )
                override fun createProvider() = error("unused")
                override fun isEnabled() = true
            },
        )
        PluginRegistry.register(
            object : SourcePlugin {
                override val manifest = PluginManifest(
                    id = "local:test",
                    name = "Test Local",
                    version = "1",
                    language = "en",
                    source = PluginManifest.Source.LOCAL,
                )
                override fun createProvider() = error("unused")
                override fun isEnabled() = false
            },
        )
        // Soft re-register must not drop either entry (overwrite by id only).
        assertTrue(PluginRegistry.get("builtin:test") != null)
        assertTrue(PluginRegistry.get("local:test") != null)
        assertEquals(2, PluginRegistry.size)
    }

    @Test
    fun capabilitiesFromLabelsMapsKnownHooks() {
        val caps = PluginManifest.capabilitiesFromLabels(listOf("home", "metadata", "subtitles"))
        assertTrue(caps.homeContribution)
        assertTrue(caps.metadataEnrichment)
        assertTrue(caps.subtitles)
        assertFalse(caps.playbackHooks)
    }

    @Test
    fun pluginEventsRingBufferKeepsRecent() {
        repeat(80) { PluginEvents.record("p", "kind$it") }
        assertEquals(64, PluginEvents.recent(100).size)
        assertEquals("kind79", PluginEvents.recent(1).first().kind)
    }
}
