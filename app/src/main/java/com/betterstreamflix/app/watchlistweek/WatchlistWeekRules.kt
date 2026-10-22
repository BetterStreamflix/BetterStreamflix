package com.betterstreamflix.app.watchlistweek

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Pure calendar bucketing for watchlist “New this week” episodes.
 * JVM-safe for unit tests (java.time only).
 */
object WatchlistWeekRules {
    const val MAX_SHOWS = 16
    const val MAX_EPISODES = 21
    const val MIN_SHELF_SIZE = 1

    data class AirTip(
        val showKey: String,
        val showTitle: String,
        val showPoster: String? = null,
        val providerName: String? = null,
        val providerShowId: String,
        val season: Int,
        val episode: Int,
        val airDate: LocalDate,
        val episodeId: String,
        val episodeTitle: String? = null,
        val stillPath: String? = null,
        val overview: String? = null,
    )

    data class DayBucket(
        val day: LocalDate,
        val dayOfWeek: DayOfWeek,
        val tips: List<AirTip>,
    )

    /**
     * Product week is always Monday→Sunday for consistency across locales
     * (US Sunday-start would shift “New this week” mid-week for DE users).
     */
    fun weekBounds(today: LocalDate, @Suppress("UNUSED_PARAMETER") locale: Locale = Locale.getDefault()): ClosedRange<LocalDate> {
        val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val end = start.plusDays(6)
        return start..end
    }

    fun parseAirDate(raw: String?): LocalDate? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Prefer calendar date prefix (handles …T20:00:00Z without zone skew).
        val datePrefix = trimmed.take(10)
        return try {
            LocalDate.parse(datePrefix, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: DateTimeParseException) {
            try {
                java.time.OffsetDateTime.parse(trimmed).toLocalDate()
            } catch (_: DateTimeParseException) {
                try {
                    java.time.Instant.parse(trimmed).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }

    fun inWeek(airDate: LocalDate, week: ClosedRange<LocalDate>): Boolean =
        !airDate.isBefore(week.start) && !airDate.isAfter(week.endInclusive)

    fun dedupeKey(tip: AirTip): String =
        "${tip.showKey}|S${tip.season}|E${tip.episode}|${tip.airDate}"

    fun bucket(
        tips: List<AirTip>,
        week: ClosedRange<LocalDate>,
        locale: Locale = Locale.getDefault(),
    ): List<DayBucket> {
        val byDay = tips
            .filter { inWeek(it.airDate, week) }
            .distinctBy(::dedupeKey)
            .groupBy { it.airDate }
        return (0..6).map { offset ->
            val day = week.start.plusDays(offset.toLong())
            val dayTips = byDay[day].orEmpty().sortedWith(
                compareBy<AirTip> { it.showTitle.lowercase(locale) }
                    .thenBy { it.season }
                    .thenBy { it.episode },
            )
            DayBucket(day = day, dayOfWeek = day.dayOfWeek, tips = dayTips)
        }
    }

    /** Day-major flatten for a horizontal week strip. */
    fun flatten(days: List<DayBucket>): List<AirTip> =
        days.flatMap { it.tips }

    fun shortDayLabel(day: LocalDate, locale: Locale = Locale.getDefault()): String =
        day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}
