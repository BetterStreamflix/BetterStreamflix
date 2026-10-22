package com.betterstreamflix.app.download

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
    fun rewritesUriOnAllExtTagsIncludingSessionKeyAndSingleQuotes() {
        val playlist = """
            #EXTM3U
            #EXT-X-SESSION-KEY:METHOD=AES-128,URI="session.key"
            #EXT-X-PRELOAD-HINT:TYPE=PART,URI='part1.m4s'
            #EXT-X-PART:DURATION=1.0,URI="part2.m4s"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="en",URI="audio.m3u8"
        """.trimIndent()
        val rewritten = OfflinePlaylistRewrite.rewrite(
            playlist,
            "https://cdn.example/master.m3u8",
        ) { absolute -> "local:$absolute" }
        assertTrue(rewritten.contains("""URI="local:https://cdn.example/session.key""""))
        assertTrue(rewritten.contains("""URI='local:https://cdn.example/part1.m4s'"""))
        assertTrue(rewritten.contains("""URI="local:https://cdn.example/part2.m4s""""))
        assertTrue(rewritten.contains("""URI="local:https://cdn.example/audio.m3u8""""))
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
