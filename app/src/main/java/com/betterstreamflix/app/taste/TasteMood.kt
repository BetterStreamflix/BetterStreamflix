package com.betterstreamflix.app.taste

import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.smartcollections.SmartCollectionRules

/**
 * Search mood chips — shared heuristics with Smart Collections where possible.
 */
enum class TasteMood {
    ALL,
    CHILL,
    INTENSE,
    KIDS,
    DE_AUDIO,
    ;

    companion object {
        fun fromKey(raw: String?): TasteMood =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ALL

        /** Moods offered in Search UI (Kids profiles omit Intense). */
        fun entriesForProfile(kidsProfile: Boolean): List<TasteMood> =
            if (kidsProfile) {
                listOf(ALL, CHILL, KIDS, DE_AUDIO)
            } else {
                entries.toList()
            }
    }
}

object TasteMoodMatcher {

    /**
     * Genres that define an “intense” mood — kept in sync with
     * [SmartCollectionRules.CHILL_BLOCK_GENRE_IDS] plus Action / War.
     */
    val INTENSE_GENRE_IDS: Set<String> =
        SmartCollectionRules.CHILL_BLOCK_GENRE_IDS + setOf(
            "28", // Action
            "10752", // War
            "37", // Western
        )

    fun matches(item: AppAdapter.Item, mood: TasteMood): Boolean {
        if (mood == TasteMood.ALL) return true
        if (item !is Movie && item !is TvShow) return true
        val signals = SmartCollectionRules.signalsFor(item) ?: return mood != TasteMood.KIDS
        return when (mood) {
            TasteMood.ALL -> true
            TasteMood.CHILL -> SmartCollectionRules.matchesChill(signals)
            TasteMood.KIDS -> SmartCollectionRules.matchesKids(signals)
            TasteMood.DE_AUDIO -> {
                // Require at least one language signal — unknown no longer soft-passes.
                if (signals.originalLanguage.isNullOrBlank() &&
                    signals.providerName.isNullOrBlank() &&
                    signals.preferredAudio.isNullOrBlank()
                ) {
                    false
                } else {
                    SmartCollectionRules.matchesDeAudio(signals)
                }
            }
            TasteMood.INTENSE -> matchesIntense(signals)
        }
    }

    fun matchesIntense(signals: SmartCollectionRules.TitleSignals): Boolean {
        val ids = signals.genres.map { it.id.trim() }.toSet()
        val names = signals.genres.map { it.name.lowercase() }
        if (ids.any { it in INTENSE_GENRE_IDS }) return true
        return names.any { name ->
            name.contains("horror") ||
                name.contains("thriller") ||
                name.contains("action") ||
                name.contains("crime") ||
                name.contains("war") ||
                name.contains("terror") ||
                name.contains("spannung") ||
                name.contains("aktion") ||
                name.contains("krieg")
        }
    }
}
