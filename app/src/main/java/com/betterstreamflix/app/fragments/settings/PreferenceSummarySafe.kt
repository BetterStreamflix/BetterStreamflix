package com.betterstreamflix.app.fragments.settings

import androidx.preference.Preference

/**
 * AndroidX Preference throws [IllegalStateException] when [Preference.setSummary]
 * is called while a [Preference.SummaryProvider] is already attached
 * (BETTERSTREAMFLIX-48).
 */

internal fun <T : Preference> T.setSummaryProviderOnce(provider: Preference.SummaryProvider<T>) {
    if (summaryProvider == null) {
        summaryProvider = provider
    }
}

internal fun Preference.setSummaryText(text: CharSequence?) {
    if (summaryProvider != null) {
        summaryProvider = null
    }
    summary = text
}

/** Clear a plain summary without touching an existing SummaryProvider. */
internal fun Preference.clearSummaryText() {
    if (summaryProvider != null) return
    summary = null
}
