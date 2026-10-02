package com.dskja.betterstreamflix.fragments.providers

import com.dskja.betterstreamflix.models.Provider as ModelProvider
import java.util.Locale

/**
 * Client-side name filter for the Choose-a-provider list.
 * Language / favorites selection stays in [ProvidersViewModel]; this only narrows the loaded set.
 */
object ProvidersSearch {

    fun filter(providers: List<ModelProvider>, query: String): List<ModelProvider> {
        val needle = query.trim()
        if (needle.isEmpty()) return providers
        val locale = Locale.ROOT
        val lower = needle.lowercase(locale)
        return providers.filter { provider ->
            provider.name.lowercase(locale).contains(lower)
        }
    }
}
