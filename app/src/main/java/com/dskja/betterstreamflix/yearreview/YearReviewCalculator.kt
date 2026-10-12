package com.dskja.betterstreamflix.yearreview

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Pure year-review aggregator. No Android dependencies — unit-testable.
 *
 * Year window uses [TimeZone.getDefault] (or the passed zone) at compute time (YR-008).
 * Finished titles use local `Calendar` millis from watch-history; in-progress movies use
 * wall-clock engagement. Near 1 Jan, TZ / DST edges can place a title in the adjacent year
 * vs the user's mental model — accepted for light Year Review.
 */
object YearReviewCalculator {

    const val DEFAULT_MOVIE_MINUTES = 100
    const val DEFAULT_EPISODE_MINUTES = 45
    /**
     * Gap between consecutive **finish** timestamps that starts a new binge session (YR-012).
     * Not a playback-session detector: pauses before mark-as-watched, background playback,
     * or same-day back-to-back finishes can skew binge shape vs real viewing.
     */
    const val BINGE_GAP_MS = 3L * 60L * 60L * 1000L
    const val TOP_SERIES_COUNT = 3

    fun yearBoundsMs(
        year: Int,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Pair<Long, Long> {
        val start = Calendar.getInstance(timeZone).apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
        val end = Calendar.getInstance(timeZone).apply {
            clear()
            set(Calendar.YEAR, year + 1)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
        return start to end
    }

    fun compute(
        year: Int,
        profileName: String,
        movies: List<YearReviewMovieEvent>,
        episodes: List<YearReviewEpisodeEvent>,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): YearReviewStats {
        val (startMs, endMs) = yearBoundsMs(year, timeZone)
        val yearMovies = movies.filter { it.watchedAtMs in startMs until endMs }
        val yearEpisodes = episodes.filter { it.watchedAtMs in startMs until endMs }

        var totalMinutes = 0.0
        for (movie in yearMovies) {
            val progressMin = movie.inProgressPositionMs?.let { it / 60_000.0 }
            totalMinutes += progressMin
                ?: (movie.runtimeMinutes?.takeIf { it > 0 } ?: DEFAULT_MOVIE_MINUTES).toDouble()
        }
        for (episode in yearEpisodes) {
            val progressMin = episode.inProgressPositionMs?.let { it / 60_000.0 }
            totalMinutes += progressMin
                ?: (episode.runtimeMinutes?.takeIf { it > 0 } ?: DEFAULT_EPISODE_MINUTES).toDouble()
        }

        val topSeries = rankTopSeries(yearEpisodes)
        val longestBinge = findLongestBinge(yearEpisodes)

        val hours = ((totalMinutes / 60.0) * 10.0).roundToInt() / 10.0
        return YearReviewStats(
            year = year,
            profileName = profileName,
            totalHours = hours,
            movieCount = yearMovies.count { it.inProgressPositionMs == null },
            episodeCount = yearEpisodes.count { it.inProgressPositionMs == null },
            topSeries = topSeries,
            longestBinge = longestBinge,
        )
    }

    internal fun rankTopSeries(
        episodes: List<YearReviewEpisodeEvent>,
    ): List<YearReviewStats.TopSeries> {
        if (episodes.isEmpty()) return emptyList()
        data class Acc(
            var title: String,
            var poster: String?,
            var count: Int = 0,
            var minutes: Int = 0,
        )
        val byShow = linkedMapOf<String, Acc>()
        for (ep in episodes) {
            val acc = byShow.getOrPut(ep.showKey) {
                Acc(title = ep.showTitle, poster = ep.showPoster)
            }
            if (acc.title.isBlank() && ep.showTitle.isNotBlank()) acc.title = ep.showTitle
            if (acc.poster.isNullOrBlank() && !ep.showPoster.isNullOrBlank()) {
                acc.poster = ep.showPoster
            }
            acc.count += 1
            acc.minutes += ep.runtimeMinutes?.takeIf { it > 0 } ?: DEFAULT_EPISODE_MINUTES
        }
        return byShow.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Acc>> { it.value.count }
                    .thenByDescending { it.value.minutes }
                    .thenBy { it.value.title.lowercase() },
            )
            .take(TOP_SERIES_COUNT)
            .map { (key, acc) ->
                YearReviewStats.TopSeries(
                    showKey = key,
                    title = acc.title.ifBlank { key },
                    poster = acc.poster,
                    episodeCount = acc.count,
                    estimatedMinutes = acc.minutes,
                )
            }
    }

    internal fun findLongestBinge(
        episodes: List<YearReviewEpisodeEvent>,
    ): YearReviewStats.LongestBinge? {
        if (episodes.isEmpty()) return null
        var best: YearReviewStats.LongestBinge? = null
        val byShow = episodes.groupBy { it.showKey }
        for ((showKey, list) in byShow) {
            val sorted = list.sortedBy { it.watchedAtMs }
            var sessionStart = 0
            fun consider(from: Int, toExclusive: Int) {
                val size = toExclusive - from
                if (size < 2) return
                val slice = sorted.subList(from, toExclusive)
                val minutes = slice.sumOf {
                    it.runtimeMinutes?.takeIf { m -> m > 0 } ?: DEFAULT_EPISODE_MINUTES
                }
                val candidate = YearReviewStats.LongestBinge(
                    showKey = showKey,
                    title = slice.first().showTitle.ifBlank { showKey },
                    episodeCount = size,
                    estimatedMinutes = minutes,
                    startMs = slice.first().watchedAtMs,
                    endMs = slice.last().watchedAtMs,
                )
                best = pickBetterBinge(best, candidate)
            }
            for (i in 1 until sorted.size) {
                val gap = sorted[i].watchedAtMs - sorted[i - 1].watchedAtMs
                if (gap > BINGE_GAP_MS) {
                    consider(sessionStart, i)
                    sessionStart = i
                }
            }
            consider(sessionStart, sorted.size)
        }
        return best
    }

    private fun pickBetterBinge(
        current: YearReviewStats.LongestBinge?,
        candidate: YearReviewStats.LongestBinge,
    ): YearReviewStats.LongestBinge {
        if (current == null) return candidate
        if (candidate.episodeCount != current.episodeCount) {
            return if (candidate.episodeCount > current.episodeCount) candidate else current
        }
        if (candidate.estimatedMinutes != current.estimatedMinutes) {
            return if (candidate.estimatedMinutes > current.estimatedMinutes) candidate else current
        }
        val candidateSpan = candidate.endMs - candidate.startMs
        val currentSpan = current.endMs - current.startMs
        return if (candidateSpan >= currentSpan) candidate else current
    }
}
