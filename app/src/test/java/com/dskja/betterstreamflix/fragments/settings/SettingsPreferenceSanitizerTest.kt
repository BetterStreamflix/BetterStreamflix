package com.dskja.betterstreamflix.fragments.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsPreferenceSanitizerTest {

    @Test
    fun miscellaneousMobileXmlHasNoDependencies() {
        val xml = readResourceXml("settings_mobile.xml")
        assertFalse(
            "screen_more must not use android:dependency (BETTERSTREAMFLIX-K)",
            SettingsPreferenceSanitizer.miscellaneousXmlHasCrossScreenDependency(xml),
        )
    }

    @Test
    fun miscellaneousTvXmlHasNoDependencies() {
        val xml = readResourceXml("settings_tv.xml")
        assertFalse(
            "screen_more must not use android:dependency (BETTERSTREAMFLIX-K)",
            SettingsPreferenceSanitizer.miscellaneousXmlHasCrossScreenDependency(xml),
        )
    }

    @Test
    fun supportPreviewIsGoneFromMobileSettings() {
        val xml = readResourceXml("settings_mobile.xml")
        assertFalse(
            "Support UI Preview was removed from the product",
            xml.contains("""android:key="p_settings_support_preview""""),
        )
        assertFalse(
            "experimental design master switch was removed with the preview",
            xml.contains("""android:key="EXPERIMENTAL_NEW_APP_DESIGN""""),
        )
    }

    @Test
    fun detectorFlagsHistoricalCrashPattern() {
        val bad = """
            <PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android">
                <PreferenceScreen android:key="screen_more" android:title="More">
                    <Preference
                        android:key="p_settings_support_preview"
                        android:dependency="EXPERIMENTAL_NEW_APP_DESIGN" />
                </PreferenceScreen>
            </PreferenceScreen>
        """.trimIndent()
        assertTrue(SettingsPreferenceSanitizer.miscellaneousXmlHasCrossScreenDependency(bad))
    }

    private fun readResourceXml(name: String): String {
        val candidates = listOf(
            File("app/src/main/res/xml/$name"),
            File("../app/src/main/res/xml/$name"),
            File("../../app/src/main/res/xml/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $name (cwd=${File(".").absolutePath})")
        return file.readText()
    }
}
