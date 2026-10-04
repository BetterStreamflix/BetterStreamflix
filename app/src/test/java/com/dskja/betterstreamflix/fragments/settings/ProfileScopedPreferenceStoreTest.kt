package com.dskja.betterstreamflix.fragments.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileScopedPreferenceStoreTest {

    @Test
    fun scopedKeysCoverLibraryAndParentalAge() {
        assertTrue("LIBRARY_SCOPE" in ProfileScopedPreferenceStore.SCOPED_KEYS)
        assertTrue("SHOW_CONTINUE_WATCHING" in ProfileScopedPreferenceStore.SCOPED_KEYS)
        assertTrue("SHOW_RECENTLY_WATCHED" in ProfileScopedPreferenceStore.SCOPED_KEYS)
        assertTrue("PARENTAL_CONTROL_MAX_AGE" in ProfileScopedPreferenceStore.SCOPED_KEYS)
    }

    @Test
    fun pinKeysStayOffTheStoreSoEmptyWidgetTextCannotWipeThem() {
        assertFalse("PARENTAL_CONTROL_PIN" in ProfileScopedPreferenceStore.SCOPED_KEYS)
        assertFalse("PARENTAL_CONTROL_ADMIN_PIN" in ProfileScopedPreferenceStore.SCOPED_KEYS)
        assertFalse("PROFILE_PIN" in ProfileScopedPreferenceStore.SCOPED_KEYS)
    }

    @Test
    fun mobileAndTvExposeTheSameScopedPreferenceKeys() {
        val mobile = readXml("settings_mobile.xml")
        val tv = readXml("settings_tv.xml")
        ProfileScopedPreferenceStore.SCOPED_KEYS.forEach { key ->
            assertTrue("$key missing on mobile", mobile.contains("""android:key="$key""""))
            assertTrue("$key missing on tv", tv.contains("""android:key="$key""""))
        }
    }

    private fun readXml(name: String): String {
        val candidates = listOf(
            File("app/src/main/res/xml/$name"),
            File("../app/src/main/res/xml/$name"),
            File("../../app/src/main/res/xml/$name"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Could not locate $name")
    }
}
