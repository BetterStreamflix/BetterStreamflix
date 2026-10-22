package com.betterstreamflix.app.fragments.settings

import com.betterstreamflix.app.providers.AniWorldProvider
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.providers.SerienStreamProvider
import com.betterstreamflix.app.providers.TmdbProvider

/**
 * Which Settings surfaces belong to the active catalog provider.
 */
object ProviderSettingsVisibility {

    fun isSerienStream(provider: Provider?): Boolean = provider is SerienStreamProvider

    fun isAniWorld(provider: Provider?): Boolean = provider is AniWorldProvider

    fun isTmdbDeutsch(provider: Provider?): Boolean =
        provider is TmdbProvider && provider.language.equals("de", ignoreCase = true)

    fun showSerienStreamSettings(provider: Provider?): Boolean = isSerienStream(provider)

    fun showAniWorldSettings(provider: Provider?): Boolean = isAniWorld(provider)

    fun showWatchlistImport(provider: Provider?): Boolean =
        isSerienStream(provider) || isAniWorld(provider) || isTmdbDeutsch(provider)

    fun supportsAccountLogin(provider: Provider?): Boolean =
        isSerienStream(provider) || isAniWorld(provider)
}
