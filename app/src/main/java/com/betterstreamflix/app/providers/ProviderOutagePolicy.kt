package com.betterstreamflix.app.providers

import android.content.Context
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.utils.UserPreferences

/**
 * Home outage-banner visibility: show when Trust Layer sees dead sources,
 * with a short dismiss cool-down per profile.
 *
 * Defaults to the **current** provider being dead (or other enabled providers
 * when the global outage level is SEVERE). Unrelated WATCH-level dead rows do
 * not raise the Home banner. Hidden for kids / guest profiles.
 */
object ProviderOutagePolicy {

    private const val PREFS = "provider_outage_banner"
    private const val KEY_DISMISS_AT = "dismiss_at_ms"
    private const val DISMISS_COOLDOWN_MS = 6L * 60L * 60L * 1000L // 6h

    /** Unit tests can force time. */
    @Volatile
    var nowMs: () -> Long = { System.currentTimeMillis() }

    fun shouldShow(snapshot: ProviderTrustLayer.Snapshot, context: Context? = null): Boolean {
        if (ProfileManager.isKidsActive() || ProfileManager.isGuestActive()) return false
        val relevant = relevantOutageNames(snapshot)
        if (relevant.isEmpty()) return false
        val app = context?.applicationContext ?: return true
        val dismissedAt = prefs(app).getLong(KEY_DISMISS_AT, 0L)
        if (dismissedAt <= 0L) return true
        return nowMs() - dismissedAt >= DISMISS_COOLDOWN_MS
    }

    /**
     * Current-provider dead always qualifies. SEVERE global optionally includes
     * other enabled providers that are DEAD.
     */
    fun relevantOutageNames(snapshot: ProviderTrustLayer.Snapshot): List<String> {
        val dead = snapshot.outageProviderNames
        if (dead.isEmpty()) return emptyList()
        val current = snapshot.currentProviderName?.trim().orEmpty()
        if (current.isNotEmpty() &&
            dead.any { it.equals(current, ignoreCase = true) }
        ) {
            return listOf(current)
        }
        if (snapshot.outageLevel != ProviderTrustLayer.OutageLevel.SEVERE) {
            return emptyList()
        }
        // SEVERE global (optional): prefer enabled providers; fall back to the dead list.
        val enabled = runCatching { enabledProviderNames() }.getOrDefault(emptySet())
        val filtered = dead.filter { name ->
            enabled.any { it.equals(name, ignoreCase = true) }
        }
        return filtered.ifEmpty { dead }
    }

    fun dismiss(context: Context) {
        prefs(context).edit().putLong(KEY_DISMISS_AT, nowMs()).apply()
    }

    fun clearDismiss(context: Context) {
        prefs(context).edit().remove(KEY_DISMISS_AT).apply()
    }

    fun bannerTitle(snapshot: ProviderTrustLayer.Snapshot): String {
        val names = relevantOutageNames(snapshot).ifEmpty { snapshot.outageProviderNames }.take(3)
        return names.joinToString(", ")
    }

    private fun enabledProviderNames(): Set<String> {
        val names = LinkedHashSet<String>()
        runCatching {
            UserPreferences.currentProvider?.name?.takeIf { it.isNotBlank() }?.let { names += it }
        }
        // Prefer non-quarantined registry names when the Provider companion is available.
        runCatching {
            Provider.providers.keys.forEach { provider ->
                if (!ProviderHealth.isQuarantined(provider) &&
                    !ProviderHealth.parkedDefaultNames.contains(provider.name)
                ) {
                    names += provider.name
                }
            }
        }
        return names
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            ProfileManager.scopedPrefKey(PREFS),
            Context.MODE_PRIVATE,
        )
}
