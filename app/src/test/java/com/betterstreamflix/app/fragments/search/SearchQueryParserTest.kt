package com.betterstreamflix.app.fragments.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryParserTest {

    @Test
    fun parse_aehnlichWie_german() {
        val intent = SearchQueryParser.parse("ähnlich wie Inception")
        assertTrue(intent is SearchQueryParser.Intent.SimilarTo)
        intent as SearchQueryParser.Intent.SimilarTo
        assertEquals("Inception", intent.seedTitle)
    }

    @Test
    fun parse_similarTo_english() {
        val intent = SearchQueryParser.parse("similar to The Matrix")
        assertTrue(intent is SearchQueryParser.Intent.SimilarTo)
        assertEquals("The Matrix", (intent as SearchQueryParser.Intent.SimilarTo).seedTitle)
    }

    @Test
    fun parse_wie_shorthand() {
        val intent = SearchQueryParser.parse("wie Dark")
        assertTrue(intent is SearchQueryParser.Intent.SimilarTo)
        assertEquals("Dark", (intent as SearchQueryParser.Intent.SimilarTo).seedTitle)
    }

    @Test
    fun parse_normal_title() {
        val intent = SearchQueryParser.parse("Breaking Bad")
        assertTrue(intent is SearchQueryParser.Intent.Normal)
        assertEquals("Breaking Bad", (intent as SearchQueryParser.Intent.Normal).query)
    }

    @Test
    fun parse_moreLike() {
        val intent = SearchQueryParser.parse("more like Dune")
        assertTrue(intent is SearchQueryParser.Intent.SimilarTo)
        assertEquals("Dune", (intent as SearchQueryParser.Intent.SimilarTo).seedTitle)
    }
}
