package com.betterstreamflix.app.fragments.search

import com.betterstreamflix.app.models.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchMemoriesTest {

    @Test
    fun localOutcomeEmptyVsSuccess() {
        assertEquals(
            SearchMemories.Outcome.EMPTY,
            SearchMemories.outcomeForLocalResults(emptyList<Any>()),
        )
        assertEquals(
            SearchMemories.Outcome.SUCCESS,
            SearchMemories.outcomeForLocalResults(listOf("hit")),
        )
    }

    @Test
    fun globalOutcomeWaitsWhileLoading() {
        val states = listOf(
            ProviderResult.State.Loading,
            ProviderResult.State.Success(emptyList()),
        )
        assertNull(SearchMemories.outcomeForGlobalStates(states))
    }

    @Test
    fun globalOutcomeFailedWhenAllErrors() {
        val states = listOf(
            ProviderResult.State.Error(Exception("a")),
            ProviderResult.State.Error(Exception("b")),
        )
        assertEquals(SearchMemories.Outcome.FAILED, SearchMemories.outcomeForGlobalStates(states))
    }

    @Test
    fun globalOutcomeSuccessWhenAnyHits() {
        val hit = Movie(id = "m1", title = "Hit")
        val states = listOf(
            ProviderResult.State.Error(Exception("a")),
            ProviderResult.State.Success(listOf(hit)),
        )
        assertEquals(SearchMemories.Outcome.SUCCESS, SearchMemories.outcomeForGlobalStates(states))
    }

    @Test
    fun globalOutcomeEmptyWhenNoHits() {
        val states = listOf(ProviderResult.State.Success(emptyList()))
        assertEquals(SearchMemories.Outcome.EMPTY, SearchMemories.outcomeForGlobalStates(states))
    }

    @Test
    fun outcomeFromKeyParses() {
        assertEquals(SearchMemories.Outcome.FAILED, SearchMemories.Outcome.fromKey("failed"))
        assertEquals(SearchMemories.Outcome.EMPTY, SearchMemories.Outcome.fromKey("empty"))
        assertEquals(SearchMemories.Outcome.SUCCESS, SearchMemories.Outcome.fromKey("success"))
        assertEquals(SearchMemories.Outcome.SUCCESS, SearchMemories.Outcome.fromKey(null))
    }

    @Test
    fun globalEmptyProviderListIsEmpty() {
        assertEquals(SearchMemories.Outcome.EMPTY, SearchMemories.outcomeForGlobalStates(emptyList()))
    }
}
