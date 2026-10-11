package com.dskja.betterstreamflix.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSeriesFoldersTest {

    @Test
    fun sanitizeFolderName_stripsIllegalChars() {
        assertEquals("Show_Name", DownloadSeriesFolders.sanitizeFolderName("Show/Name"))
        assertEquals("A B", DownloadSeriesFolders.sanitizeFolderName("  A   B  "))
    }

    @Test
    fun seriesRef_fromVideoTypeJson() {
        val item = sampleEpisode(
            videoTypeJson = """{"kind":"episode","showId":"42","showTitle":"Demo Show","showPoster":"p.jpg"}""",
        )
        val ref = DownloadSeriesFolders.seriesRef(item)
        assertNotNull(ref)
        assertEquals("series|Prov|42", ref!!.key)
        assertEquals("Demo Show", ref.title)
    }

    @Test
    fun seriesRef_fromContentKeyFallback() {
        val item = sampleEpisode(
            contentKey = "episode|Prov|99|1|2|ep",
            title = "Fallback Title",
            videoTypeJson = "",
        )
        val ref = DownloadSeriesFolders.seriesRef(item)
        assertNotNull(ref)
        assertEquals("series|Prov|99", ref!!.key)
        assertEquals("Fallback Title", ref.title)
    }

    @Test
    fun seriesRef_nullForMovies() {
        val item = DownloadItemEntity(
            id = "1",
            contentKey = "movie|Prov|m1",
            media3Id = "1",
            providerName = "Prov",
            kind = DownloadKind.MOVIE.name,
            title = "Film",
        )
        assertNull(DownloadSeriesFolders.seriesRef(item))
    }

    @Test
    fun formatEta_humanReadable() {
        assertEquals("2h 5m", DownloadSeriesFolders.formatEta(null, 2 * 3600 + 5 * 60L))
        assertEquals("3m 10s", DownloadSeriesFolders.formatEta(null, 3 * 60 + 10L))
        assertEquals("45s", DownloadSeriesFolders.formatEta(null, 45L))
        assertEquals("…", DownloadSeriesFolders.formatEta(null, -1L))
    }

    @Test
    fun needsRedownload_storageRelocatedAndFileMissing() {
        val relocated = sampleEpisode(
            state = DownloadItemState.FAILED.name,
            errorCode = DownloadErrorCode.STORAGE_RELOCATED.name,
        )
        val missing = sampleEpisode(
            state = DownloadItemState.FAILED.name,
            errorCode = DownloadErrorCode.FILE_MISSING.name,
        )
        val network = sampleEpisode(
            state = DownloadItemState.FAILED.name,
            errorCode = DownloadErrorCode.NETWORK.name,
        )
        assertTrue(DownloadSeriesFolders.needsRedownload(relocated))
        assertTrue(DownloadSeriesFolders.needsRedownload(missing))
        assertFalse(DownloadSeriesFolders.needsRedownload(network))
    }

    private fun sampleEpisode(
        contentKey: String = "episode|Prov|1|1|1|e1",
        title: String = "Show",
        videoTypeJson: String = "",
        state: String = DownloadItemState.COMPLETED.name,
        errorCode: String = "",
    ) = DownloadItemEntity(
        id = "ep1",
        contentKey = contentKey,
        media3Id = "ep1",
        providerName = "Prov",
        kind = DownloadKind.EPISODE.name,
        title = title,
        videoTypeJson = videoTypeJson,
        state = state,
        errorCode = errorCode,
    )
}
