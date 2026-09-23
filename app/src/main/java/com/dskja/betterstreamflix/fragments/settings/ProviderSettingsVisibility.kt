package com.dskja.betterstreamflix.fragments.settings

import com.dskja.betterstreamflix.providers.AniWorldProvider
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.providers.SerienStreamProvider
import com.dskja.betterstreamflix.providers.TmdbProvider

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
