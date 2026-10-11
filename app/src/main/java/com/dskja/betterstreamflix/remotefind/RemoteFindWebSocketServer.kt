package com.dskja.betterstreamflix.remotefind

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * LAN WebSocket host for Remote Find Phone. One authenticated phone controller at a time.
 * Hello must arrive within [HELLO_TIMEOUT_MS] or the socket is closed.
 * Ping is ignored until the peer is authed. Bad pairing attempts are rate-limited.
 */
class RemoteFindWebSocketServer(
    port: Int,
    private val expectedToken: String,
    private val shortCode: String,
    private val onCommand: (RemoteFindProtocol.Inbound) -> Unit,
    private val onPhoneConnected: () -> Unit = {},
    private val onPhoneDisconnected: () -> Unit = {},
) : WebSocketServer(InetSocketAddress(port)) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val startedLatch = CountDownLatch(1)
    private val controller = AtomicReference<WebSocket?>(null)
    private var startError: Exception? = null
    private val badTokenCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val helloWatchdogs = ConcurrentHashMap<WebSocket, Runnable>()

    fun getStartError(): Exception? = startError

    fun awaitStart(timeoutMs: Long = 5_000): Boolean =
        startedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun sendToPhone(message: String) {
        controller.get()?.takeIf { it.isOpen }?.send(message)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d(TAG, "Client connected: ${conn.remoteSocketAddress}")
        val watchdog = Runnable {
            if (controller.get() !== conn && conn.isOpen) {
                conn.send(RemoteFindProtocol.encodeError("hello_timeout"))
                conn.close(4003, "hello_timeout")
            }
        }
        helloWatchdogs[conn] = watchdog
        mainHandler.postDelayed(watchdog, HELLO_TIMEOUT_MS)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        when (val inbound = RemoteFindProtocol.parseInbound(message)) {
            is RemoteFindProtocol.Inbound.Hello -> {
                val peerKey = conn.remoteSocketAddress?.address?.hostAddress.orEmpty()
                val presented = inbound.code.trim()
                val codeOk = RemoteFindCode.normalize(presented) ==
                    RemoteFindCode.normalize(shortCode)
                // Accept legacy hello:<token> during migration window.
                val tokenOk = presented == expectedToken
                if (!codeOk && !tokenOk) {
                    val count = badTokenCounts
                        .getOrPut(peerKey.ifBlank { "unknown" }) { AtomicInteger(0) }
                        .incrementAndGet()
                    conn.send(RemoteFindProtocol.encodeError("bad_token"))
                    if (count >= MAX_BAD_TOKEN) {
                        conn.close(4001, "bad_token")
                    }
                    return
                }
                cancelHelloWatchdog(conn)
                badTokenCounts.remove(peerKey)
                val previous = controller.getAndSet(conn)
                if (previous != null && previous !== conn && previous.isOpen) {
                    previous.send(RemoteFindProtocol.encodeBye())
                    previous.close(4002, "replaced")
                }
                conn.send(RemoteFindProtocol.encodeAckHello())
                conn.send(RemoteFindProtocol.encodeWelcome(shortCode, expectedToken))
                mainHandler.post { onPhoneConnected() }
            }
            is RemoteFindProtocol.Inbound.Ping -> {
                if (controller.get() === conn) {
                    conn.send(RemoteFindProtocol.encodePong())
                }
                // Unauthenticated ping: ignore (no fingerprint / no pong).
            }
            is RemoteFindProtocol.Inbound.Unknown -> {
                if (controller.get() === conn) {
                    conn.send(RemoteFindProtocol.encodeError("unknown"))
                }
            }
            else -> {
                if (controller.get() !== conn) {
                    conn.send(RemoteFindProtocol.encodeError("not_authed"))
                    return
                }
                mainHandler.post { onCommand(inbound) }
            }
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        cancelHelloWatchdog(conn)
        if (controller.compareAndSet(conn, null)) {
            mainHandler.post { onPhoneDisconnected() }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "Error", ex)
        if (conn == null) {
            startError = ex
            startedLatch.countDown()
        }
    }

    override fun onStart() {
        Log.d(TAG, "Server started")
        startedLatch.countDown()
    }

    private fun cancelHelloWatchdog(conn: WebSocket) {
        helloWatchdogs.remove(conn)?.let { mainHandler.removeCallbacks(it) }
    }

    companion object {
        private const val TAG = "RemoteFindWS"
        private const val HELLO_TIMEOUT_MS = 5_000L
        private const val MAX_BAD_TOKEN = 5
    }
}
