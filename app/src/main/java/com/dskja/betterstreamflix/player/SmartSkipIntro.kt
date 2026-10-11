package com.dskja.betterstreamflix.player

/**
 * Smart Skip Intro / Recap policy shared by Mobile + TV players.
 *
 * Priority: chapter marks → silence gaps in the first minutes → conservative
 * episode heuristic. Movies never get the blind heuristic (chapters/silence only).
 * The button is shown only while the playhead is inside a skippable segment with
 * a meaningful amount left — not for the entire opening two minutes.
 */
object SmartSkipIntro {

    enum class Kind { INTRO, RECAP }

    enum class Source { CHAPTER, SILENCE, HEURISTIC }

    data class ChapterMark(
        val title: String?,
        val startMs: Long,
        val endMs: Long,
    )

    data class SilenceGap(
        val startMs: Long,
        val endMs: Long,
    )

    data class Opportunity(
        val kind: Kind,
        val startMs: Long,
        val endMs: Long,
        val source: Source,
    ) {
        fun remainingMs(positionMs: Long): Long = (endMs - positionMs).coerceAtLeast(0L)
    }

    /** Only analyze / offer skips inside the opening minutes. */
    const val ANALYSIS_WINDOW_MS: Long = 4L * 60_000L

    /** Hide the button when less than this remains (skip would feel pointless). */
    const val MIN_SKIP_REMAINING_MS: Long = 12_000L

    /** Ignore tiny chapter / silence segments. */
    const val MIN_SEGMENT_MS: Long = 15_000L

    /** Delay showing the button slightly after a segment starts. */
    const val BUTTON_LEAD_IN_MS: Long = 2_500L

    private const val MIN_DURATION_FOR_HEURISTIC_MS: Long = 12L * 60_000L
    private const val MIN_SILENCE_MS: Long = 1_200L
    private const val MAX_SILENCE_MS: Long = 5_000L
    private const val SILENCE_EARLIEST_MS: Long = 15_000L
    private const val SILENCE_LATEST_MS: Long = 150_000L

    private val INTRO_TITLE = Regex(
        pattern = """\b(intro|opening(\s+credits)?|title\s+sequence|vorspann|g[eé]n[eé]rique|sigla|opening\s+theme|theme\s+song|op\b|credit(?:s)?\s+sequence)\b""",
        option = RegexOption.IGNORE_CASE,
    )
    private val RECAP_TITLE = Regex(
        pattern = """\b(recap|previously(\s+on)?|last\s+time|zusammenfassung|r[eé]sum[eé]|riepilogo|cold\s+open)\b""",
        option = RegexOption.IGNORE_CASE,
    )

    fun classifyTitle(title: String?): Kind? {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (RECAP_TITLE.containsMatchIn(t)) return Kind.RECAP
        if (INTRO_TITLE.containsMatchIn(t)) return Kind.INTRO
        return null
    }

    fun heuristicIntroEndMs(durationMs: Long): Long {
        if (!PlaybackProgress.hasKnownDuration(durationMs)) return 80_000L
        return (durationMs * 55L / 1000L).coerceIn(55_000L, 105_000L)
    }

    fun heuristicRecapEndMs(durationMs: Long): Long {
        if (!PlaybackProgress.hasKnownDuration(durationMs)) return 35_000L
        return (durationMs * 25L / 1000L).coerceIn(25_000L, 50_000L)
    }

    /**
     * Resolve the opportunity that should control the Skip button at [positionMs],
     * or null when nothing reliable / non-spammy applies.
     */
    fun evaluate(
        positionMs: Long,
        durationMs: Long,
        isEpisode: Boolean,
        episodeNumber: Int?,
        chapters: List<ChapterMark>,
        silenceGaps: List<SilenceGap>,
        alreadyConsumed: Boolean,
    ): Opportunity? {
        if (alreadyConsumed || positionMs < 0L) return null
        if (positionMs > ANALYSIS_WINDOW_MS) return null

        val fromChapters = opportunitiesFromChapters(chapters, durationMs, isEpisode)
        // When the stream exposes intro/recap chapters, trust only those — never widen
        // into the episode heuristic (that was the old spammy 2-minute button).
        if (fromChapters.isNotEmpty()) {
            return visibleOpportunity(fromChapters, positionMs)
        }

        val fromSilence = opportunitiesFromSilence(
            durationMs = durationMs,
            isEpisode = isEpisode,
            episodeNumber = episodeNumber,
            silenceGaps = silenceGaps,
        )
        if (fromSilence.isNotEmpty()) {
            return visibleOpportunity(fromSilence, positionMs)
        }

        if (!isEpisode) return null
        if (!PlaybackProgress.hasKnownDuration(durationMs) ||
            durationMs < MIN_DURATION_FOR_HEURISTIC_MS
        ) {
            return null
        }
        return visibleOpportunity(
            heuristicOpportunities(durationMs, episodeNumber),
            positionMs,
        )
    }

