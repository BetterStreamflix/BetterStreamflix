package com.dskja.betterstreamflix.sync

import com.dskja.betterstreamflix.models.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchPartyRepositoryMappingTest {

    @Test
    fun mapsMoviePayload() {
        val type = WatchPartyRepository.toVideoType(
            WatchPartyPayload(
                provider = "SerienStream",
                mediaType = "movie",
                mediaId = "m1",
                title = "Demo",
                releaseDate = "2020",
                poster = "https://example.com/p.jpg",
                imdbId = "tt1",
            ),
        )
        assertTrue(type is Video.Type.Movie)
        val movie = type as Video.Type.Movie
        assertEquals("m1", movie.id)
        assertEquals("Demo", movie.title)
        assertEquals("tt1", movie.imdbId)
    }

    @Test
    fun mapsEpisodePayload() {
        val type = WatchPartyRepository.toVideoType(
            WatchPartyPayload(
                provider = "SerienStream",
                mediaType = "episode",
                mediaId = "e9",
                title = "Show",
                showId = "s1",
                showTitle = "Show",
                seasonNumber = 2,
                episodeNumber = 5,
                episodeTitle = "Five",
            ),
        )
        assertTrue(type is Video.Type.Episode)
        val episode = type as Video.Type.Episode
        assertEquals("e9", episode.id)
        assertEquals(5, episode.number)
        assertEquals(2, episode.season.number)
        assertEquals("s1", episode.tvShow.id)
    }
}
