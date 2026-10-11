package com.dskja.betterstreamflix.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastPlaylistRewriterTest {
    @Test
    fun rewritesRelativeSegmentsThroughProxy() {
        val playlist = """
            #EXTM3U
            #EXTINF:4.0,
            segment0.ts
            #EXTINF:4.0,
            ../other/segment1.ts
        """.trimIndent()
        val out = CastPlaylistRewriter.rewrite(
            playlistText = playlist,
            playlistUrl = "https://cdn.example.com/hls/master.m3u8",
            proxyBase = "http://192.168.1.10:8080",
            sessionToken = "tok123",
        )
        assertTrue(out.contains("http://192.168.1.10:8080/p?u="))
        assertTrue(out.contains("&t=tok123"))
        assertTrue(out.contains("segment0.ts") || out.contains("cdn.example.com"))
        assertTrue(out.lines().none { it.trim() == "segment0.ts" })
    }

    @Test
    fun rewritesUriAttributesInTags() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.key"
            #EXTINF:4.0,
            https://cdn.example.com/a.ts
        """.trimIndent()
        val out = CastPlaylistRewriter.rewrite(
            playlistText = playlist,
            playlistUrl = "https://cdn.example.com/hls/master.m3u8",
            proxyBase = "http://10.0.0.2:9",
            sessionToken = "abc",
        )
        assertTrue(out.contains("URI=\"http://10.0.0.2:9/p?u="))
        assertTrue(out.contains("&t=abc"))
        assertTrue(out.contains("http://10.0.0.2:9/p?u="))
    }

    @Test
    fun resolveAgainstKeepsAbsolute() {
        assertEquals(
            "https://cdn.example.com/x.ts",
            CastPlaylistRewriter.resolveAgainst("https://base/a.m3u8", "https://cdn.example.com/x.ts"),
        )
    }

    @Test
    fun blocksPrivateProxyTargets() {
        assertTrue(CastStreamProxyServer.isBlockedProxyTarget("http://127.0.0.1/secret"))
        assertTrue(CastStreamProxyServer.isBlockedProxyTarget("http://localhost/x"))
        assertTrue(CastStreamProxyServer.isBlockedProxyTarget("http://192.168.1.1/admin"))
        assertTrue(CastStreamProxyServer.isBlockedProxyTarget("http://10.0.0.5/"))
        assertTrue(CastStreamProxyServer.isBlockedProxyTarget("http://169.254.169.254/latest/meta-data/"))
        // Unregistered public host is blocked (open-relay close).
        assertTrue(
            CastStreamProxyServer.isBlockedProxyTarget(
                "https://cdn.example.com/a.ts",
                allowedHosts = setOf("other.example.com"),
            ),
        )
        // Explicitly wrapped LAN IPTV hosts require lanAllowedHosts.
        assertTrue(
            !CastStreamProxyServer.isBlockedProxyTarget(
                "http://192.168.1.50/live.m3u8",
                allowedHosts = setOf("192.168.1.50"),
                lanAllowedHosts = setOf("192.168.1.50"),
            ),
        )
        // Registered hostname that resolves private without LAN flag stays blocked.
        assertTrue(
            CastStreamProxyServer.isBlockedProxyTarget(
                "http://192.168.1.50/live.m3u8",
                allowedHosts = setOf("192.168.1.50"),
                lanAllowedHosts = emptySet(),
            ),
        )
    }

    @Test
    fun rewritesSingleQuotedAndUnquotedUriAttributes() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI='key.key'
            #EXT-X-MAP:URI=init.mp4
            #EXTINF:4.0,
            seg.ts
        """.trimIndent()
        val hosts = mutableSetOf<String>()
        val out = CastPlaylistRewriter.rewrite(
            playlistText = playlist,
            playlistUrl = "https://cdn.example.com/hls/master.m3u8",
            proxyBase = "http://10.0.0.2:9",
            sessionToken = "tok",
            onAbsoluteUrl = { url ->
                java.net.URI(url).host?.let { hosts.add(it.lowercase()) }
            },
        )
        assertTrue(out.contains("URI=\"http://10.0.0.2:9/p?u="))
        assertTrue(hosts.contains("cdn.example.com"))
    }

    @Test
    fun rewritesMpdAbsoluteMediaUrls() {
        val mpd = """
            <MPD>
              <SegmentTemplate media="https://cdn2.example.com/seg-1.m4s" initialization="https://cdn2.example.com/init.mp4"/>
            </MPD>
        """.trimIndent()
        val hosts = mutableSetOf<String>()
        val out = CastPlaylistRewriter.rewriteMpd(
            mpdText = mpd,
            playlistUrl = "https://cdn.example.com/dash.mpd",
            proxyBase = "http://10.0.0.2:9",
            sessionToken = "t",
            onAbsoluteUrl = { url ->
                java.net.URI(url).host?.let { hosts.add(it.lowercase()) }
            },
        )
        assertTrue(out.contains("http://10.0.0.2:9/p?u="))
        assertTrue(hosts.contains("cdn2.example.com"))
    }
}
