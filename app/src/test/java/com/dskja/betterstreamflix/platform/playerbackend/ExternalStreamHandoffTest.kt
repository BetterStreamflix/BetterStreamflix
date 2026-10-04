package com.dskja.betterstreamflix.platform.playerbackend

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalStreamHandoffTest {

    @Test
    fun extractUrlFromPlaylist_skipsComments() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            https://cdn.example/master.m3u8
        """.trimIndent()
        assertEquals(
            "https://cdn.example/master.m3u8",
            ExternalStreamHandoff.extractUrlFromPlaylist(playlist),
        )
    }

    @Test
    fun extractUrlFromPlaylist_returnsNullWhenEmpty() {
        assertNull(ExternalStreamHandoff.extractUrlFromPlaylist("#EXTM3U\n#EXT-X-ENDLIST\n"))
    }

    @Test
    fun extractUrlFromPlaylist_ignoresRelativeSegments() {
        val playlist = """
            #EXTM3U
            segment001.ts
            https://cdn.example/real.m3u8
        """.trimIndent()
        assertEquals(
            "https://cdn.example/real.m3u8",
            ExternalStreamHandoff.extractUrlFromPlaylist(playlist),
        )
    }

    @Test
    fun extractUrlFromPlaylist_returnsNullForRelativeOnly() {
        assertNull(ExternalStreamHandoff.extractUrlFromPlaylist("#EXTM3U\nsegment.ts\n"))
    }

    @Test
    fun headerArray_flattensKeyValuePairsForMx() {
        val headers = mapOf(
            "Referer" to "https://example.com/",
            "User-Agent" to "BetterStreamflix",
        )
        val headerArray = headers.flatMap { listOf(it.key, it.value) }
        assertEquals(listOf("Referer", "https://example.com/", "User-Agent", "BetterStreamflix"), headerArray)
    }

    @Test
    fun mpvHeaderLines_useLfAndReferrerSpelling() {
        val headers = mapOf("Referer" to "https://ref.example/", "Cookie" to "a=1")
        val headerLinesLf = headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        assertTrue(headerLinesLf.contains("Referer: https://ref.example/"))
        assertTrue(headerLinesLf.contains("Cookie: a=1"))
        assertTrue(!headerLinesLf.contains("\r"))
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
        assertEquals("https://ref.example/", referer)
    }

    @Test
    fun decodeBase64Uri_returnsNullOnMalformed() {
        assertNull(ExternalStreamHandoff.decodeBase64Uri("not-a-data-uri"))
        assertNull(ExternalStreamHandoff.decodeBase64Uri("data:text/plain;base64"))
    }

    @Test
    fun playWith_excludesAdmAndOneDmPackages() {
        assertTrue(ExternalStreamHandoff.isDownloaderPackage("com.dv.adm"))
        assertTrue(ExternalStreamHandoff.isDownloaderPackage("com.dv.adm.pay"))
        assertTrue(ExternalStreamHandoff.isDownloaderPackage("com.dv.adm.old"))
        assertTrue(ExternalStreamHandoff.isDownloaderPackage("idm.internet.download.manager"))
        assertTrue(ExternalStreamHandoff.isDownloaderPackage("idm.internet.download.manager.plus"))
        assertFalse(ExternalStreamHandoff.isEligiblePlayerPackage("com.dv.adm"))
        assertFalse(ExternalStreamHandoff.isEligiblePlayerPackage("idm.internet.download.manager"))
    }

    @Test
    fun playWith_allowsKnownVideoPlayers() {
        assertTrue(ExternalStreamHandoff.isEligiblePlayerPackage("org.videolan.vlc"))
        assertTrue(ExternalStreamHandoff.isEligiblePlayerPackage("com.mxtech.videoplayer.ad"))
        assertTrue(ExternalStreamHandoff.isEligiblePlayerPackage("com.mxtech.videoplayer.pro"))
        assertTrue(ExternalStreamHandoff.isEligiblePlayerPackage("is.xyz.mpv"))
        assertTrue(ExternalStreamHandoff.isEligiblePlayerPackage("com.brouken.player"))
        assertFalse(ExternalStreamHandoff.isDownloaderPackage("org.videolan.vlc"))
        assertTrue(ExternalStreamHandoff.KNOWN_PLAYER_PACKAGES.contains("org.videolan.vlc"))
    }

    @Test
    fun playWith_denylistMatchesDownloadHandoffCandidates() {
        for (pkg in com.dskja.betterstreamflix.download.ExternalDownloadHandoff.CANDIDATE_PACKAGES) {
            assertTrue(
                "Play with must exclude download candidate $pkg",
                ExternalStreamHandoff.isDownloaderPackage(pkg),
            )
            assertFalse(ExternalStreamHandoff.isEligiblePlayerPackage(pkg))
        }
    }

    @Test
    fun playWith_excludePackagesCoverDownloadersOnly() {
        val excluded = ExternalStreamHandoff.excludePackagesForPlayWith(
            listOf("com.dv.adm", "idm.internet.download.manager", "org.videolan.vlc"),
        )
        assertTrue(excluded.contains("com.dv.adm"))
        assertTrue(excluded.contains("idm.internet.download.manager"))
        // VLC is a player — must not be excluded.
        assertFalse(excluded.contains("org.videolan.vlc"))
        assertTrue(
            ExternalStreamHandoff.downloaderExcludeClassNames("com.dv.adm")
                .contains("com.dv.adm.AEditor"),
        )
        assertTrue(
            ExternalStreamHandoff.downloaderExcludeClassNames("org.videolan.vlc").isEmpty(),
        )
    }

    @Test
    fun playWith_intentActionIsViewWithDefaultCategory() {
        // Document the Play-with contract for regressions: ACTION_VIEW + CATEGORY_DEFAULT.
        assertEquals(Intent.ACTION_VIEW, Intent.ACTION_VIEW)
        assertEquals(Intent.CATEGORY_DEFAULT, Intent.CATEGORY_DEFAULT)
        assertTrue(ExternalStreamHandoff.DOWNLOADER_PACKAGE_DENYLIST.contains("com.dv.adm"))
        assertTrue(ExternalStreamHandoff.DOWNLOADER_PACKAGE_DENYLIST.contains("idm.internet.download.manager"))
    }
}
