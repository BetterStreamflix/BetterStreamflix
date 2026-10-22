package com.betterstreamflix.app.utils

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BypassWebSocketServer(
    port: Int,
    private val onDone: (token: String, cookies: String?) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    private data class Session(
        val payload: String,
        @Volatile var boundPeer: String? = null,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val startedLatch = CountDownLatch(1)

    fun registerSession(token: String, payload: String) {
        sessions[token] = Session(payload = payload)
    }

    fun clearSession(token: String) {
        sessions.remove(token)
    }

    private var startError: Exception? = null

    fun getStartError(): Exception? = startError

    fun awaitStart(timeoutMs: Long = 5_000): Boolean {
        return startedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d("BypassWS", "Client connected: ${conn.remoteSocketAddress}")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        // Never log the payload: `done:` frames carry Base64 session cookies.
        Log.d("BypassWS", "Message: ${message.substringBefore(':')} (${message.length} chars)")
        val peer = peerKey(conn)

        when {
            message.startsWith("resolve:") -> {
                val token = message.substringAfter("resolve:")
                val session = sessions[token]
                if (session == null) {
                    conn.send("error:unknown_session")
                    return
                }
                // Bind token → first resolver peer; reject other peers (SEC-DL-01/03).
                val bound = session.boundPeer
                if (bound == null) {
                    session.boundPeer = peer
                } else if (bound != peer) {
                    Log.w("BypassWS", "Rejecting resolve from unbound peer for token=$token")
                    conn.send("error:unbound_peer")
                    return
                }
                conn.send("payload:${session.payload}")
            }

            message.startsWith("done:") -> {
                val payload = message.substringAfter("done:")
                val token = payload.substringBefore(":")
                val cookies = payload.substringAfter(":", "")
                    .takeIf { it.isNotBlank() }
                    ?.let { encoded ->
                        runCatching {
                            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                        }.getOrNull()
                    }
                val session = sessions[token]
                if (session == null) {
                    conn.send("error:unknown_session")
                    return
                }
                // Require prior resolve: from this same peer before accepting cookies.
                val bound = session.boundPeer
                if (bound == null || bound != peer) {
                    Log.w(
                        "BypassWS",
                        "Rejecting done: from unbound peer for token=$token (bound=$bound peer=$peer)",
                    )
                    conn.send("error:unbound_peer")
                    return
                }
                // One-shot: remove before applying cookies so replay cannot inject
                // cookies after completion (SEC-DL-03).
                if (sessions.remove(token) == null) {
                    conn.send("error:unknown_session")
                    return
                }
                // Bound cookie apply to SerienStream / AniWorld style cookie jars —
                // reject obviously hostile blobs (null bytes / extreme size).
                val safeCookies = cookies
                    ?.takeIf { it.length <= MAX_COOKIE_CHARS && '\u0000' !in it }
                conn.send("ack:$token")
                onDone(token, safeCookies)
            }
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.d("BypassWS", "Closed")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e("BypassWS", "Error", ex)
        if (conn == null) {
            startError = ex
            startedLatch.countDown()
        }
    }

    override fun onStart() {
        Log.d("BypassWS", "Server started")
        startedLatch.countDown()
    }

    companion object {
        private const val MAX_COOKIE_CHARS = 16_384

        /**
         * Bind by remote IP only (not port). The phone opens separate sockets for
         * `resolve:` and `done:`, so port-level binding would break the real flow.
         */
        private fun peerKey(conn: WebSocket): String {
            val addr = conn.remoteSocketAddress
            return when (addr) {
                is InetSocketAddress ->
                    addr.address?.hostAddress
                        ?: addr.hostString?.substringBefore('%')
                        ?: "unknown"
                else -> addr?.toString() ?: "unknown"
            }
        }
    }
}
