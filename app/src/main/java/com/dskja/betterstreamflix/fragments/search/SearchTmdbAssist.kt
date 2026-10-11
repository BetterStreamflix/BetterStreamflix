package com.dskja.betterstreamflix.fragments.search

import android.util.Log
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.People
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.FuzzyText
import com.dskja.betterstreamflix.utils.TMDb3
import com.dskja.betterstreamflix.utils.TMDb3.original
import com.dskja.betterstreamflix.utils.TMDb3.w500
import kotlinx.coroutines.CancellationException

/**
 * TMDb helpers that make Search typo-tolerant and add people / "ähnlich wie" results
 * for any provider (not only [com.dskja.betterstreamflix.providers.TmdbProvider]).
 */
object SearchTmdbAssist {

    private const val TAG = "SearchTmdbAssist"
    private const val MAX_PEOPLE = 8
    private const val MAX_SIMILAR = 24
    private const val MAX_TYPO_SHOWS = 16

    data class TypoRescue(
        /** Best corrected title to re-query the HTML provider with, if any. */
        val correctedQuery: String?,
        val shows: List<AppAdapter.Item>,
        val people: List<People>,
    ) {
        val all: List<AppAdapter.Item> get() = people + shows
    }

    suspend fun peopleForQuery(query: String, language: String?): List<People> {
        if (query.isBlank()) return emptyList()
        return runCatching {
            TMDb3.Search.multi(query = query, language = language, page = 1)
                .results
                .mapNotNull { it.toPeopleOrNull() }
                .distinctBy { it.id }
                .sortedWith(peopleComparator)
                .take(MAX_PEOPLE)
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "peopleForQuery failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun typoRescue(query: String, language: String?): TypoRescue {
        if (query.isBlank()) return TypoRescue(null, emptyList(), emptyList())
        return runCatching {
            val page = TMDb3.Search.multi(query = query, language = language, page = 1)
            val people = page.results.mapNotNull { it.toPeopleOrNull() }
                .distinctBy { it.id }
                .sortedWith(peopleComparator)
                .take(MAX_PEOPLE)
            val scored = page.results.mapNotNull { multi ->
                val title = multi.titleOrNull() ?: return@mapNotNull null
                val item = multi.toShowOrNull() ?: return@mapNotNull null
                title to (FuzzyText.distance(query, title) to item)
            }.sortedBy { it.second.first }

            val budget = FuzzyText.typoBudget(query)
            val corrected = scored.firstOrNull { (title, pair) ->
                pair.first <= budget && !FuzzyText.normalize(title).equals(
                    FuzzyText.normalize(query),
                    ignoreCase = true,
                )
            }?.first

            val shows = scored.map { it.second.second }
                .distinctBy { it.searchIdentityKey() }
                .take(MAX_TYPO_SHOWS)

            TypoRescue(correctedQuery = corrected, shows = shows, people = people)
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "typoRescue failed: ${e.message}")
            TypoRescue(null, emptyList(), emptyList())
        }
    }

    /**
     * Resolve seed title → TMDb movie/TV → recommendations + similar.
     */
    suspend fun similarTo(seedTitle: String, language: String?): List<AppAdapter.Item> {
        if (seedTitle.isBlank()) return emptyList()
        return runCatching {
            val multi = TMDb3.Search.multi(query = seedTitle, language = language, page = 1)
            val seed = multi.results
                .mapNotNull { item ->
                    val title = item.titleOrNull() ?: return@mapNotNull null
                    val dist = FuzzyText.distance(seedTitle, title)
                    Triple(dist, item, title)
                }
                .filter { (dist, item, _) ->
                    dist <= FuzzyText.typoBudget(seedTitle) ||
                        FuzzyText.normalize(item.titleOrNull().orEmpty())
                            .contains(FuzzyText.normalize(seedTitle))
                }
                .minByOrNull { it.first }
                ?.second
                ?: multi.results.firstOrNull { it is TMDb3.Movie || it is TMDb3.Tv }

            when (seed) {
                is TMDb3.Movie -> {
                    val details = TMDb3.Movies.details(
                        movieId = seed.id,
                        appendToResponse = listOf(
                            TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS,
                            TMDb3.Params.AppendToResponse.Movie.SIMILAR,
                        ),
                        language = language,
                    )
                    mergeSimilarPages(details.recommendations?.results, details.similar?.results)
                }
                is TMDb3.Tv -> {
                    val details = TMDb3.TvSeries.details(
                        seriesId = seed.id,
                        appendToResponse = listOf(
                            TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS,
                            TMDb3.Params.AppendToResponse.Tv.SIMILAR,
                        ),
                        language = language,
                    )
                    mergeSimilarPages(details.recommendations?.results, details.similar?.results)
                }
                else -> emptyList()
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "similarTo failed: ${e.message}")
            emptyList()
        }
    }

    private fun mergeSimilarPages(
        recommendations: List<TMDb3.MultiItem>?,
        similar: List<TMDb3.MultiItem>?,
    ): List<AppAdapter.Item> {
        val out = LinkedHashMap<String, AppAdapter.Item>()
        (recommendations.orEmpty() + similar.orEmpty()).forEach { multi ->
            val show = multi.toShowOrNull() ?: return@forEach
            out.putIfAbsent(show.searchIdentityKey(), show)
        }
        return out.values.take(MAX_SIMILAR)
    }

    private val peopleComparator = compareByDescending<People> { person ->
        when (person.knownForDepartment?.lowercase()) {
            "directing" -> 2
            "acting", "actors" -> 1
            else -> 0
        }
    }.thenBy { it.name.lowercase() }

    private fun TMDb3.MultiItem.toPeopleOrNull(): People? {
        val person = this as? TMDb3.Person ?: return null
        return People(
            id = person.id.toString(),
            name = person.name,
            image = person.profilePath?.w500 ?: person.profilePath?.original,
            knownForDepartment = person.knownForDepartment?.value,
        ).also { mapped ->
            mapped.character = person.knownForDepartment?.value
        }
    }

    private fun TMDb3.MultiItem.toShowOrNull(): AppAdapter.Item? = when (this) {
        is TMDb3.Movie -> Movie(
            id = id.toString(),
            title = title,
            overview = overview,
            released = releaseDate,
            rating = voteAverage.toDouble(),
            poster = posterPath?.w500,
            banner = backdropPath?.original,
            tmdbId = id.toString(),
        )
        is TMDb3.Tv -> TvShow(
            id = id.toString(),
            title = name,
            overview = overview,
            released = firstAirDate,
            rating = voteAverage.toDouble(),
            poster = posterPath?.w500,
            banner = backdropPath?.original,
            tmdbId = id.toString(),
        )
        else -> null
    }

    private fun TMDb3.MultiItem.titleOrNull(): String? = when (this) {
        is TMDb3.Movie -> title.takeIf { it.isNotBlank() }
        is TMDb3.Tv -> name.takeIf { it.isNotBlank() }
        else -> null
    }
}

internal fun AppAdapter.Item.searchIdentityKey(): String = when (this) {
    is Movie -> "movie:$id"
    is TvShow -> "tvshow:$id"
    is People -> "people:$id"
    is Category -> "category:${identityKey ?: name}"
    else -> "${this::class.java.name}:${hashCode()}"
}
