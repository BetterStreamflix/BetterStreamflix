package com.dskja.betterstreamflix.cast

import android.util.Log
import com.dskja.betterstreamflix.utils.BypassWebSocketEndpointHelper
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import com.dskja.betterstreamflix.utils.NetworkClient
import java.io.File
import java.io.FileInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
 * (H-CAST-1). Only hosts registered via [wrap] / playlist rewrite are proxied
 * (SEC-CAST-02). Private targets require explicit LAN registration (SEC-CAST-01).
 * DNS is re-resolved on every hop to resist rebinding (SEC-CAST-03).
 */
class CastStreamProxyServer(
    private val httpClient: OkHttpClient = defaultClient(),
) : NanoHTTPD(0) {

    @Volatile
    private var defaultHeaders: Map<String, String> = emptyMap()

    /** Opaque token required on `/p` while this instance is running. */
    val sessionToken: String = UUID.randomUUID().toString().replace("-", "")

    private val pumpExecutor = Executors.newCachedThreadPool()
    private val allowedHosts = ConcurrentHashMap.newKeySet<String>()
    /** Hosts intentionally wrapped for LAN IPTV (may resolve to private IPs). */
    private val lanAllowedHosts = ConcurrentHashMap.newKeySet<String>()
    private val localFiles = ConcurrentHashMap<String, File>()

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
        registerAllowedHost(originalUrl, allowPrivate = true)
        val base = publicBaseUrl() ?: return originalUrl
        return CastPlaylistRewriter.proxyUrl(base, originalUrl, sessionToken)
    }

    /**
     * Register a host discovered while rewriting playlists.
     * Private / loopback / CGNAT hosts are rejected here (SEC-CAST-01).
     */
    fun registerAllowedHost(url: String) {
        registerAllowedHost(url, allowPrivate = false)
    }

    private fun registerAllowedHost(url: String, allowPrivate: Boolean) {
        val host = runCatching { java.net.URI(url).host?.lowercase() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return
        if (isAlwaysBlockedHost(host)) return
        val inet = runCatching { InetAddress.getByName(host) }.getOrNull()
        if (inet != null && isBlockedAddress(inet)) {
            if (!allowPrivate) return
            lanAllowedHosts.add(host)
        }
        allowedHosts.add(host)
    }

    /** Serve a local subtitle/media file to Chromecast over LAN HTTP. */
    fun wrapLocalFile(file: File): String? {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return null
        val base = publicBaseUrl() ?: return null
        val id = UUID.randomUUID().toString().replace("-", "")
        localFiles[id] = file
        return "$base/f?t=$sessionToken&id=$id"
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
                session.uri == "/health" -> {
                    // Require session token — unauthenticated health was an open probe (CAST-P3-1).
                    val presented = session.parms["t"].orEmpty()
                    if (presented.isBlank() || presented != sessionToken) {
                        newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "unauthorized")
                    } else {
                        newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
                    }
                }
                session.uri == "/f" -> serveLocal(session)
                session.uri == "/p" -> proxy(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cast proxy failed", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "proxy error",
            )
        }
    }

    private fun serveLocal(session: IHTTPSession): Response {
        val presented = session.parms["t"].orEmpty()
        if (presented.isBlank() || presented != sessionToken) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "unauthorized")
        }
        val id = session.parms["id"].orEmpty()
        val file = localFiles[id]
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "missing file")
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "gone")
        }
        val mime = when {
            file.name.endsWith(".vtt", true) -> "text/vtt"
            file.name.endsWith(".srt", true) -> "application/x-subrip"
            file.name.endsWith(".ass", true) || file.name.endsWith(".ssa", true) -> "text/x-ssa"
            else -> "application/octet-stream"
        }
        val response = newFixedLengthResponse(
            Response.Status.OK,
            mime,
            FileInputStream(file),
            file.length(),
        )
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
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
        if (isBlockedProxyTarget(target, allowedHosts, lanAllowedHosts)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "blocked target")
        }

        val requestBuilder = Request.Builder().url(target).get()
        val mergedHeaders = linkedMapOf("User-Agent" to NetworkClient.USER_AGENT)
        // Only forward session headers to hosts that were explicitly wrapped /
        // playlist-registered (credentialed open-relay mitigation, SEC-CAST-02).
        val targetHost = runCatching { java.net.URI(target).host?.lowercase() }.getOrNull()
        if (targetHost != null && allowedHosts.contains(targetHost)) {
            mergedHeaders.putAll(defaultHeaders)
        }
        val uaKey = mergedHeaders.keys.firstOrNull { it.equals("User-Agent", ignoreCase = true) }
        val ua = uaKey?.let { mergedHeaders[it] }
        if (ua.isNullOrBlank() || ua.startsWith("okhttp", ignoreCase = true)) {
            if (uaKey != null) mergedHeaders.remove(uaKey)
            mergedHeaders["User-Agent"] = NetworkClient.USER_AGENT
        }
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

        val upstream = executeWithValidatedRedirects(requestBuilder.build())
            ?: return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "blocked redirect")
        var transferOwnershipToPump = false
        try {
            val body = upstream.body
            val contentType = body?.contentType()?.toString()
                ?: upstream.header("Content-Type")
                ?: "application/octet-stream"
            val finalUrl = upstream.request.url.toString()

            if (!upstream.isSuccessful && body == null) {
                return newFixedLengthResponse(
                    Response.Status.lookup(upstream.code) ?: Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "upstream error",
                )
            }

            val looksLikePlaylist = contentType.contains("mpegurl", ignoreCase = true) ||
                contentType.contains("m3u8", ignoreCase = true) ||
                contentType.contains("dash+xml", ignoreCase = true) ||
                contentType.contains("mpd", ignoreCase = true) ||
                finalUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
                finalUrl.substringBefore('?').endsWith(".mpd", ignoreCase = true) ||
                target.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
                target.substringBefore('?').endsWith(".mpd", ignoreCase = true)

            if (looksLikePlaylist) {
                val bytes = body?.bytes() ?: ByteArray(0)
                val rewritten = maybeRewritePlaylist(finalUrl.ifBlank { target }, contentType, bytes)
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
                    Log.w(TAG, "Cast proxy stream pump ended")
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

    /**
     * Follow redirects manually so each hop is re-validated against the allowlist
     * and always-blocked hosts (metadata / loopback).
     */
    private fun executeWithValidatedRedirects(initial: Request): okhttp3.Response? {
        var request = initial
        repeat(MAX_REDIRECTS + 1) { hop ->
            val response = httpClient.newCall(request).execute()
            if (!response.isRedirect) return response
            val location = response.header("Location").orEmpty()
            response.close()
            if (location.isBlank()) return null
            val nextUrl = runCatching {
                java.net.URI(request.url.toString()).resolve(location).toString()
            }.getOrDefault(location)
            if (!nextUrl.startsWith("http://") && !nextUrl.startsWith("https://")) return null
            if (isBlockedProxyTarget(nextUrl, allowedHosts, lanAllowedHosts)) {
                Log.w(TAG, "Blocked cast proxy redirect hop=$hop")
                return null
            }
            // Redirect hops never expand the LAN allowlist (SEC-CAST-01/03).
            registerAllowedHost(nextUrl, allowPrivate = false)
            request = request.newBuilder().url(nextUrl).build()
        }
        return null
    }

    private fun decorate(response: Response, upstream: okhttp3.Response) {
        upstream.header("Accept-Ranges")?.let { response.addHeader("Accept-Ranges", it) }
        upstream.header("Content-Range")?.let { response.addHeader("Content-Range", it) }
        upstream.header("Cache-Control")?.let { response.addHeader("Cache-Control", it) }
        response.addHeader("Access-Control-Allow-Origin", "*")
    }

    private fun maybeRewritePlaylist(playlistUrl: String, contentType: String, bytes: ByteArray): ByteArray {
        val text = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull() ?: return bytes
        val base = publicBaseUrl() ?: return bytes
        val looksM3u = text.contains("#EXTM3U")
        val looksMpd = text.contains("<MPD") || contentType.contains("dash", ignoreCase = true) ||
            playlistUrl.substringBefore('?').endsWith(".mpd", ignoreCase = true)
        if (!looksM3u && !looksMpd) return bytes
        val rewritten = if (looksMpd && !looksM3u) {
            CastPlaylistRewriter.rewriteMpd(text, playlistUrl, base, sessionToken) { url ->
                registerAllowedHost(url, allowPrivate = false)
            }
        } else {
            CastPlaylistRewriter.rewrite(text, playlistUrl, base, sessionToken) { url ->
                registerAllowedHost(url, allowPrivate = false)
            }
        }
        return rewritten.toByteArray(StandardCharsets.UTF_8)
    }

    override fun stop() {
        clearDefaultHeaders()
        allowedHosts.clear()
        lanAllowedHosts.clear()
        localFiles.clear()
        runCatching { super.stop() }
        runCatching { pumpExecutor.shutdownNow() }
    }

    companion object {
        private const val TAG = "CastStreamProxy"
        private const val MAX_REDIRECTS = 5

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                // Manual redirect validation in [executeWithValidatedRedirects] (CAST-P1-3).
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()

        private fun isAlwaysBlockedHost(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || h.endsWith(".localhost") || h == "0.0.0.0") return true
            if (h == "::1" || h == "[::1]") return true
            if (h == "169.254.169.254" || h == "metadata.google.internal") return true
            // Literal loopback / metadata always blocked even if somehow registered.
            if (h == "127.0.0.1" || h.startsWith("127.")) return true
            return false
        }

        private fun isBlockedAddress(inet: InetAddress): Boolean {
            if (inet.isAnyLocalAddress ||
                inet.isLoopbackAddress ||
                inet.isLinkLocalAddress ||
                inet.isSiteLocalAddress ||
                inet.isMulticastAddress
            ) {
                return true
            }
            // CGNAT 100.64.0.0/10 (SEC-CAST-04)
            if (inet is Inet4Address) {
                val b = inet.address
                if (b.size == 4) {
                    val first = b[0].toInt() and 0xff
                    val second = b[1].toInt() and 0xff
                    if (first == 100 && second in 64..127) return true
                }
            }
            // IPv6 ULA fc00::/7
            if (inet is Inet6Address) {
                val b = inet.address
                if (b.isNotEmpty() && (b[0].toInt() and 0xfe) == 0xfc) return true
            }
            return false
        }

        /**
         * Reject unregistered hosts and private/metadata targets.
         * Allowlist membership alone is not enough — DNS is re-resolved so a
         * registered public name cannot rebind to RFC1918 / CGNAT / ULA
         * unless it was explicitly LAN-registered via [wrap] (SEC-CAST-01/03).
         */
        @JvmStatic
        @JvmOverloads
        fun isBlockedProxyTarget(
            url: String,
            allowedHosts: Set<String> = emptySet(),
            lanAllowedHosts: Set<String> = emptySet(),
        ): Boolean {
            val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return true
            if (isAlwaysBlockedHost(host)) return true
            // Open-relay close: only previously registered hosts (SEC-CAST-02).
            if (allowedHosts.isNotEmpty() && !allowedHosts.contains(host)) return true
            // When no allowlist is supplied (unit tests of address policy), fall
            // through to address checks only.
            if (allowedHosts.isEmpty()) {
                val inet = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
                return isBlockedAddress(inet)
            }
            val inet = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return true
            if (isBlockedAddress(inet)) {
                // Private resolution allowed only for intentionally wrapped LAN hosts.
                return !lanAllowedHosts.contains(host)
            }
            return false
        }
    }
}
