package com.dskja.betterstreamflix.fragments.providers

import com.dskja.betterstreamflix.models.Provider as ModelProvider
import java.text.Normalizer
import java.util.Locale

/**
 * Client-side name filter for the Choose-a-provider list.
 * Language / favorites selection stays in [ProvidersViewModel]; this only narrows the loaded set.
 */
object ProvidersSearch {

    enum class FocusDown {
        LIST,
        EMPTY_CTA,
        STAY,
    }

    fun filter(providers: List<ModelProvider>, query: String): List<ModelProvider> {
        val needle = query.trim()
        if (needle.isEmpty()) return providers
        val plain = needle.lowercase(Locale.ROOT)
        val folded = fold(needle)
        return providers.filter { provider ->
            val name = provider.name
            name.lowercase(Locale.ROOT).contains(plain) || fold(name).contains(folded)
        }
    }

    /** DPAD Down from the provider search field. A gone list is not a safe target. */
    fun focusDown(hasRows: Boolean, emptyCtaVisible: Boolean): FocusDown = when {
        hasRows -> FocusDown.LIST
        emptyCtaVisible -> FocusDown.EMPTY_CTA
        else -> FocusDown.STAY
    }

    private fun fold(value: String): String {
        val lower = value.lowercase(Locale.GERMAN)
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .replace("ß", "ss")
    }
}
