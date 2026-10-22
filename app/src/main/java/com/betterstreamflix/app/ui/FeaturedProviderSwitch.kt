package com.betterstreamflix.app.ui

import android.content.Context
import android.widget.Toast
import com.betterstreamflix.app.R
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.Show
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.utils.DeviceCapabilities
import com.betterstreamflix.app.utils.UserPreferences

/** Shared sticky playback provider switch (Featured / Detail / Search parity). */
object FeaturedProviderSwitch {

    /**
     * Sticky handoff to [providerName] when it differs from the current provider.
     * On Leanback/Fire TV, optionally toasts the new name (Search parity).
     * @return true when a sticky switch occurred
     */
    fun switchSticky(
        providerName: String?,
        context: Context? = null,
        cueToast: Boolean = context != null && (
            DeviceCapabilities.isLeanbackDevice(context) ||
                DeviceCapabilities.isAmazonFireTv(context)
            ),
    ): Boolean {
        val targetName = providerName?.takeIf { it.isNotBlank() } ?: return false
        if (targetName == UserPreferences.currentProvider?.name) return false
        val target = Provider.findByName(targetName) ?: return false
        // Sticky playback handoff — no DB reset / Home notify storm.
        UserPreferences.setCurrentProviderForPlayback(target)
        if (cueToast && context != null) {
            Toast.makeText(
                context,
                context.getString(R.string.switching_to_provider, targetName),
                Toast.LENGTH_SHORT,
            ).show()
        }
        return true
    }

    fun runWithProvider(
        show: Show,
        context: Context? = null,
        cueToast: Boolean = context != null && (
            DeviceCapabilities.isLeanbackDevice(context) ||
                DeviceCapabilities.isAmazonFireTv(context)
            ),
        action: () -> Unit,
    ) {
        val providerName = when (show) {
            is Movie -> show.providerName
            is TvShow -> show.providerName
        }
        switchSticky(providerName, context, cueToast)
        action()
    }

    fun runWithProvider(show: Show, action: () -> Unit) {
        runWithProvider(show, context = null, cueToast = false, action = action)
    }
}