    fun opportunitiesFromChapters(
        chapters: List<ChapterMark>,
        durationMs: Long,
        isEpisode: Boolean,
    ): List<Opportunity> {
        if (chapters.isEmpty()) return emptyList()
        val sorted = chapters.sortedBy { it.startMs }
        val out = ArrayList<Opportunity>(sorted.size)
        for (index in sorted.indices) {
            val chapter = sorted[index]
            val kind = classifyTitle(chapter.title) ?: continue
            val start = chapter.startMs.coerceAtLeast(0L)
            if (start > ANALYSIS_WINDOW_MS) continue
            var end = chapter.endMs
            if (end <= start || end == PlaybackProgress.TIME_UNSET) {
                end = sorted.firstOrNull { it.startMs > start }?.startMs
                    ?: when (kind) {
                        Kind.RECAP -> heuristicRecapEndMs(durationMs)
                        Kind.INTRO -> heuristicIntroEndMs(durationMs)
                    }
            }
            if (!isEpisode && kind == Kind.RECAP) {
                // Recap chapters on movies are rare; still honor an explicit mark.
            }
            if (end - start < MIN_SEGMENT_MS) continue
            val cappedEnd = end.coerceAtMost(ANALYSIS_WINDOW_MS + 45_000L)
            if (cappedEnd - start < MIN_SEGMENT_MS) continue
            out += Opportunity(kind, start, cappedEnd, Source.CHAPTER)
        }
        return out
    }

    fun heuristicOpportunities(
        durationMs: Long,
        episodeNumber: Int?,
    ): List<Opportunity> {
        val introEnd = heuristicIntroEndMs(durationMs)
        val hasRecap = (episodeNumber ?: 1) > 1
        if (!hasRecap) {
            return listOf(Opportunity(Kind.INTRO, 5_000L, introEnd, Source.HEURISTIC))
        }
        val recapEnd = heuristicRecapEndMs(durationMs).coerceAtMost(introEnd - MIN_SEGMENT_MS)
        if (recapEnd < 20_000L) {
            return listOf(Opportunity(Kind.INTRO, 5_000L, introEnd, Source.HEURISTIC))
        }
        return listOf(
            Opportunity(Kind.RECAP, 3_000L, recapEnd, Source.HEURISTIC),
            Opportunity(Kind.INTRO, recapEnd, introEnd, Source.HEURISTIC),
        )
    }

    fun opportunitiesFromSilence(
        durationMs: Long,
        isEpisode: Boolean,
        episodeNumber: Int?,
        silenceGaps: List<SilenceGap>,
    ): List<Opportunity> {
        val gap = silenceGaps
            .asSequence()
            .filter { candidate ->
                val len = candidate.endMs - candidate.startMs
                len in MIN_SILENCE_MS..MAX_SILENCE_MS &&
                    candidate.startMs in SILENCE_EARLIEST_MS..SILENCE_LATEST_MS &&
                    candidate.endMs <= ANALYSIS_WINDOW_MS
            }
            .sortedBy { it.endMs }
            .firstOrNull()
            ?: return emptyList()

        val skipTo = (gap.endMs + 400L).coerceAtMost(ANALYSIS_WINDOW_MS + 30_000L)
        val hasRecap = isEpisode && (episodeNumber ?: 1) > 1
        val kind = when {
            hasRecap && skipTo <= 55_000L -> Kind.RECAP
            else -> Kind.INTRO
        }
        val start = when (kind) {
            Kind.RECAP -> 3_000L
            Kind.INTRO -> if (hasRecap) {
                heuristicRecapEndMs(durationMs).coerceAtMost(skipTo - MIN_SEGMENT_MS)
                    .coerceAtLeast(5_000L)
            } else {
                5_000L
            }
        }
        if (skipTo - start < MIN_SEGMENT_MS) return emptyList()
        return listOf(Opportunity(kind, start, skipTo, Source.SILENCE))
    }

    fun visibleOpportunity(
        opportunities: List<Opportunity>,
        positionMs: Long,
    ): Opportunity? {
        return opportunities
            .asSequence()
            .filter { opp ->
                positionMs >= opp.startMs + BUTTON_LEAD_IN_MS &&
                    positionMs <= opp.endMs - MIN_SKIP_REMAINING_MS &&
                    opp.endMs - opp.startMs >= MIN_SEGMENT_MS
            }
            // Prefer the soonest end (tighter chapter/silence beats a wide heuristic).
            .sortedWith(compareBy<Opportunity> { it.endMs }.thenBy { sourceRank(it.source) })
            .firstOrNull()
    }

    private fun sourceRank(source: Source): Int = when (source) {
        Source.CHAPTER -> 0
        Source.SILENCE -> 1
        Source.HEURISTIC -> 2
    }
}
