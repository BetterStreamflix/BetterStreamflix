package com.dskja.betterstreamflix.notifications

import android.content.Context
import android.util.Log
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.LiveHubCatalog
import com.dskja.betterstreamflix.utils.UserPreferences
import org.json.JSONObject

/**
 * Detects Live Hub favorites that newly appear in the live catalog
 * (best-effort “game/show starting” without timestamped EPG).
 *
 * First successful snapshot after opt-in is a silent baseline — no spam.
 */
object LiveFavoriteStartingChecker {
    private const val TAG = "LiveFavNotify"
    private const val COOLDOWN_MS = 6L * 60L * 60L * 1000L

    suspend fun check(context: Context, budget: Int): Int {
        if (budget <= 0) return 0
        if (!UserPreferences.notifyLiveFavoriteStarting) return 0

        val hub = LiveHubCatalog.load(
            sportLabel = context.getString(R.string.live_hub_section_sport),
            newsLabel = context.getString(R.string.live_hub_section_news),
            channelsLabel = context.getString(R.string.live_hub_section_channels),
            favoritesLabel = context.getString(R.string.live_hub_section_favorites),
            context = context,
        )
        val hubShows = hub.categories
            .flatMap { it.list }
            .filterIsInstance<TvShow>()
        val liveFavorites = LiveHubCatalog.resolveFavoriteChannels(context, hubShows)
        if (liveFavorites.isEmpty()) {
            // Still advance baseline so we don't flood when favorites appear later.
            if (!UserPreferences.notifyLiveBaselineReady) {
                UserPreferences.notifyLiveActiveKeys = emptySet()
                UserPreferences.notifyLiveBaselineReady = true
            } else {
                UserPreferences.notifyLiveActiveKeys = emptySet()
            }
            return 0
        }

        val current = LinkedHashMap<String, TvShow>()
        for (show in liveFavorites) {
            current[LiveHubCatalog.identityKey(show)] = show
        }
        val currentKeys = current.keys

        if (!UserPreferences.notifyLiveBaselineReady) {
            UserPreferences.notifyLiveActiveKeys = currentKeys
            UserPreferences.notifyLiveBaselineReady = true
            Log.d(TAG, "baseline ${currentKeys.size} live favorites (silent)")
            return 0
        }

        val previous = UserPreferences.notifyLiveActiveKeys
        val newlyLive = currentKeys - previous
        UserPreferences.notifyLiveActiveKeys = currentKeys

        if (newlyLive.isEmpty()) return 0

        var sent = 0
        val now = System.currentTimeMillis()
        val cooldowns = readCooldowns()
        for (key in newlyLive) {
            if (sent >= budget) break
            val last = cooldowns.optLong(key, 0L)
            if (last > 0L && now - last < COOLDOWN_MS) continue
            val show = current[key] ?: continue
            val program = show.programNow?.trim()?.takeIf { it.isNotBlank() }
                ?: LiveHubCatalog.programNowOf(show)
            ProductNotifier.notifyLiveStarting(
                context = context,
                showTitle = show.title,
                programHint = program,
                identityKey = key,
            )
            cooldowns.put(key, now)
            sent++
            Log.i(TAG, "live starting: ${show.title}")
        }
        if (sent > 0) writeCooldowns(cooldowns)
        return sent
    }

    fun resetBaseline() {
        UserPreferences.notifyLiveBaselineReady = false
        UserPreferences.notifyLiveActiveKeys = emptySet()
    }

    private fun readCooldowns(): JSONObject {
        val raw = UserPreferences.notifyLiveCooldownJson
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun writeCooldowns(json: JSONObject) {
        // Bound map size — drop oldest-ish keys when oversized.
        if (json.length() > 80) {
            val keys = json.keys().asSequence().toList()
            keys.take(json.length() - 60).forEach { json.remove(it) }
        }
        UserPreferences.notifyLiveCooldownJson = json.toString()
    }
}
