package com.betterstreamflix.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineLibraryTest {

    @Test
    fun build_groupsEpisodesIntoSeriesAndKeepsMovies() {
        val movie = completedMovie("m1", "Inception", createdAt = 100)
        val ep1 = completedEpisode(
            id = "e1",
            showId = "showA",
            showTitle = "Lost",
            season = 1,
            episode = 1,
            title = "Pilot",
            createdAt = 200,
        )
        val ep2 = completedEpisode(
            id = "e2",
            showId = "showA",
            showTitle = "Lost",
            season = 1,
            episode = 2,
            title = "Tabula Rasa",
            createdAt = 300,
        )
        val entries = OfflineLibrary.build(listOf(movie, ep1, ep2))
        assertEquals(2, entries.size)
        val series = entries.filterIsInstance<OfflineLibrary.Entry.SeriesItem>().single()
        val movies = entries.filterIsInstance<OfflineLibrary.Entry.MovieItem>()
        assertEquals("Lost", series.title)
        assertEquals(2, series.episodeCount)
        assertEquals(1, movies.size)
        assertEquals("Inception", movies.single().title)
        // Newest library activity first (series updatedAt 300 > movie 100).
        assertTrue(entries.first() is OfflineLibrary.Entry.SeriesItem)
    }

    @Test
    fun build_ignoresNonCompleted() {
        val active = completedMovie("m2", "Active").copy(
            state = DownloadItemState.DOWNLOADING.name,
        )
        assertTrue(OfflineLibrary.build(listOf(active)).isEmpty())
    }

    @Test
    fun filter_matchesTitleSubtitleAndEpisodeLabelsLocally() {
        val movie = completedMovie("m3", "Dune")
        val ep = completedEpisode(
            id = "e3",
            showId = "showB",
            showTitle = "The Expanse",
            season = 2,
            episode = 4,
            title = "Static",
            createdAt = 1,
        )
        val all = OfflineLibrary.build(listOf(movie, ep))
        assertEquals(1, OfflineLibrary.filter(all, "dune").size)
        assertEquals(1, OfflineLibrary.filter(all, "expanse").size)
        assertEquals(1, OfflineLibrary.filter(all, "static").size)
        assertTrue(OfflineLibrary.filter(all, "missing-xyz").isEmpty())
        assertEquals(2, OfflineLibrary.filter(all, "  ").size)
    }

    @Test
    fun episodeLabel_includesSeasonEpisode() {
        val ep = completedEpisode(
            id = "e4",
            showId = "s",
            showTitle = "Show",
            season = 3,
            episode = 7,
            title = "Collision",
            createdAt = 1,
        )
        assertEquals("S03E07 · Collision", OfflineLibrary.episodeLabel(ep))
    }

    private fun completedMovie(
        id: String,
        title: String,
        createdAt: Long = 1L,
    ): DownloadItemEntity = DownloadItemEntity(
        id = id,
        contentKey = "movie|Prov|$id",
        media3Id = "media-$id",
        providerName = "Prov",
        kind = DownloadKind.MOVIE.name,
        title = title,
        posterUrl = "https://img.example/$id.jpg",
        state = DownloadItemState.COMPLETED.name,
        createdAt = createdAt,
        updatedAt = createdAt,
        completedAt = createdAt,
    )

    private fun completedEpisode(
        id: String,
        showId: String,
        showTitle: String,
        season: Int,
        episode: Int,
        title: String,
        createdAt: Long,
    ): DownloadItemEntity = DownloadItemEntity(
        id = id,
        contentKey = "episode|Prov|$showId|$season|$episode|$id",
        media3Id = "media-$id",
        providerName = "Prov",
        kind = DownloadKind.EPISODE.name,
        title = title,
        subtitle = "S${season}E$episode",
        posterUrl = "https://img.example/ep-$id.jpg",
        videoTypeJson = """{"showId":"$showId","showTitle":"$showTitle","showPoster":"https://img.example/$showId.jpg","seasonNumber":$season,"number":$episode,"id":"$id"}""",
        state = DownloadItemState.COMPLETED.name,
        createdAt = createdAt,
        updatedAt = createdAt,
        completedAt = createdAt,
    )
}
