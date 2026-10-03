package com.dskja.betterstreamflix.download

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalDownloadHandoffTest {

    @Test
    fun candidatePackages_includeAdmAndOneDm_only() {
        assertTrue(ExternalDownloadHandoff.CANDIDATE_PACKAGES.contains("com.dv.adm"))
        assertTrue(ExternalDownloadHandoff.CANDIDATE_PACKAGES.contains("com.dv.adm.pay"))
        assertTrue(ExternalDownloadHandoff.CANDIDATE_PACKAGES.contains("idm.internet.download.manager"))
        assertFalse(ExternalDownloadHandoff.CANDIDATE_PACKAGES.contains("com.aefyr.sai"))
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

    @Test
    fun describeAdm_usesMainAndAEditor_withTextAndFilename() {
        val request = ExternalDownloadHandoff.Request(
            url = "https://cdn.example/stream.m3u8",
            fileName = "Show S01E01",
            headers = mapOf("Referer" to "https://example.com/"),
        )
        val spec = ExternalDownloadHandoff.describeForPackage(request, "com.dv.adm")
        assertEquals(Intent.ACTION_MAIN, spec.action)
        assertEquals("com.dv.adm", spec.packageName)
        assertEquals("com.dv.adm.AEditor", spec.className)
        assertNull(spec.dataUri)
        assertEquals("https://cdn.example/stream.m3u8", spec.extras[Intent.EXTRA_TEXT])
        assertEquals("Show S01E01", spec.extras["com.android.extra.filename"])
        assertEquals("https://example.com/", spec.extras["Referer"])
    }

    @Test
    fun describeAdmPro_targetsPayPackage() {
        val request = ExternalDownloadHandoff.Request(
            url = "https://cdn.example/a.mp4",
            fileName = "movie.mp4",
        )
        val spec = ExternalDownloadHandoff.describeForPackage(request, "com.dv.adm.pay")
        assertEquals(Intent.ACTION_MAIN, spec.action)
        assertEquals("com.dv.adm.pay", spec.packageName)
        assertEquals("com.dv.adm.AEditor", spec.className)
        assertEquals("https://cdn.example/a.mp4", spec.extras[Intent.EXTRA_TEXT])
    }

    @Test
    fun describeOneDm_usesDownloaderAndExtraFilename() {
        val request = ExternalDownloadHandoff.Request(
            url = "https://cdn.example/clip.mp4",
            fileName = "clip.mp4",
        )
        val spec = ExternalDownloadHandoff.describeForPackage(
            request,
            "idm.internet.download.manager",
        )
        assertEquals(Intent.ACTION_VIEW, spec.action)
        assertEquals("idm.internet.download.manager", spec.packageName)
        assertEquals("idm.internet.download.manager.Downloader", spec.className)
        assertEquals("https://cdn.example/clip.mp4", spec.dataUri)
        assertEquals("clip.mp4", spec.extras["extra_filename"])
    }

    @Test
    fun isAdmAndOneDm_packageHelpers() {
        assertTrue(ExternalDownloadHandoff.isAdmPackage("com.dv.adm"))
        assertTrue(ExternalDownloadHandoff.isAdmPackage("com.dv.adm.pay"))
        assertFalse(ExternalDownloadHandoff.isAdmPackage("idm.internet.download.manager"))
        assertTrue(ExternalDownloadHandoff.isOneDmPackage("idm.internet.download.manager.plus"))
    }

    @Test
    fun admEditorClassCandidates_includePayFallback() {
        val pay = ExternalDownloadHandoff.admEditorClassCandidates("com.dv.adm.pay")
        assertTrue(pay.contains("com.dv.adm.AEditor"))
        assertTrue(pay.contains("com.dv.adm.pay.AEditor"))
    }
}
