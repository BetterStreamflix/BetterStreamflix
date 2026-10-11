package com.dskja.betterstreamflix.smartcollections

import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.player.SeriesAudioPreference
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.TmdbUtils

/**
 * Pure heuristics for Smart Collection shelves built from Watchlist + Continue Watching.
 *
 * Shelves:
 * - Chill evening — light genres, short-ish runtime, no mature thrillers
 * - With kids — Family / Animation + age-safe content ratings
 * - DE audio — series audio memory, German providers, or German original language
 */
object SmartCollectionRules {

    /** TMDb genre ids that feel “chill evening”. */
    val CHILL_GENRE_IDS = setOf(
        "35", // Comedy
        "10749", // Romance
        "18", // Drama
        "16", // Animation
        "10751", // Family
        "14", // Fantasy
        "10402", // Music
        "99", // Documentary (soft)
    )

    /** Genres that break a chill evening mood. */
    val CHILL_BLOCK_GENRE_IDS = setOf(
        "27", // Horror
        "53", // Thriller
        "80", // Crime
        "10752", // War
        "10770", // TV Movie often mixed — kept allowed
    )

    val KIDS_GENRE_IDS = setOf(
        "10751", // Family
        "16", // Animation
    )

    /** Kids ceiling used when contentRating is available (FSK / TV-Y7 / PG …). */
    const val KIDS_MAX_AGE = 12

    /** Soft runtime cap for evening chill (minutes). Null runtime still allowed via genre. */
    const val CHILL_MAX_RUNTIME_MINUTES = 130

    const val MIN_SHELF_SIZE = 3
    const val SHELF_CAP = 16

    data class TitleSignals(
        val genres: List<Genre>,
        val runtimeMinutes: Int?,
        val contentRating: String?,
        val originalLanguage: String?,
        val providerName: String?,
        val seriesId: String?,
        val preferredAudio: String?,
    )

    fun signalsFor(
        item: Any,
        preferredAudioLookup: (seriesId: String) -> String? = { null },
    ): TitleSignals? = when (item) {
        is Movie -> TitleSignals(
            genres = item.genres,
            runtimeMinutes = item.runtime,
            contentRating = item.contentRating,
            originalLanguage = item.originalLanguage,
            providerName = item.providerName,
            seriesId = null,
            preferredAudio = null,
        )
        is TvShow -> TitleSignals(
            genres = item.genres,
            runtimeMinutes = item.runtime,
            contentRating = item.contentRating,
            originalLanguage = item.originalLanguage,
            providerName = item.providerName,
            seriesId = item.id,
            preferredAudio = preferredAudioLookup(item.id),
        )
        else -> null
    }

    fun matchesChill(signals: TitleSignals): Boolean {
        if (signals.genres.isEmpty() && signals.runtimeMinutes == null) return false
        val ids = signals.genres.map { it.id.trim() }.toSet()
        val names = signals.genres.map { it.name.lowercase() }
        if (ids.any { it in CHILL_BLOCK_GENRE_IDS }) return false
        if (names.any { name ->
                name.contains("horror") ||
                    name.contains("thriller") ||
                    name.contains("war") ||
                    name.contains("crime") ||
                    name.contains("terror")
            }
        ) {
            return false
        }
        val genreHit = ids.any { it in CHILL_GENRE_IDS } ||
            names.any { name ->
                name.contains("comedy") ||
                    name.contains("romance") ||
                    name.contains("drama") ||
                    name.contains("animation") ||
                    name.contains("family") ||
                    name.contains("fantasy") ||
                    name.contains("music") ||
                    name.contains("komödie") ||
                    name.contains("romantik") ||
                    name.contains("familie") ||
                    name.contains("zeichentrick")
            }
        val runtimeOk = signals.runtimeMinutes?.let { it in 1..CHILL_MAX_RUNTIME_MINUTES } ?: true
        return genreHit && runtimeOk
    }

    fun matchesKids(signals: TitleSignals): Boolean {
        val age = TmdbUtils.parseContentRatingToAge(signals.contentRating)
        if (age != null && age > KIDS_MAX_AGE) return false

        val ids = signals.genres.map { it.id.trim() }.toSet()
        val names = signals.genres.map { it.name.lowercase() }
        val genreHit = ids.any { it in KIDS_GENRE_IDS } ||
            names.any { name ->
                name.contains("family") ||
                    name.contains("animation") ||
                    name.contains("kids") ||
                    name.contains("children") ||
                    name.contains("familie") ||
                    name.contains("zeichentrick") ||
                    name.contains("kinder")
            }
        // Age-safe with unknown genre still qualifies when rating is clearly kids.
        val ratingKids = age != null && age <= KIDS_MAX_AGE
        return genreHit || (ratingKids && signals.genres.isEmpty())
    }

    fun matchesDeAudio(signals: TitleSignals): Boolean {
        // Strongest: user already picked DE audio for this series.
        if (SeriesAudioPreference.canonical(signals.preferredAudio) == "de") return true

        val original = SeriesAudioPreference.canonical(signals.originalLanguage)
        if (original == "de") return true

        // Cheap name prior before Provider registry (avoids Android init in unit tests).
        val provider = signals.providerName?.lowercase().orEmpty()
        if (provider.isNotEmpty()) {
            val deHints = listOf(
                "serienstream",
                "aniworld",
                "filmpalast",
                "hdfilme",
                "megakino",
                "kellerkino",
                "kinoger",
                "einschalten",
                "tmdb (de)",
                "vavoo vod (de)",
                "pluto tv de",
            )
            if (deHints.any { provider.contains(it) }) return true
        }

        val providerLang = runCatching {
            signals.providerName
                ?.let { Provider.findByName(it)?.language }
                ?.let { SeriesAudioPreference.canonical(it) }
        }.getOrNull()
        return providerLang == "de"
    }
}
