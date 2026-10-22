package com.betterstreamflix.app.fragments.settings

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
        assertTrue("SHOW_NEXT_EPISODES" in ProfileScopedPreferenceStore.SCOPED_KEYS)
        assertTrue("SHOW_SMART_COLLECTIONS" in ProfileScopedPreferenceStore.SCOPED_KEYS)
        assertTrue("PARENTAL_CONTROL_MAX_AGE" in ProfileScopedPreferenceStore.SCOPED_KEYS)
    }

    @Test
    fun retiredThemeKeyStaysOffTheStore() {
        // Single locked Dark look — no theme widget exists anymore, so the key
        // must not be scoped-routed or it would shadow profile prefs forever.
        assertFalse("SELECTED_THEME" in ProfileScopedPreferenceStore.SCOPED_KEYS)
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
        val shared = ProfileScopedPreferenceStore.SCOPED_KEYS -
            ProfileScopedPreferenceStore.TV_ONLY_SCOPED_KEYS
        shared.forEach { key ->
            assertTrue("$key missing on mobile", mobile.contains("""android:key="$key""""))
            assertTrue("$key missing on tv", tv.contains("""android:key="$key""""))
        }
        ProfileScopedPreferenceStore.TV_ONLY_SCOPED_KEYS.forEach { key ->
            assertTrue("$key missing on tv", tv.contains("""android:key="$key""""))
            assertFalse(
                "$key should stay TV-only",
                mobile.contains("""android:key="$key""""),
            )
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
