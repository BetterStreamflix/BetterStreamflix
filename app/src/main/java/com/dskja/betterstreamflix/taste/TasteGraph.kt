package com.dskja.betterstreamflix.taste

import android.content.Context
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.fragments.search.searchIdentityKey
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.FuzzyText
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
        val app = context.applicationContext
        val signals = TasteGraphStore.loadSignals(app)
        val affinity = TasteGraphStore.loadGenreAffinity(app).toMutableMap()
        val negatives = linkedSetOf<String>()
        val boosts = linkedMapOf<String, Float>()
        for (signal in signals) {
            when (signal.kind) {
                TasteSignalKind.HIDDEN,
                TasteSignalKind.NOT_INTERESTED,
                -> negatives += signal.identityKey
                TasteSignalKind.MORE_LIKE_SEED -> {
                    boosts[signal.identityKey] =
                        (boosts[signal.identityKey] ?: 0f) + 2f
                    for (g in signal.genreIds) {
                        affinity[g] = (affinity[g] ?: 0f) + 0.35f
                    }
                }
            }
        }
        val tokens = TasteGraphStore.loadQueryTokens(app)
        val snap = TasteSnapshot(
            genreAffinity = affinity,
            titleBoosts = boosts,
            negatives = negatives,
            recentQueryTokens = tokens,
            updatedAtMs = System.currentTimeMillis(),
        )
        cache.set(ProfileManager.activeProfileId to snap)
        return snap
    }

    fun recordNegative(
        context: Context,
        item: AppAdapter.Item,
        kind: TasteSignalKind,
    ) {
        require(kind == TasteSignalKind.HIDDEN || kind == TasteSignalKind.NOT_INTERESTED)
        upsertSignal(context, item, kind, boostTitle = false)
    }

    fun recordMoreLike(context: Context, item: AppAdapter.Item) {
        upsertSignal(context, item, TasteSignalKind.MORE_LIKE_SEED, boostTitle = true)
        bumpGenres(context, genreIdsOf(item), amount = 0.5f)
    }

    fun recordPositive(context: Context, item: AppAdapter.Item) {
        bumpGenres(context, genreIdsOf(item), amount = 0.4f)
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

    fun negativeCount(context: Context): Int =
        snapshot(context).negatives.size

    private fun upsertSignal(
        context: Context,
        item: AppAdapter.Item,
        kind: TasteSignalKind,
        boostTitle: Boolean,
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
        if (boostTitle) {
            // Affinity already bumped in recordMoreLike.
        }
        invalidate()
    }

    private fun bumpGenres(context: Context, genreIds: List<String>, amount: Float) {
        if (genreIds.isEmpty()) return
        val affinity = TasteGraphStore.loadGenreAffinity(context).toMutableMap()
        for (id in genreIds) {
            val key = id.trim()
            if (key.isEmpty()) continue
            affinity[key] = ((affinity[key] ?: 0f) + amount).coerceAtMost(8f)
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
}
