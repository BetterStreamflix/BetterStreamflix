package com.betterstreamflix.app.platform

import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingMergerTest {
    @Test
    fun detectsProviderCwNames() {
        assertTrue(ContinueWatchingMerger.isProviderContinueWatching("Jellyfin · Continue"))
        assertTrue(ContinueWatchingMerger.isProviderContinueWatching("Plex · On Deck"))
        assertTrue(ContinueWatchingMerger.isProviderContinueWatching("Continue Watching"))
        assertFalse(ContinueWatchingMerger.isProviderContinueWatching("Latest Movies"))
    }

    @Test
    fun mergesWithoutDuplicateImdb() {
        val local = listOf(Movie(id = "1", title = "A", imdbId = "tt1"))
        val remote = listOf(Movie(id = "jf-1", title = "A", imdbId = "tt1"), Movie(id = "2", title = "B", imdbId = "tt2"))
        val merged = ContinueWatchingMerger.merge(local, remote)
        assertEquals(2, merged.size)
        assertEquals("tt1", (merged[0] as Movie).imdbId)
        assertEquals("tt2", (merged[1] as Movie).imdbId)
    }

    @Test
    fun episodeKeyUsesShowAndProvider() {
        val ep = Episode(
            id = "e1",
            number = 2,
            tvShow = TvShow(id = "s1", title = "Show", imdbId = "tt99").apply {
                providerName = "Sflix"
            },
        )
        assertEquals("ep:Sflix:tt99", ContinueWatchingMerger.identityKey(ep))
    }

    @Test
    fun movieKeyIncludesProvider() {
        val a = Movie(id = "1", title = "A", imdbId = "tt1").apply { providerName = "Sflix" }
        val b = Movie(id = "jf-1", title = "A", imdbId = "tt1").apply { providerName = "Jellyfin" }
        assertEquals("movie:Sflix:tt1", ContinueWatchingMerger.identityKey(a))
        assertEquals("movie:Jellyfin:tt1", ContinueWatchingMerger.identityKey(b))
        val merged = ContinueWatchingMerger.merge(listOf(a), listOf(b))
        assertEquals(2, merged.size)
    }
}
