package com.dskja.betterstreamflix.remotefind

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * LAN WebSocket host for Remote Find Phone. One authenticated phone controller at a time.
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

    fun getStartError(): Exception? = startError

    fun awaitStart(timeoutMs: Long = 5_000): Boolean =
        startedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun sendToPhone(message: String) {
        controller.get()?.takeIf { it.isOpen }?.send(message)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d(TAG, "Client connected: ${conn.remoteSocketAddress}")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        when (val inbound = RemoteFindProtocol.parseInbound(message)) {
            is RemoteFindProtocol.Inbound.Hello -> {
                if (inbound.token != expectedToken) {
                    conn.send(RemoteFindProtocol.encodeError("bad_token"))
                    conn.close(4001, "bad_token")
                    return
                }
                val previous = controller.getAndSet(conn)
                if (previous != null && previous !== conn && previous.isOpen) {
                    previous.send(RemoteFindProtocol.encodeBye())
                    previous.close(4002, "replaced")
                }
                conn.send(RemoteFindProtocol.encodeAckHello())
                conn.send(RemoteFindProtocol.encodeWelcome(shortCode))
                mainHandler.post { onPhoneConnected() }
            }
            is RemoteFindProtocol.Inbound.Ping -> conn.send(RemoteFindProtocol.encodePong())
            is RemoteFindProtocol.Inbound.Unknown ->
                conn.send(RemoteFindProtocol.encodeError("unknown"))
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

    companion object {
        private const val TAG = "RemoteFindWS"
    }
}
