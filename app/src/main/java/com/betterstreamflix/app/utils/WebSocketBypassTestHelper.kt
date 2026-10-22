package com.betterstreamflix.app.utils

import android.net.Uri
import android.util.Log
import com.betterstreamflix.app.BuildConfig
import java.util.UUID

object WebSocketBypassTestHelper {

    private const val TAG = "BypassWSTest"
    private const val DEFAULT_PORT = 8081

    private var server: BypassWebSocketServer? = null
    private var serverPort: Int = DEFAULT_PORT

    data class Session(
        val token: String,
        val wsUrl: String,
        val deepLink: String,
    )

    @Synchronized
    fun createSession(
        url: String,
        onDone: (String) -> Unit = {},
    ): Session? {
        // Hard-gate: never stand up a LAN bypass WS server in release (SS-B10-02).
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "createSession refused outside DEBUG builds")
            return null
        }
        val activeServer = ensureServer(onDone) ?: return null
        val wsUrl = BypassWebSocketEndpointHelper.getAdvertisedWsUrl(serverPort) ?: return null
        val token = UUID.randomUUID().toString()

        activeServer.registerSession(token, url)
        val deepLink = "betterstreamflix://resolve?ws=${Uri.encode(wsUrl)}&token=$token"

        return Session(
            token = token,
            wsUrl = wsUrl,
            deepLink = deepLink,
        )
    }

    @Synchronized
    private fun ensureServer(onDone: (String) -> Unit): BypassWebSocketServer? {
        if (!BuildConfig.DEBUG) return null
        server?.let { return it }

        return runCatching {
            // Test helper never applies cookies from done: — token ack only.
            BypassWebSocketServer(serverPort) { token, _ ->
                onDone(token)
            }.also {
                it.start()
                check(it.awaitStart()) { "Timed out while starting websocket server" }
                server = it
            }
        }.onFailure {
            Log.e(TAG, "Unable to start websocket server", it)
        }.getOrNull()
    }
}
