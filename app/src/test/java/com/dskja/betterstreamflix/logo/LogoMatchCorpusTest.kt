package com.dskja.betterstreamflix.logo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calibrated title-gate corpus for logo search (real-world failure modes). */
class LogoMatchCorpusTest {

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun ok(
        query: String,
        candidate: String,
        yearQ: Int? = null,
        yearC: Int? = null,
        votes: Int = 200,
        pop: Float = 40f,
    ) = TmdbLogoPicker.isAcceptableSearchHit(
        voteCount = votes,
        popularity = pop,
        queryTitle = query,
        candidateTitles = listOf(candidate),
        normalize = ::normalize,
        releaseYear = yearQ,
        candidateYear = yearC,
    )

    @Test
    fun blocksPopularWrongHits() {
        assertFalse(ok("one last stick", "the last of us"))
        assertFalse(ok("primate", "primates of madagascar"))
        assertFalse(ok("it", "it chapter two", votes = 5000))
        assertFalse(ok("dune", "dune", yearQ = 2021, yearC = 1984))
    }

    @Test
    fun acceptsStrongMatches() {
        assertTrue(ok("dune part two", "dune part two"))
        assertTrue(ok("mobland", "mobland"))
        assertTrue(ok("the batman", "the batman"))
        assertTrue(ok("spider man no way home", "spider-man: no way home"))
        assertTrue(ok("shogun", "shōgun"))
    }

    @Test
    fun animeAndLocalizedTitles() {
        assertTrue(ok("attack on titan", "attack on titan"))
        assertTrue(
            TmdbLogoPicker.isAcceptableSearchHit(
                voteCount = 300,
                popularity = 80f,
                queryTitle = "kimetsu no yaiba",
                candidateTitles = listOf("Demon Slayer: Kimetsu no Yaiba", "鬼滅の刃"),
                normalize = ::normalize,
            ),
        )
        assertTrue(ok("solo leveling", "solo leveling"))
    }

    @Test
    fun remakeYearGate() {
        assertTrue(ok("dune", "dune", yearQ = 2021, yearC = 2021))
        assertFalse(ok("dune", "dune", yearQ = 2021, yearC = 1984))
        assertTrue(ok("dune", "dune", yearQ = 2021, yearC = 2020)) // ±1
    }

    @Test
    fun originalAndAlternativeTitlesMatch() {
        assertTrue(
            TmdbLogoPicker.isAcceptableSearchHit(
                voteCount = 400,
                popularity = 60f,
                queryTitle = "the boys",
                candidateTitles = listOf("The Boys", "Пацаны"),
                normalize = ::normalize,
            ),
        )
        assertTrue(
            TmdbLogoPicker.isAcceptableSearchHit(
                voteCount = 400,
                popularity = 60f,
                queryTitle = "parasite",
                candidateTitles = listOf("기생충", "Gisaengchung", "Parasite"),
                normalize = ::normalize,
            ),
        )
        assertTrue(
            TmdbLogoPicker.titlesSimilarEnough(
                queryTitle = "demon slayer",
                candidateTitles = listOf("鬼滅の刃", "Demon Slayer: Kimetsu no Yaiba"),
                normalize = ::normalize,
            ),
        )
    }

    @Test
    fun sequelGuardBlocksLoosePrefix() {
        assertFalse(TmdbLogoPicker.titlesMatch("dune", "dune part two"))
        assertTrue(TmdbLogoPicker.titlesMatch("dune part two", "dune part two"))
    }
}
