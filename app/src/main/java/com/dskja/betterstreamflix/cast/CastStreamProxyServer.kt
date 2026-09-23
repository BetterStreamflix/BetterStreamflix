package com.dskja.betterstreamflix.cast

import android.util.Log
import com.dskja.betterstreamflix.utils.BypassWebSocketEndpointHelper
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.userAgent
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LAN-facing stream proxy so Chromecast can fetch URLs that require custom headers
 * (Referer, Cookie, User-Agent, tokens) which the default Cast receiver cannot send.
 *
 * Chromecast cannot reach the phone's 127.0.0.1 — we advertise the device LAN IP.
 * Large media responses are streamed; HLS playlists are rewritten so variants/segments
 * also flow through this proxy.
 *
 * Auth: every `/p` request must carry the per-instance session token issued by [wrap]
 * (H-CAST-1). Private/loopback targets are rejected to limit open-relay SSRF.
 */
class CastStreamProxyServer(
    private val httpClient: OkHttpClient = defaultClient(),
) : NanoHTTPD(0) {

    @Volatile
    private var defaultHeaders: Map<String, String> = emptyMap()

    /** Opaque token required on `/p` while this instance is running. */
    val sessionToken: String = UUID.randomUUID().toString().replace("-", "")

    private val pumpExecutor = Executors.newCachedThreadPool()

    fun updateDefaultHeaders(headers: Map<String, String>) {
        defaultHeaders = headers
    }

    fun clearDefaultHeaders() {
        defaultHeaders = emptyMap()
    }

    fun publicBaseUrl(): String? {
        val host = BypassWebSocketEndpointHelper.getLocalIpv4Address() ?: return null
        return "http://$host:$listeningPort"
    }

    /** Build a Cast-reachable URL that proxies [originalUrl] with the current headers. */
    fun wrap(originalUrl: String): String {
        val base = publicBaseUrl() ?: return originalUrl
        return CastPlaylistRewriter.proxyUrl(base, originalUrl, sessionToken)
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.OPTIONS -> {
                    newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "").also {
                        it.addHeader("Access-Control-Allow-Origin", "*")
                        it.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
                        it.addHeader("Access-Control-Allow-Headers", "Range, Content-Type")
                    }
                }
                session.uri == "/health" ->
                    newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
                session.uri == "/p" || session.uri.startsWith("/p") -> proxy(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cast proxy failed", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message ?: "proxy error",
            )
        }
    }

    private fun proxy(session: IHTTPSession): Response {
        val presented = session.parms["t"].orEmpty()
        if (presented.isBlank() || presented != sessionToken) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "unauthorized")
        }

        val encoded = session.parms["u"].orEmpty()
        if (encoded.isBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "missing u")
        }
        val target = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "unsupported scheme")
        }
        if (isBlockedProxyTarget(target)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "blocked target")
        }

        val requestBuilder = Request.Builder().url(target).get()
        val mergedHeaders = linkedMapOf("User-Agent" to userAgent)
        mergedHeaders.putAll(defaultHeaders)
        // Chromecast cannot send custom headers; VOE/SerienStream CDNs 404 without Referer.
        if (mergedHeaders.keys.none { it.equals("Referer", ignoreCase = true) }) {
            runCatching {
                val uri = java.net.URI(target)
                val origin = "${uri.scheme}://${uri.host}/"
                mergedHeaders["Referer"] = origin
                mergedHeaders.putIfAbsent("Origin", "${uri.scheme}://${uri.host}")
            }
        }
        session.headers["range"]?.let { mergedHeaders["Range"] = it }
        session.headers["accept"]?.let { mergedHeaders.putIfAbsent("Accept", it) }
        mergedHeaders.forEach { (key, value) ->
            if (key.equals("Host", ignoreCase = true)) return@forEach
            requestBuilder.header(key, value)
        }

        val upstream = httpClient.newCall(requestBuilder.build()).execute()
        var transferOwnershipToPump = false
        try {
            val body = upstream.body
            val contentType = body?.contentType()?.toString()
                ?: upstream.header("Content-Type")
                ?: "application/octet-stream"

            if (!upstream.isSuccessful && body == null) {
                return newFixedLengthResponse(
                    Response.Status.lookup(upstream.code) ?: Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "upstream ${upstream.code}",
                )
            }

            val looksLikePlaylist = contentType.contains("mpegurl", ignoreCase = true) ||
                contentType.contains("m3u8", ignoreCase = true) ||
                target.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

            if (looksLikePlaylist) {
                val bytes = body?.bytes() ?: ByteArray(0)
                val rewritten = maybeRewritePlaylist(target, contentType, bytes)
                val response = newFixedLengthResponse(
                    Response.Status.lookup(upstream.code) ?: Response.Status.OK,
                    contentType,
                    rewritten.inputStream(),
                    rewritten.size.toLong(),
                )
                decorate(response, upstream)
                return response
            }

            // Stream large media so Chromecast can start sooner and we avoid OOM.
            val contentLength = body?.contentLength() ?: upstream.header("Content-Length")?.toLongOrNull() ?: -1L
            val pipedIn = PipedInputStream(256 * 1024)
            val pipedOut = PipedOutputStream(pipedIn)
            transferOwnershipToPump = true
            pumpExecutor.execute {
                try {
                    upstream.body?.byteStream()?.use { input ->
                        input.copyTo(pipedOut, 64 * 1024)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Cast proxy stream pump ended: ${e.message}")
                } finally {
                    runCatching { pipedOut.close() }
                    runCatching { upstream.close() }
                }
            }

            val response = newFixedLengthResponse(
                Response.Status.lookup(upstream.code) ?: Response.Status.OK,
                contentType,
                pipedIn,
                contentLength,
            )
            decorate(response, upstream)
            response.setChunkedTransfer(contentLength < 0)
            return response
        } catch (e: Exception) {
            if (!transferOwnershipToPump) {
                runCatching { upstream.close() }
            }
            throw e
        } finally {
            if (!transferOwnershipToPump) {
                runCatching { upstream.close() }
            }
        }
    }

    private fun decorate(response: Response, upstream: okhttp3.Response) {
        upstream.header("Accept-Ranges")?.let { response.addHeader("Accept-Ranges", it) }
        upstream.header("Content-Range")?.let { response.addHeader("Content-Range", it) }
        upstream.header("Cache-Control")?.let { response.addHeader("Cache-Control", it) }
        response.addHeader("Access-Control-Allow-Origin", "*")
    }

    private fun maybeRewritePlaylist(playlistUrl: String, contentType: String, bytes: ByteArray): ByteArray {
        val text = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull() ?: return bytes
        if (!text.contains("#EXTM3U")) return bytes
        val base = publicBaseUrl() ?: return bytes
        return CastPlaylistRewriter.rewrite(text, playlistUrl, base, sessionToken)
            .toByteArray(StandardCharsets.UTF_8)
    }

    override fun stop() {
        clearDefaultHeaders()
        runCatching { super.stop() }
        runCatching { pumpExecutor.shutdownNow() }
    }

    companion object {
        private const val TAG = "CastStreamProxy"

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()

        /**
         * Reject loopback / link-local / RFC1918 targets so a LAN peer cannot use
         * the proxy as an open SSRF relay into the phone's private network.
         */
        fun isBlockedProxyTarget(url: String): Boolean {
            val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return true
            if (host == "localhost" || host.endsWith(".localhost") || host == "0.0.0.0") return true
            if (host == "::1" || host == "[::1]") return true
            val inet = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            return inet.isAnyLocalAddress ||
                inet.isLoopbackAddress ||
                inet.isLinkLocalAddress ||
                inet.isSiteLocalAddress ||
                inet.isMulticastAddress
        }
    }
}
