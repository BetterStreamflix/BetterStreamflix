package com.dskja.betterstreamflix.remotefind

import android.content.Context
import android.util.Log
import com.dskja.betterstreamflix.utils.BypassWebSocketEndpointHelper
import java.security.SecureRandom

/**
 * Starts / stops the TV-side LAN WebSocket + UDP discovery for Remote Find Phone.
 *
 * Cleartext `ws://` on the LAN is intentional (phone↔TV same Wi‑Fi trust boundary).
 * Mitigations: token is never sent in UDP offers; token rotates each [start] /
 * hostAndShowCode; phone must complete `hello:<code>` before receiving the token
 * in `welcome`. Optional short host TTL can be layered later via session age.
 */
object RemoteFindHost {
    private const val TAG = "RemoteFindHost"
    private const val PREFERRED_PORT = 47_822
    private val secureRandom = SecureRandom()

    @Volatile
    private var server: RemoteFindWebSocketServer? = null

    @Volatile
    private var responder: RemoteFindDiscovery.Responder? = null

    @Volatile
    private var appContext: Context? = null

    data class Hosted(
        val token: String,
        val shortCode: String,
        val wsUrl: String,
        val deepLink: String,
    )

    @Synchronized
    fun start(context: Context): Hosted? {
        appContext = context.applicationContext
        if (RemoteFindSession.isHost() && server != null) {
            val token = RemoteFindSession.token ?: return null
            val code = RemoteFindSession.shortCode ?: return null
            val ws = RemoteFindSession.wsUrl ?: return null
            return Hosted(
                token = token,
                shortCode = code,
                wsUrl = ws,
                deepLink = RemoteFindLinks.buildDeepLink(ws, code),
            )
        }
        // Rotate token + code on every explicit host/show-code cycle.
        stopInternal(clearSession = true)
        val token = newToken()
        val code = RemoteFindCode.newShortCode()
        val ports = listOf(PREFERRED_PORT, 0)
        var started: RemoteFindWebSocketServer? = null
        var port = PREFERRED_PORT
        for (candidate in ports) {
            val attempt = RemoteFindWebSocketServer(
                port = candidate,
                expectedToken = token,
                shortCode = code,
                onCommand = { command -> handleCommand(command) },
                onPhoneConnected = {
                    RemoteFindSession.phoneConnected = true
                    server?.sendToPhone(
                        RemoteFindProtocol.encodeStatus("connected"),
                    )
                },
                onPhoneDisconnected = {
                    RemoteFindSession.phoneConnected = false
                },
            )
            val ok = runCatching {
                attempt.start()
                check(attempt.awaitStart()) { "Timed out starting Remote Find server" }
                check(attempt.getStartError() == null) {
                    attempt.getStartError()?.message ?: "start failed"
                }
                true
            }.onFailure {
                Log.e(TAG, "port $candidate failed", it)
                runCatching { attempt.stop() }
            }.getOrDefault(false)
            if (ok) {
                started = attempt
                port = attempt.port
                break
            }
        }
        val active = started ?: return null
        val wsUrl = BypassWebSocketEndpointHelper.getAdvertisedWsUrl(port)
        if (wsUrl.isNullOrBlank()) {
            runCatching { active.stop() }
            return null
        }
        server = active
        RemoteFindSession.beginHost(token, code, wsUrl)
        responder = RemoteFindDiscovery.Responder(code, wsUrl).also { it.start() }
        return Hosted(
            token = token,
            shortCode = code,
            wsUrl = wsUrl,
            deepLink = RemoteFindLinks.buildDeepLink(wsUrl, code),
        )
    }

    /**
     * Recreate sockets after Activity recreate when [RemoteFindSession] still says hosting.
     */
    @Synchronized
    fun restoreIfNeeded(context: Context): Hosted? {
        appContext = context.applicationContext
        if (!RemoteFindSession.isHost()) return null
        if (server != null) {
            val token = RemoteFindSession.token ?: return null
            val code = RemoteFindSession.shortCode ?: return null
            val ws = RemoteFindSession.wsUrl ?: return null
            return Hosted(token, code, ws, RemoteFindLinks.buildDeepLink(ws, code))
        }
        val code = RemoteFindSession.shortCode ?: return null
        // Rotate token on restore for safety; keep the on-screen short code.
        val token = newToken()
        val ports = listOf(PREFERRED_PORT, 0)
        var started: RemoteFindWebSocketServer? = null
        var port = PREFERRED_PORT
        for (candidate in ports) {
            val attempt = RemoteFindWebSocketServer(
                port = candidate,
                expectedToken = token,
                shortCode = code,
                onCommand = { command -> handleCommand(command) },
                onPhoneConnected = {
                    RemoteFindSession.phoneConnected = true
                    server?.sendToPhone(RemoteFindProtocol.encodeStatus("connected"))
                },
                onPhoneDisconnected = { RemoteFindSession.phoneConnected = false },
            )
            val ok = runCatching {
                attempt.start()
                check(attempt.awaitStart())
                check(attempt.getStartError() == null)
                true
            }.onFailure {
                Log.e(TAG, "restore port $candidate failed", it)
                runCatching { attempt.stop() }
            }.getOrDefault(false)
            if (ok) {
                started = attempt
                port = attempt.port
                break
            }
        }
        val active = started ?: return null
        val wsUrl = BypassWebSocketEndpointHelper.getAdvertisedWsUrl(port) ?: run {
            runCatching { active.stop() }
            return null
        }
        server = active
        RemoteFindSession.beginHost(token, code, wsUrl)
        responder = RemoteFindDiscovery.Responder(code, wsUrl).also { it.start() }
        return Hosted(token, code, wsUrl, RemoteFindLinks.buildDeepLink(wsUrl, code))
    }

    @Synchronized
    fun stop() = stopInternal(clearSession = true)

    /** Tear down sockets without clearing session (Activity recreate). */
    @Synchronized
    fun pauseForRecreation() = stopInternal(clearSession = false)

    fun isRunning(): Boolean = server != null && RemoteFindSession.isHost()

    private fun stopInternal(clearSession: Boolean) {
        responder?.stop()
        responder = null
        runCatching { server?.stop(500) }
        server = null
        if (clearSession && RemoteFindSession.isHost()) {
            RemoteFindSession.clear()
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun handleCommand(command: RemoteFindProtocol.Inbound) {
        when (command) {
            is RemoteFindProtocol.Inbound.KeySet -> SearchInputBridge.setQuery(command.text)
            is RemoteFindProtocol.Inbound.KeyAppend -> SearchInputBridge.appendQuery(command.text)
            RemoteFindProtocol.Inbound.KeyBack -> SearchInputBridge.backspace()
            RemoteFindProtocol.Inbound.Submit -> SearchInputBridge.submit()
            is RemoteFindProtocol.Inbound.Pad -> RemoteFindPadBridge.onPad(command.dx, command.dy)
            RemoteFindProtocol.Inbound.Tap -> {
                SearchInputBridge.ensureSearchVisible()
                RemoteFindPadBridge.tap()
            }
            RemoteFindProtocol.Inbound.Back -> RemoteFindPadBridge.back()
            else -> Unit
        }
    }
}
