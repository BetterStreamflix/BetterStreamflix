package com.dskja.betterstreamflix.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePlaylistRewriteTest {
    @Test
    fun resolvesRelativeSegmentsAndKeyUri() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.key"
            #EXTINF:6,
            seg-1.ts
            #EXTINF:6,
            https://cdn.example/abs.ts
        """.trimIndent()
        val rewritten = OfflinePlaylistRewrite.rewrite(
            playlist,
            "https://cdn.example/master.m3u8",
        ) { absolute -> "local:$absolute" }
        assertTrue(rewritten.contains("""URI="local:https://cdn.example/key.key""""))
        assertTrue(rewritten.contains("local:https://cdn.example/seg-1.ts"))
        assertTrue(rewritten.contains("local:https://cdn.example/abs.ts"))
    }

    @Test
    fun sameTreeMatchesEncodedVolumeIds() {
        assertTrue(
            DownloadTreeAccess.sameTree(
                "content://com.android.externalstorage.documents/tree/ABCD-1234%3AMovies",
                "content://com.android.externalstorage.documents/tree/ABCD-1234:Download",
            ),
        )
        assertEquals("ABCD-1234", DownloadTreeAccess.treeVolumeId("content://x/tree/ABCD-1234%3AMovies"))
    }

    @Test
    fun mimeForShareUriFollowsPath() {
        assertEquals(
            "application/vnd.apple.mpegurl",
            OfflinePlayback.mimeForSharePath("http://127.0.0.1:9/abc/index.m3u8", "video/mp4"),
        )
        assertEquals(
            "video/mp4",
            OfflinePlayback.mimeForSharePath(
                "content://files/share_1.mp4",
                "application/vnd.apple.mpegurl",
            ),
        )
    }
}
