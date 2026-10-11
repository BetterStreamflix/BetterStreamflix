package com.dskja.betterstreamflix.remotefind

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dskja.betterstreamflix.utils.BypassWebSocketEndpointHelper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone-side OkHttp WebSocket client for Remote Find Phone.
 * Connects, sends `hello:<code>`, then stores the token from `welcome`.
 */
class RemoteFindClient(
    private val wsUrl: String,
    private val pairingCode: String,
    private val onStatus: (String) -> Unit = {},
    private val onConnected: () -> Unit = {},
    private val onDisconnected: (String?) -> Unit = {},
    private val onWelcomeToken: (String) -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val socketRef = AtomicReference<WebSocket?>(null)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect(): Boolean {
        if (!BypassWebSocketEndpointHelper.isAllowedPeerWs(wsUrl)) {
            Log.w(TAG, "Rejected disallowed ws peer: $wsUrl")
            mainHandler.post { onDisconnected("disallowed_peer") }
            return false
        }
        val request = Request.Builder().url(wsUrl).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(RemoteFindProtocol.encodeHello(pairingCode))
                mainHandler.post {
                    onConnected()
                    onStatus("connected")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when {
                    text.startsWith("welcome:") -> {
                        val welcome = RemoteFindProtocol.parseWelcome(text)
                        if (welcome != null) {
                            onWelcomeToken(welcome.token)
                        }
                        mainHandler.post { onStatus(text) }
                    }
                    text.startsWith("status:") ||
                        text.startsWith("ack:") ->
                        mainHandler.post { onStatus(text) }
                    text.startsWith("error:") ->
                        mainHandler.post { onStatus(text) }
                    text == "bye" -> {
                        webSocket.close(1000, "bye")
                        mainHandler.post { onDisconnected("bye") }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socketRef.compareAndSet(webSocket, null)
                mainHandler.post { onDisconnected(reason) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "socket failure", t)
                socketRef.compareAndSet(webSocket, null)
                mainHandler.post { onDisconnected(t.message) }
            }
        })
        socketRef.set(socket)
        return true
    }

    fun send(message: String): Boolean {
        val socket = socketRef.get() ?: return false
        return socket.send(message)
    }

    fun close() {
        socketRef.getAndSet(null)?.close(1000, "leave")
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val TAG = "RemoteFindClient"
    }
}
