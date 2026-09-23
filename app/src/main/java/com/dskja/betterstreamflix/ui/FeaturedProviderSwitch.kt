package com.dskja.betterstreamflix.ui

import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Show
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.UserPreferences

/** Shared Featured Watch Now provider switch (Mobile + TV parity). */
object FeaturedProviderSwitch {

    fun runWithProvider(show: Show, action: () -> Unit) {
        val providerName = when (show) {
            is Movie -> show.providerName
            is TvShow -> show.providerName
        }
        if (!providerName.isNullOrBlank() &&
            providerName != UserPreferences.currentProvider?.name
        ) {
            Provider.findByName(providerName)?.let {
                // Sticky playback handoff — no DB reset / Home notify storm.
                UserPreferences.setCurrentProviderForPlayback(it)
            }
        }
        action()
    }
}
