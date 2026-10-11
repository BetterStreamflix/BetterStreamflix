package com.dskja.betterstreamflix.taste

import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.fragments.search.searchIdentityKey
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.FuzzyText

/**
 * Pure history-aware / mood-aware ranking for Search results.
 */
object TasteRanker {

    fun apply(
        items: List<AppAdapter.Item>,
        snapshot: TasteSnapshot,
        mood: TasteMood = TasteMood.ALL,
        query: String = "",
    ): List<AppAdapter.Item> {
        if (items.isEmpty()) return items
        val scored = items.mapIndexedNotNull { index, item ->
            if (item is Movie || item is TvShow) {
                val key = item.searchIdentityKey()
                val tmdb = TasteSnapshot.tmdbKey(tmdbIdOf(item), item is TvShow)
                if (snapshot.isHardHidden(key, tmdb)) return@mapIndexedNotNull null
                if (!TasteMoodMatcher.matches(item, mood)) return@mapIndexedNotNull null
            } else if (mood != TasteMood.ALL) {
                // Keep people / genres / headers when mood filtering shows.
            }
            Scored(item, score(item, snapshot, mood, query), index)
        }
        return scored
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenBy { it.originalIndex },
            )
            .map { it.item }
    }

    fun score(
        item: AppAdapter.Item,
        snapshot: TasteSnapshot,
        mood: TasteMood = TasteMood.ALL,
        query: String = "",
    ): Double {
        if (item !is Movie && item !is TvShow) return 0.0
        val key = item.searchIdentityKey()
        val tmdb = TasteSnapshot.tmdbKey(tmdbIdOf(item), item is TvShow)
        if (snapshot.isHardHidden(key, tmdb)) return Double.NEGATIVE_INFINITY

        var total = 0.0
        total += snapshot.titleBoosts[key]?.toDouble() ?: 0.0
        total += snapshot.softDemote(key, tmdb).toDouble()

        val genres = when (item) {
            is Movie -> item.genres
            is TvShow -> item.genres
            else -> emptyList()
        }
        if (genres.isNotEmpty() && snapshot.genreAffinity.isNotEmpty()) {
            var affinity = 0.0
            for (g in genres) {
                affinity += snapshot.genreAffinity[g.id.trim()]?.toDouble() ?: 0.0
            }
            total += affinity.coerceAtMost(3.0)
        }

        val favoriteBoost = when (item) {
            is Movie -> if (item.isFavorite) 2.0 else 0.0
            is TvShow -> if (item.isFavorite) 2.0 else 0.0
            else -> 0.0
        }
        total += favoriteBoost

        // Movies: watched −1. TV: in-progress CW franchise demote; finished binge softer.
        total += when (item) {
            is Movie -> if (item.isWatched) -1.0 else 0.0
            is TvShow -> {
                when {
                    item.lastPlayedAtMillis != null -> -0.75
                    item.isWatching -> -0.35
                    else -> 0.0
                }
            }
            else -> 0.0
        }

        if (mood != TasteMood.ALL && TasteMoodMatcher.matches(item, mood)) {
            total += 0.75
        }

        val title = when (item) {
            is Movie -> item.title
            is TvShow -> item.title
            else -> ""
        }
        if (query.isNotBlank() && title.isNotBlank()) {
            val q = FuzzyText.normalize(query)
            val t = FuzzyText.normalize(title)
            if (t.contains(q) || q.contains(t)) total += 0.35
        }

        if (snapshot.recentQueryTokens.isNotEmpty() && title.isNotBlank()) {
            val t = FuzzyText.normalize(title)
            val hits = snapshot.recentQueryTokens.count { token ->
                t.contains(FuzzyText.normalize(token))
            }
            total += (hits * 0.5).coerceAtMost(1.5)
        }

        // Within FOR_YOU, prefer newer releases when a year filter is active (product #47).
        val year = when (item) {
            is Movie -> item.released?.get(java.util.Calendar.YEAR)
            is TvShow -> item.released?.get(java.util.Calendar.YEAR)
            else -> null
        }
        if (year != null && year > 0) {
            total += ((year - 1980).coerceIn(0, 50)) * 0.01
        }

        return total
    }

    private fun tmdbIdOf(item: AppAdapter.Item): String? = when (item) {
        is Movie -> item.tmdbId?.takeIf { it.isNotBlank() }
        is TvShow -> item.tmdbId?.takeIf { it.isNotBlank() }
        else -> null
    }

    private data class Scored(
        val item: AppAdapter.Item,
        val score: Double,
        val originalIndex: Int,
    )
}
