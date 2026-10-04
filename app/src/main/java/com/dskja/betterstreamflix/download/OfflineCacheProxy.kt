package com.dskja.betterstreamflix.download

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.media3.datasource.DataSource
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback HLS reader for Play with on a completed adaptive download.
 *
 * External players cannot open Media3's cache. This serves the cached playlist
 * and segments on 127.0.0.1 so VLC/MX/mpv play the finished download instead of
 * an expired CDN URL.
 */
object OfflineCacheProxy {
    private const val TAG = "OfflineCacheProxy"
    private const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024

    private val started = AtomicBoolean(false)
    private val routes = ConcurrentHashMap<String, Route>()
    private val io = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "offline-share").apply { isDaemon = true }
    }

    @Volatile
    private var port: Int = -1

    private data class Route(
        val appContext: Context,
        val media3Id: String,
        val playlistUri: String,
        val cacheKey: String?,
    )

    fun shareUrl(context: Context, media3Id: String, playlistUri: String, cacheKey: String?): String? {
        if (media3Id.isBlank() || playlistUri.isBlank()) return null
        if (!ensureStarted()) return null
        val id = media3Id.hashCode().toUInt().toString(16)
        val app = context.applicationContext
        routes[id] = Route(app, media3Id, playlistUri, cacheKey?.takeIf { it.isNotBlank() })
        val probe = readPlaylist(routes[id]!!, playlistUri, cacheKey?.takeIf { it.isNotBlank() })
            ?: return null
        if (!OfflinePlaylistRewrite.looksLikePlaylist(probe)) return null
        return "http://127.0.0.1:$port/$id/index.m3u8"
    }

    private fun ensureStarted(): Boolean {
        if (started.get() && port > 0) return true
        synchronized(this) {
            if (started.get() && port > 0) return true
            return try {
                val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
                port = server.localPort
                started.set(true)
                io.execute { acceptLoop(server) }
                true
            } catch (e: Exception) {
                Log.w(TAG, "Loopback share server failed: ${e.message}")
                false
            }
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            io.execute { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 20_000
            val reader = client.getInputStream().bufferedReader()
            val request = reader.readLine().orEmpty()
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
            }
            val path = request.split(' ').getOrNull(1)?.substringBefore('?').orEmpty()
            val query = request.split(' ').getOrNull(1)?.substringAfter('?', missingDelimiterValue = "").orEmpty()
            val parts = path.trim('/').split('/')
            val route = parts.getOrNull(0)?.let { routes[it] }
            if (route == null) {
                writeStatus(client, 404, "text/plain", "missing".toByteArray())
                return
            }
            if (parts.getOrNull(1) == "index.m3u8" || parts.size == 1) {
                val playlist = readPlaylist(route, route.playlistUri, route.cacheKey)
                if (playlist == null) {
                    writeStatus(client, 404, "text/plain", "playlist missing".toByteArray())
                    return
                }
                val rewritten = OfflinePlaylistRewrite.rewrite(playlist, route.playlistUri) { absolute ->
                    segmentUrl(routeId(route), absolute)
                }
                writeStatus(client, 200, "application/vnd.apple.mpegurl", rewritten.toByteArray(Charsets.UTF_8))
                return
            }
            val encoded = query.substringAfter("u=", "").substringBefore('&')
            val target = decodeUrl(encoded)
            if (target.isNullOrBlank()) {
                writeStatus(client, 400, "text/plain", "bad target".toByteArray())
                return
            }
            val nested = readPlaylist(route, target, cacheKey = null)
            if (nested != null && OfflinePlaylistRewrite.looksLikePlaylist(nested)) {
                val rewritten = OfflinePlaylistRewrite.rewrite(nested, target) { absolute ->
                    segmentUrl(routeId(route), absolute)
                }
                writeStatus(client, 200, "application/vnd.apple.mpegurl", rewritten.toByteArray(Charsets.UTF_8))
                return
            }
            streamCached(client, route, target)
        }
    }

    private fun routeId(route: Route): String =
        routes.entries.firstOrNull { it.value.media3Id == route.media3Id }?.key
            ?: route.media3Id.hashCode().toUInt().toString(16)

    private fun segmentUrl(routeId: String, absolute: String): String {
        val encoded = Base64.encodeToString(
            absolute.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "http://127.0.0.1:$port/$routeId/seg?u=$encoded"
    }

    private fun decodeUrl(encoded: String): String? {
        if (encoded.isBlank()) return null
        return runCatching {
            String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun readPlaylist(route: Route, uri: String, cacheKey: String?): String? {
        val source = open(route.appContext, uri, cacheKey) ?: return null
        return try {
            val buf = ByteArray(16 * 1024)
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (total < MAX_PLAYLIST_BYTES) {
                val read = source.read(buf, 0, buf.size)
                if (read == androidx.media3.common.C.RESULT_END_OF_INPUT || read <= 0) break
                out.write(buf, 0, read)
                total += read
            }
            val text = out.toString(Charsets.UTF_8.name())
            text.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { source.close() }
        }
    }

    private fun streamCached(client: Socket, route: Route, uri: String) {
        val source = open(route.appContext, uri, cacheKey = null)
        if (source == null) {
            writeStatus(client, 404, "text/plain", "segment missing".toByteArray())
            return
        }
        try {
            val header = "HTTP/1.1 200 OK\r\nContent-Type: ${contentType(uri)}\r\nConnection: close\r\n\r\n"
            val out = BufferedOutputStream(client.getOutputStream())
            out.write(header.toByteArray(Charsets.US_ASCII))
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = source.read(buf, 0, buf.size)
                if (read == androidx.media3.common.C.RESULT_END_OF_INPUT || read <= 0) break
                out.write(buf, 0, read)
            }
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Segment stream failed: ${e.message}")
        } finally {
            runCatching { source.close() }
        }
    }

    private fun open(context: Context, uri: String, cacheKey: String?): DataSource? {
        return runCatching {
            StreamflixDownloadManager.openOfflineSource(context, Uri.parse(uri), cacheKey)
        }.getOrNull()
    }

    private fun contentType(uri: String): String {
        val path = uri.lowercase().substringBefore('?')
        return when {
            path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".mp4") || path.endsWith(".m4s") || path.endsWith(".cmfv") -> "video/mp4"
            path.endsWith(".vtt") -> "text/vtt"
            path.endsWith(".webm") -> "video/webm"
            else -> "video/mp2t"
        }
    }

    private fun writeStatus(client: Socket, code: Int, type: String, body: ByteArray) {
        val reason = if (code == 200) "OK" else "ERR"
        val header = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        runCatching {
            val out = client.getOutputStream()
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        }
    }
}
