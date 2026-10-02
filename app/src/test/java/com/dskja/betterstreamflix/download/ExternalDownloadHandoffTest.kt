package com.dskja.betterstreamflix.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalDownloadHandoffTest {

    @Test
    fun candidatePackages_includeAdmVariants() {
        assertTrue(ExternalDownloadHandoff.CANDIDATE_PACKAGES.contains("com.dv.adm"))
        assertTrue(ExternalDownloadHandoff.CANDIDATE_PACKAGES.contains("com.dv.adm.pay"))
        assertTrue(ExternalDownloadHandoff.CANDIDATE_PACKAGES.contains("idm.internet.download.manager"))
    }

    @Test
    fun sanitizeFileName_stripsIllegalChars() {
        assertEquals(
            "Movie_Title_S01E01",
            ExternalDownloadHandoff.sanitizeFileName("Movie/Title:S01E01"),
        )
        assertEquals("stream", ExternalDownloadHandoff.sanitizeFileName("  "))
    }

    @Test
    fun storageLocation_parsesRemovableAndCustom() {
        assertEquals(DownloadStorageLocation.REMOVABLE, DownloadStorageLocation.fromKey("removable"))
        assertEquals(
            DownloadStorageLocation.CUSTOM_FOLDER,
            DownloadStorageLocation.fromKey("CUSTOM_FOLDER"),
        )
    }

    @Test
    fun volumeIdFromTree_extractsUuidFromDocId() {
        val docId = java.net.URLDecoder.decode("ABCD-1234%3AMovies", Charsets.UTF_8.name())
        val volume = docId.substringBefore(':', missingDelimiterValue = "")
        assertEquals("ABCD-1234", volume)
        assertEquals("Movies", docId.substringAfter(':'))
    }
}
