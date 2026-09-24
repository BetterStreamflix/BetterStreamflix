package com.dskja.betterstreamflix.providers

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * Soft-shelf regressions:
 * - Nested [async] that captured the outer Home [coroutineScope] kept work alive
 *   after [withTimeout] (#206).
 * - Discover movie/TV ClassCast must soft-fail to empty shelf, not kill Home
 *   (BETTERSTREAMFLIX-Q).
 */
class TmdbSoftShelfScopeTest {

    private suspend fun <T> softShelfBroken(
        timeoutMs: Long,
        fallback: T,
        block: suspend () -> T,
    ): T {
        return try {
            withTimeout(timeoutMs) { block() }
        } catch (_: TimeoutCancellationException) {
            fallback
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }
    }

    private suspend fun <T> softShelfFixed(
        timeoutMs: Long,
        fallback: T,
        block: suspend kotlinx.coroutines.CoroutineScope.() -> T,
    ): T {
        return try {
            withTimeout(timeoutMs) {
                coroutineScope { block() }
            }
        } catch (_: TimeoutCancellationException) {
            fallback
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }
    }

    @Test
    fun brokenNestedAsyncKeepsOuterScopeWaiting() = runBlocking {
        val elapsed = measureTimeMillis {
            coroutineScope {
                softShelfBroken(80L, emptyList<String>()) {
                    val slow = async {
                        delay(400L)
                        listOf("late")
                    }
                    slow.await()
                }
            }
        }
        assertTrue("expected orphan wait, was ${elapsed}ms", elapsed >= 300L)
    }

    @Test
    fun fixedNestedAsyncCancelsWithShelfTimeout() = runBlocking {
        val result: List<String>
        val elapsed = measureTimeMillis {
            result = coroutineScope {
                softShelfFixed(80L, emptyList()) {
                    val slow = async {
                        delay(400L)
                        listOf("late")
                    }
                    slow.await()
                }
            }
        }
        assertEquals(emptyList<String>(), result)
        assertTrue("expected cancel under 250ms, was ${elapsed}ms", elapsed < 250L)
    }

    @Test
    fun fixedNestedClassCastIsContainedInSoftShelf() = runBlocking {
        val result = coroutineScope {
            softShelfFixed(500L, emptyList<String>()) {
                val bad = async<List<String>> {
                    throw ClassCastException()
                }
                bad.await()
            }
        }
        assertEquals(
            "ClassCast in shelf child must soft-fail to fallback",
            emptyList<String>(),
            result,
        )
    }

    @Test
    fun runCatchingInsideAsyncPreventsDeferredFailure() = runBlocking {
        val result = coroutineScope {
            softShelfFixed(500L, emptyList<String>()) {
                val movies = async {
                    runCatching<List<String>> { throw ClassCastException() }
                        .getOrDefault(emptyList())
                }
                val shows = async {
                    listOf("ok")
                }
                movies.await() + shows.await()
            }
        }
        assertEquals(listOf("ok"), result)
    }

    @Test
    fun moviePlusTvListsWidenWithoutUnsafeCast() {
        open class Multi
        data class Film(val id: Int) : Multi()
        data class Show(val id: Int) : Multi()
        val movies: List<Film> = listOf(Film(1), Film(2))
        val shows: List<Show> = listOf(Show(3))
        val merged: List<Multi> = movies + shows
        assertEquals(3, merged.size)
        assertTrue(merged[0] is Film)
        assertTrue(merged[2] is Show)
    }
}
