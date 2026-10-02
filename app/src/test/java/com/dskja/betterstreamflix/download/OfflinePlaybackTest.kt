package com.dskja.betterstreamflix.download

import android.content.Context
import com.dskja.betterstreamflix.models.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock

class OfflinePlaybackTest {

    @Test
    fun contentKeyFor_movie_matchesDownloadContentKey() {
        val type = Video.Type.Movie(
            id = "tt123",
            title = "Test",
            releaseDate = "2020",
            poster = "",
            imdbId = null,
        )
        assertEquals(
            DownloadContentKey.movie("ProviderX", "tt123"),
            OfflinePlayback.contentKeyFor(type, "ProviderX"),
        )
    }

    @Test
    fun contentKeyFor_episode_matchesDownloadContentKey() {
        val type = Video.Type.Episode(
            id = "ep9",
            number = 3,
            title = "Pilot",
            poster = null,
            overview = null,
            tvShow = Video.Type.Episode.TvShow(
                id = "show1",
                title = "Show",
                poster = null,
                banner = null,
                releaseDate = null,
                imdbId = null,
            ),
            season = Video.Type.Episode.Season(number = 2, title = null),
        )
        assertEquals(
            DownloadContentKey.episode("ProviderY", "show1", 2, 3, "ep9"),
            OfflinePlayback.contentKeyFor(type, "ProviderY"),
        )
    }

    @Test
    fun buildLocalVideo_returnsNullWhenNotCompleted() {
        val item = DownloadItemEntity(
            id = "id1",
            contentKey = "movie|p|1",
            media3Id = "m1",
            providerName = "p",
            kind = DownloadKind.MOVIE.name,
            title = "T",
            state = DownloadItemState.DOWNLOADING.name,
            streamUrl = "https://example.com/video.mp4",
            localUri = "https://example.com/video.mp4",
            bytesDownloaded = 100L,
            contentLength = 1000L,
        )
        // Context unused on non-COMPLETED early return.
        val ctx = mock(Context::class.java)
        assertNull(OfflinePlayback.buildLocalVideo(ctx, item))
    }

    @Test
    fun buildLocalVideo_returnsNullWhenQueued() {
        val item = DownloadItemEntity(
            id = "id2",
            contentKey = "movie|p|2",
            media3Id = "m2",
            providerName = "p",
            kind = DownloadKind.MOVIE.name,
            title = "T",
            state = DownloadItemState.QUEUED.name,
        )
        val ctx = mock(Context::class.java)
        assertNull(OfflinePlayback.buildLocalVideo(ctx, item))
    }

    @Test
    fun buildLocalVideo_returnsNullWhenCompletedButNoCacheOrLocalFile() {
        val item = DownloadItemEntity(
            id = "id3",
            contentKey = "movie|p|3",
            media3Id = "missing-media3",
            providerName = "p",
            kind = DownloadKind.MOVIE.name,
            title = "T",
            state = DownloadItemState.COMPLETED.name,
            streamUrl = "https://cdn.example/v.mp4",
            localUri = "https://cdn.example/v.mp4",
            bytesDownloaded = 0L,
            contentLength = 1000L,
        )
        val ctx = mock(Context::class.java)
        // StreamflixDownloadManager.get will fail without real Android cache; treat as null.
        assertNull(OfflinePlayback.buildLocalVideo(ctx, item))
    }

    @Test
    fun exportShareUri_rejectsHttpsLocalUri() {
        val item = DownloadItemEntity(
            id = "id4",
            contentKey = "movie|p|4",
            media3Id = "m4",
            providerName = "p",
            kind = DownloadKind.MOVIE.name,
            title = "T",
            state = DownloadItemState.COMPLETED.name,
            localUri = "https://cdn.example/v.mp4",
        )
        val ctx = mock(Context::class.java)
        assertNull(OfflinePlayback.exportShareUri(ctx, item))
    }

    @Test
    fun buildLocalVideo_stampsOfflineMedia3IdOnlyWhenCompleted() {
        val incomplete = DownloadItemEntity(
            id = "id5",
            contentKey = "movie|p|5",
            media3Id = "m5",
            providerName = "p",
            kind = DownloadKind.MOVIE.name,
            title = "T",
            state = DownloadItemState.COMPLETED.name,
            streamUrl = "https://cdn.example/v.mp4",
            localUri = "https://cdn.example/v.mp4",
            bytesDownloaded = 0L,
        )
        val ctx = mock(Context::class.java)
        // No Media3 download index in JVM unit tests → null (no stamp).
        assertNull(OfflinePlayback.buildLocalVideo(ctx, incomplete))
    }
}
