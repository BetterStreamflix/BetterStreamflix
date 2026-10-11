package com.dskja.betterstreamflix.yearreview

/**
 * Aggregated local year review for the active profile.
 * Hours are estimated from title runtime (finish clears watchHistory).
 */
data class YearReviewStats(
    val year: Int,
    val profileName: String,
    /** Estimated hours (one decimal). */
    val totalHours: Double,
    val movieCount: Int,
    val episodeCount: Int,
    val topSeries: List<TopSeries>,
    val longestBinge: LongestBinge?,
) {
    val hasActivity: Boolean
        get() = movieCount > 0 || episodeCount > 0 || totalHours > 0.0

    data class TopSeries(
        val showKey: String,
        val title: String,
        val poster: String?,
        val episodeCount: Int,
        val estimatedMinutes: Int,
    )

    data class LongestBinge(
        val showKey: String,
        val title: String,
        val episodeCount: Int,
        val estimatedMinutes: Int,
        val startMs: Long,
        val endMs: Long,
    )
}

/** Flat inputs for [YearReviewCalculator] — no Android / Room types. */
data class YearReviewMovieEvent(
    val id: String,
    val title: String,
    val runtimeMinutes: Int?,
    val watchedAtMs: Long,
    /** Mid-watch position contribution when not finished (ms). */
    val inProgressPositionMs: Long? = null,
)

data class YearReviewEpisodeEvent(
    val id: String,
    val showKey: String,
    val showTitle: String,
    val showPoster: String?,
    val runtimeMinutes: Int?,
    val watchedAtMs: Long,
)
