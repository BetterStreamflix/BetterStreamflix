package com.dskja.betterstreamflix.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSkipIntroTest {

    @Test
    fun classifyTitleDetectsIntroAndRecap() {
        assertEquals(SmartSkipIntro.Kind.INTRO, SmartSkipIntro.classifyTitle("Opening Credits"))
        assertEquals(SmartSkipIntro.Kind.INTRO, SmartSkipIntro.classifyTitle("Vorspann"))
        assertEquals(SmartSkipIntro.Kind.RECAP, SmartSkipIntro.classifyTitle("Previously on…"))
        assertEquals(SmartSkipIntro.Kind.RECAP, SmartSkipIntro.classifyTitle("Recap"))
        assertNull(SmartSkipIntro.classifyTitle("Act One"))
        assertNull(SmartSkipIntro.classifyTitle(null))
    }

    @Test
    fun chapterMarksWinOverHeuristic() {
        val chapters = listOf(
            SmartSkipIntro.ChapterMark("Intro", 8_000L, 72_000L),
        )
        val opp = SmartSkipIntro.evaluate(
            positionMs = 20_000L,
            durationMs = 42L * 60_000L,
            isEpisode = true,
            episodeNumber = 1,
            chapters = chapters,
            silenceGaps = emptyList(),
            alreadyConsumed = false,
        )
        assertNotNull(opp)
        assertEquals(SmartSkipIntro.Source.CHAPTER, opp!!.source)
        assertEquals(72_000L, opp.endMs)
        assertEquals(SmartSkipIntro.Kind.INTRO, opp.kind)
    }

    @Test
    fun doesNotSpamOutsideSegment() {
        val chapters = listOf(
            SmartSkipIntro.ChapterMark("Intro", 8_000L, 72_000L),
        )
        assertNull(
            SmartSkipIntro.evaluate(
                positionMs = 1_000L,
                durationMs = 42L * 60_000L,
                isEpisode = true,
                episodeNumber = 1,
                chapters = chapters,
                silenceGaps = emptyList(),
                alreadyConsumed = false,
            ),
        )
        assertNull(
            SmartSkipIntro.evaluate(
                positionMs = 70_000L,
                durationMs = 42L * 60_000L,
                isEpisode = true,
                episodeNumber = 1,
                chapters = chapters,
                silenceGaps = emptyList(),
                alreadyConsumed = false,
            ),
        )
    }

    @Test
    fun moviesSkipBlindHeuristic() {
        assertNull(
            SmartSkipIntro.evaluate(
                positionMs = 30_000L,
                durationMs = 110L * 60_000L,
                isEpisode = false,
                episodeNumber = null,
                chapters = emptyList(),
                silenceGaps = emptyList(),
                alreadyConsumed = false,
            ),
        )
    }

    @Test
    fun episodeHeuristicOffersRecapThenIntro() {
        val duration = 42L * 60_000L
        val recap = SmartSkipIntro.evaluate(
            positionMs = 10_000L,
            durationMs = duration,
            isEpisode = true,
            episodeNumber = 4,
            chapters = emptyList(),
            silenceGaps = emptyList(),
            alreadyConsumed = false,
        )
        assertNotNull(recap)
        assertEquals(SmartSkipIntro.Kind.RECAP, recap!!.kind)
        assertEquals(SmartSkipIntro.Source.HEURISTIC, recap.source)

        val intro = SmartSkipIntro.evaluate(
            positionMs = recap.endMs + 5_000L,
            durationMs = duration,
            isEpisode = true,
            episodeNumber = 4,
            chapters = emptyList(),
            silenceGaps = emptyList(),
            alreadyConsumed = false,
        )
        assertNotNull(intro)
        assertEquals(SmartSkipIntro.Kind.INTRO, intro!!.kind)
        assertTrue(intro.endMs > recap.endMs)
    }

    @Test
    fun silenceGapProducesOpportunity() {
        val opp = SmartSkipIntro.evaluate(
            positionMs = 40_000L,
            durationMs = 24L * 60_000L,
            isEpisode = true,
            episodeNumber = 1,
            chapters = emptyList(),
            silenceGaps = listOf(SmartSkipIntro.SilenceGap(48_000L, 50_000L)),
            alreadyConsumed = false,
        )
        // Still inside a derived intro window that ends near the silence.
        // Position 40s with skip-to ~50.4s leaves < 12s — should be hidden.
        assertNull(opp)

        val mid = SmartSkipIntro.evaluate(
            positionMs = 20_000L,
            durationMs = 24L * 60_000L,
            isEpisode = true,
            episodeNumber = 1,
            chapters = emptyList(),
            silenceGaps = listOf(SmartSkipIntro.SilenceGap(70_000L, 72_000L)),
            alreadyConsumed = false,
        )
        assertNotNull(mid)
        assertEquals(SmartSkipIntro.Source.SILENCE, mid!!.source)
        assertEquals(SmartSkipIntro.Kind.INTRO, mid.kind)
        assertTrue(mid.endMs in 72_000L..73_000L)
    }

    @Test
    fun consumedHidesButton() {
        assertNull(
            SmartSkipIntro.evaluate(
                positionMs = 20_000L,
                durationMs = 42L * 60_000L,
                isEpisode = true,
                episodeNumber = 1,
                chapters = listOf(SmartSkipIntro.ChapterMark("Intro", 8_000L, 72_000L)),
                silenceGaps = emptyList(),
                alreadyConsumed = true,
            ),
        )
    }

    @Test
    fun chapterWithoutEndUsesNextChapterStart() {
        val ops = SmartSkipIntro.opportunitiesFromChapters(
            chapters = listOf(
                SmartSkipIntro.ChapterMark("Recap", 0L, PlaybackProgress.TIME_UNSET),
                SmartSkipIntro.ChapterMark("Intro", 40_000L, 90_000L),
            ),
            durationMs = 42L * 60_000L,
            isEpisode = true,
        )
        val recap = ops.first { it.kind == SmartSkipIntro.Kind.RECAP }
        assertEquals(40_000L, recap.endMs)
    }
}
