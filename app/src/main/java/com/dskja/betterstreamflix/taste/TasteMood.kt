package com.dskja.betterstreamflix.taste

import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.smartcollections.SmartCollectionRules

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
    }
}

object TasteMoodMatcher {

    /** Genres that define an “intense” mood (horror / thriller / action / crime / war). */
    val INTENSE_GENRE_IDS = setOf(
        "27", // Horror
        "53", // Thriller
        "28", // Action
        "80", // Crime
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
                // Soft: unknown language still allowed so mood doesn’t empty sparse catalogs.
                if (signals.originalLanguage.isNullOrBlank() &&
                    signals.providerName.isNullOrBlank() &&
                    signals.preferredAudio.isNullOrBlank()
                ) {
                    true
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
