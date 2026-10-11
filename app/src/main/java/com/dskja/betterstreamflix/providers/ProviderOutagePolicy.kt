package com.dskja.betterstreamflix.providers

import android.content.Context
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Home outage-banner visibility: show when Trust Layer sees dead sources,
 * with a short dismiss cool-down per profile.
 */
object ProviderOutagePolicy {

    private const val PREFS = "provider_outage_banner"
    private const val KEY_DISMISS_AT = "dismiss_at_ms"
    private const val DISMISS_COOLDOWN_MS = 6L * 60L * 60L * 1000L // 6h

    /** Unit tests can force time. */
    @Volatile
    var nowMs: () -> Long = { System.currentTimeMillis() }

    fun shouldShow(snapshot: ProviderTrustLayer.Snapshot, context: Context? = null): Boolean {
        if (snapshot.outageLevel == ProviderTrustLayer.OutageLevel.NONE) return false
        if (snapshot.outageProviderNames.isEmpty()) return false
        val app = context?.applicationContext ?: return true
        val dismissedAt = prefs(app).getLong(KEY_DISMISS_AT, 0L)
        if (dismissedAt <= 0L) return true
        return nowMs() - dismissedAt >= DISMISS_COOLDOWN_MS
    }

    fun dismiss(context: Context) {
        prefs(context).edit().putLong(KEY_DISMISS_AT, nowMs()).apply()
    }

    fun clearDismiss(context: Context) {
        prefs(context).edit().remove(KEY_DISMISS_AT).apply()
    }

    fun bannerTitle(snapshot: ProviderTrustLayer.Snapshot): String {
        val names = snapshot.outageProviderNames.take(3)
        return names.joinToString(", ")
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            ProfileManager.scopedPrefKey(PREFS),
            Context.MODE_PRIVATE,
        )
}
