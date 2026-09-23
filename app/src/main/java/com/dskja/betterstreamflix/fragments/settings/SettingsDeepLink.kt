package com.dskja.betterstreamflix.fragments.settings

/**
 * One-shot deep link into a nested PreferenceScreen when navigating to Settings.
 * Consumed by [SettingsMobileFragment] / [SettingsTvFragment] on view create.
 */
object SettingsDeepLink {
    @Volatile
    var pendingScreenKey: String? = null

    fun openDownloadsScreen() {
        pendingScreenKey = "screen_downloads"
    }

    fun openSerienStreamAuth() {
        pendingScreenKey = "screen_serienstream_auth"
    }

    fun openAniWorldAuth() {
        pendingScreenKey = "screen_aniworld_auth"
    }

    fun consumePendingScreenKey(): String? {
        val key = pendingScreenKey
        pendingScreenKey = null
        return key
    }
}
