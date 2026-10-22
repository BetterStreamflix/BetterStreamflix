package com.betterstreamflix.app.onboarding

import com.betterstreamflix.app.platform.jellyfin.JellyfinProvider
import com.betterstreamflix.app.platform.plex.PlexProvider
import com.betterstreamflix.app.platform.plugins.PluginRegistry
import com.betterstreamflix.app.providers.IptvProvider
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.providers.ProviderHealth
import com.betterstreamflix.app.providers.VavooVodProvider
import com.betterstreamflix.app.utils.AppLanguageManager
import com.betterstreamflix.app.utils.UserPreferences
import java.util.Locale

/** Curated provider list for the onboarding “pick 3 favorites” step. */
object OnboardingCatalog {

    private const val MAX_VISIBLE = 24

    fun resolveLanguageCode(languageTag: String): String {
        if (languageTag == AppLanguageManager.SYSTEM_LANGUAGE) {
            return Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "en"
        }
        return languageTag.lowercase(Locale.ROOT)
    }

    fun providersForLanguage(languageTag: String): List<Provider> {
        val lang = resolveLanguageCode(languageTag)
        val all = eligibleProviders()
        val matched = all.filter { it.language.equals(lang, ignoreCase = true) }
        val pool = if (matched.size >= OnboardingPolicy.REQUIRED_FAVORITES) matched else all
        return pool
            .sortedWith(
                compareBy<Provider> { ProviderHealth.pickerRank(it.name) }
                    .thenBy { it.name.lowercase(Locale.ROOT) },
            )
            .take(MAX_VISIBLE)
    }

    private fun eligibleProviders(): List<Provider> {
        return Provider.allProviders().keys.filter { provider ->
            if (!PluginRegistry.isProviderVisible(provider)) return@filter false
            if (provider is IptvProvider) return@filter false
            if (provider is JellyfinProvider || provider is PlexProvider) return@filter false
            if (provider is VavooVodProvider && !UserPreferences.showVavooVodProviders) {
                return@filter false
            }
            if (!UserPreferences.showQuarantinedProviders && ProviderHealth.isQuarantined(provider)) {
                return@filter false
            }
            true
        }
    }
}
