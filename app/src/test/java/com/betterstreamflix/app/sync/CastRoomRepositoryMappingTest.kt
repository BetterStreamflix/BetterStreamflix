package com.betterstreamflix.app.sync

import com.betterstreamflix.app.models.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastRoomRepositoryMappingTest {

    @Test
    fun mapsMovieItem() {
        val item = CastRoomItemPayload(
            mediaType = "movie",
            mediaId = "m1",
            title = "Film",
            releaseDate = "2024",
            poster = "https://example.com/p.jpg",
            imdbId = "tt1",
        )
        val type = CastRoomRepository.toVideoType(item)
        assertTrue(type is Video.Type.Movie)
        val movie = type as Video.Type.Movie
        assertEquals("m1", movie.id)
        assertEquals("Film", movie.title)
        assertEquals("tt1", movie.imdbId)
    }

    @Test
    fun mapsEpisodeItem() {
        val item = CastRoomItemPayload(
            mediaType = "episode",
            mediaId = "e9",
            title = "Show",
            subtitle = "S2 E3",
            showId = "s1",
            showTitle = "Show",
            seasonNumber = 2,
            episodeNumber = 3,
            episodeTitle = "The One",
        )
        val type = CastRoomRepository.toVideoType(item)
        assertTrue(type is Video.Type.Episode)
        val episode = type as Video.Type.Episode
        assertEquals("e9", episode.id)
        assertEquals(3, episode.number)
        assertEquals(2, episode.season.number)
        assertEquals("s1", episode.tvShow.id)
        assertEquals("The One", episode.title)
    }

    @Test
    fun displayLabelPrefersSubtitle() {
        val item = CastRoomItemPayload(
            mediaType = "episode",
            mediaId = "e1",
            title = "Show",
            subtitle = "S1 E1",
        )
        assertEquals("Show · S1 E1", CastRoomQueue.displayLabel(item))
    }
}
