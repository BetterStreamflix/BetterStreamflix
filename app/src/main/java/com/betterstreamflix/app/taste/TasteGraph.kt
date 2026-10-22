package com.betterstreamflix.app.taste

import android.content.Context
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.fragments.search.searchIdentityKey
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.utils.FuzzyText
import java.util.concurrent.atomic.AtomicReference

/**
 * Façade over [TasteGraphStore] with an in-process snapshot cache per profile.
 */
object TasteGraph {

    private val cache = AtomicReference<Pair<String, TasteSnapshot>?>(null)

    fun snapshot(context: Context): TasteSnapshot {
        val profileId = ProfileManager.activeProfileId
        cache.get()?.let { (id, snap) ->
            if (id == profileId) return snap
        }
        return rebuild(context)
    }

    fun invalidate() {
        cache.set(null)
    }

    fun rebuild(context: Context): TasteSnapshot {
        // Pin profile at the start so a mid-rebuild switch cannot cache under the wrong id.
        val profileId = ProfileManager.activeProfileId
        val app = context.applicationContext
        val signals = TasteGraphStore.loadSignals(app, profileId)
        val affinity = TasteGraphStore.loadGenreAffinity(app, profileId).toMutableMap()
        val hardHidden = linkedSetOf<String>()
        val softDemotes = linkedMapOf<String, Float>()
        val boosts = linkedMapOf<String, Float>()
        for (signal in signals) {
            val tmdbKey = TasteSnapshot.tmdbKey(signal.tmdbId, signal.isTv)
            when (signal.kind) {
                TasteSignalKind.HIDDEN -> {
                    hardHidden += signal.identityKey
                    if (tmdbKey != null) hardHidden += tmdbKey
                }
                TasteSignalKind.NOT_INTERESTED -> {
                    // Soft demote only — still visible, ranked down.
                    softDemotes[signal.identityKey] =
                        (softDemotes[signal.identityKey] ?: 0f) - 3f
                    if (tmdbKey != null) {
                        softDemotes[tmdbKey] = (softDemotes[tmdbKey] ?: 0f) - 3f
                    }
                }
                TasteSignalKind.MORE_LIKE_SEED -> {
                    // Genre affinity already bumped in recordMoreLike — only title boost here.
                    boosts[signal.identityKey] =
                        (boosts[signal.identityKey] ?: 0f) + 2f
                }
            }
        }
        val tokens = TasteGraphStore.loadQueryTokens(app, profileId)
        val snap = TasteSnapshot(
            genreAffinity = affinity,
            titleBoosts = boosts,
            negatives = hardHidden,
            softDemotes = softDemotes,
            recentQueryTokens = tokens,
            updatedAtMs = System.currentTimeMillis(),
        )
        // Only publish if the active profile is still the one we loaded.
        if (ProfileManager.activeProfileId == profileId) {
            cache.set(profileId to snap)
        }
        return snap
    }

    fun recordNegative(
        context: Context,
        item: AppAdapter.Item,
        kind: TasteSignalKind,
    ) {
        require(kind == TasteSignalKind.HIDDEN || kind == TasteSignalKind.NOT_INTERESTED)
        upsertSignal(context, item, kind)
    }

    fun recordMoreLike(context: Context, item: AppAdapter.Item) {
        upsertSignal(context, item, TasteSignalKind.MORE_LIKE_SEED)
        bumpGenres(context, genreIdsOf(item), amount = 0.5f)
    }

    fun recordPositive(context: Context, item: AppAdapter.Item) {
        bumpGenres(context, genreIdsOf(item), amount = 0.4f)
        invalidate()
    }

    /** Soft reverse of [recordPositive] when a title leaves My List. */
    fun recordUnfavorite(context: Context, item: AppAdapter.Item) {
        bumpGenres(context, genreIdsOf(item), amount = -0.4f)
        invalidate()
    }

    /**
     * Throttled positive engagement from player watch progress
     * (at most once per title per 12h window).
     */
    fun recordWatchEngagement(context: Context, item: AppAdapter.Item) {
        if (item !is Movie && item !is TvShow) return
        val app = context.applicationContext
        val key = item.searchIdentityKey()
        val now = System.currentTimeMillis()
        val last = TasteGraphStore.loadLastWatchEngagementMs(app)[key] ?: 0L
        if (now - last < WATCH_ENGAGEMENT_COOLDOWN_MS) return
        TasteGraphStore.saveLastWatchEngagementMs(app, key, now)
        bumpGenres(app, genreIdsOf(item), amount = 0.25f)
        invalidate()
    }

    fun ingestSearchMemory(
        context: Context,
        query: String,
        topItems: List<AppAdapter.Item>,
    ) {
        val tokens = FuzzyText.normalize(query)
            .split(' ')
            .map { it.trim() }
            .filter { it.length >= 3 }
        if (tokens.isNotEmpty()) {
            val prior = TasteGraphStore.loadQueryTokens(context)
            TasteGraphStore.saveQueryTokens(
                context,
                (tokens + prior).distinct().take(24),
            )
        }
        for (item in topItems.take(5)) {
            bumpGenres(context, genreIdsOf(item), amount = 0.15f)
        }
        invalidate()
    }

    fun clearNegatives(context: Context) {
        TasteGraphStore.clearNegatives(context)
        invalidate()
    }

    fun clearAll(context: Context) {
        TasteGraphStore.clearAll(context)
        invalidate()
    }

    fun wipeProfile(context: Context, profileId: String) {
        TasteGraphStore.wipeProfile(context, profileId)
        cache.get()?.let { (id, _) ->
            if (id == profileId) cache.set(null)
        }
    }

    fun negativeCount(context: Context): Int =
        snapshot(context).hardHidden.size

    fun hiddenTitles(context: Context): List<String> =
        TasteGraphStore.loadSignals(context)
            .filter {
                it.kind == TasteSignalKind.HIDDEN ||
                    it.kind == TasteSignalKind.NOT_INTERESTED
            }
            .map { it.title.ifBlank { it.identityKey } }
            .distinct()
            .take(40)

    fun matchesHardHidden(context: Context, item: AppAdapter.Item): Boolean {
        val snap = snapshot(context)
        val key = item.searchIdentityKey()
        val tmdb = TasteSnapshot.tmdbKey(tmdbIdOf(item), item is TvShow)
        return snap.isHardHidden(key, tmdb)
    }

    private fun upsertSignal(
        context: Context,
        item: AppAdapter.Item,
        kind: TasteSignalKind,
    ) {
        val key = item.searchIdentityKey()
        val entry = TasteSignalEntry(
            identityKey = key,
            kind = kind,
            atMs = System.currentTimeMillis(),
            title = titleOf(item),
            genreIds = genreIdsOf(item),
            tmdbId = tmdbIdOf(item),
            isTv = item is TvShow,
        )
        val prior = TasteGraphStore.loadSignals(context)
            .filterNot { it.identityKey == key && it.kind == kind }
        TasteGraphStore.saveSignals(context, listOf(entry) + prior)
        invalidate()
    }

    private fun bumpGenres(context: Context, genreIds: List<String>, amount: Float) {
        if (genreIds.isEmpty()) return
        val affinity = TasteGraphStore.loadGenreAffinity(context).toMutableMap()
        for (id in genreIds) {
            val key = id.trim()
            if (key.isEmpty()) continue
            val next = ((affinity[key] ?: 0f) + amount).coerceIn(0f, 8f)
            if (next <= 0.01f) affinity.remove(key) else affinity[key] = next
        }
        TasteGraphStore.saveGenreAffinity(context, affinity)
    }

    private fun genreIdsOf(item: AppAdapter.Item): List<String> = when (item) {
        is Movie -> item.genres.map { it.id.trim() }.filter { it.isNotEmpty() }
        is TvShow -> item.genres.map { it.id.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    private fun titleOf(item: AppAdapter.Item): String = when (item) {
        is Movie -> item.title
        is TvShow -> item.title
        else -> ""
    }

    private fun tmdbIdOf(item: AppAdapter.Item): String? = when (item) {
        is Movie -> item.tmdbId?.takeIf { it.isNotBlank() } ?: item.id.toIntOrNull()?.toString()
        is TvShow -> item.tmdbId?.takeIf { it.isNotBlank() } ?: item.id.toIntOrNull()?.toString()
        else -> null
    }

    private const val WATCH_ENGAGEMENT_COOLDOWN_MS = 12L * 60L * 60L * 1000L
}
