package com.dskja.betterstreamflix.cast

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Smart-TV control hub — the open-protocol bridges every other vendor hides
 * behind proprietary SDKs:
 *
 *  • Samsung Tizen (OS 4.0+, 2018+): WSS remote-control channel on :8002.
 *    `ms.remote.control` key frames drive transport; pairing yields a token
 *    we persist so subsequent connects skip the on-screen prompt.
 *  • LG webOS: SSAP on :3000 — `register` with our manifest (PROMPT pairing),
 *    then `ssap://media.controls/…` + `system.launcher` for content handoff.
 *
 * Pairings (token / client-key) live in SharedPrefs — TVs accept remembered
 * clients without re-prompting.
 */
class SmartTvBridge private constructor(context: Context) {

    data class TvDevice(
        val id: String,          // "tizen:192.168.1.20" | "webos:192.168.1.21"
        val name: String,
        val host: String,
        val kind: Kind,
        var connected: Boolean = false,
    ) {
        enum class Kind { TIZEN, WEBOS }
    }

    private val prefs = context.applicationContext
        .getSharedPreferences("smart-tv-bridge", Context.MODE_PRIVATE)
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val pendingCommands = ConcurrentHashMap<String, Int>()

    private val _devices = MutableStateFlow<List<TvDevice>>(loadSaved())
    val devices: StateFlow<List<TvDevice>> = _devices

    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // Tizen's channel runs wss with a self-signed cert — we accept it, the
    // pairing token is the actual authorization gate.
    private val insecureClient: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), SecureRandom())
            }.socketFactory,
            trustAll,
        )
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES) // WS stays open for the session
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun loadSaved(): List<TvDevice> {
        val raw = prefs.getString("devices", "") ?: return emptyList()
        return raw.split("|").mapNotNull { entry ->
            val parts = entry.split(";")
            if (parts.size < 3) return@mapNotNull null
            TvDevice(
                id = parts[0],
                name = parts[1],
                host = parts[2],
                kind = if (parts[0].startsWith("tizen")) TvDevice.Kind.TIZEN else TvDevice.Kind.WEBOS,
            )
        }
    }

    private fun persist(list: List<TvDevice>) {
        prefs.edit().putString(
            "devices",
            list.joinToString("|") { "${it.id};${it.name};${it.host}" },
        ).apply()
    }

    private fun setStatus(id: String, s: String) {
        _status.value = _status.value + (id to s)
    }

    /** Add + connect a TV by IP. Pairing prompt shows on the TV the first time. */
    suspend fun addAndConnect(host: String, kind: TvDevice.Kind, name: String? = null): TvDevice? =
        withContext(Dispatchers.IO) {
            val prefix = if (kind == TvDevice.Kind.TIZEN) "tizen" else "webos"
            val device = TvDevice(
                id = "$prefix:$host",
                name = name ?: (if (kind == TvDevice.Kind.TIZEN) "Samsung TV" else "LG TV"),
                host = host,
                kind = kind,
            )
            val ok = connect(device)
            if (ok) {
                val list = (_devices.value.filter { it.id != device.id } + device)
                _devices.value = list
                persist(list)
            }
            if (ok) device else null
        }

    suspend fun removeDevice(id: String) = withContext(Dispatchers.IO) {
        sockets.remove(id)?.close(1000, "removed")
        val list = _devices.value.filter { it.id != id }
        _devices.value = list
        persist(list)
        prefs.edit().remove("token:$id").apply()
        _status.value = _status.value - id
    }

    suspend fun connect(d: TvDevice): Boolean = withContext(Dispatchers.IO) {
        if (sockets.containsKey(d.id)) return@withContext true
        setStatus(d.id, "connecting…")
        val url = when (d.kind) {
            TvDevice.Kind.TIZEN -> {
                val name = Base64.encodeToString("BetterStreamflix".toByteArray(), Base64.NO_WRAP)
                val token = prefs.getString("token:${d.id}", null)
                "wss://${d.host}:8002/api/v2/channels/samsung.remote.control" +
                    "?name=$name" + (token?.let { "&token=$it" } ?: "")
            }
            TvDevice.Kind.WEBOS -> "ws://${d.host}:3000/"
        }
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            val req = Request.Builder().url(url).build()
            insecureClient.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sockets[d.id] = webSocket
                    if (d.kind == TvDevice.Kind.WEBOS) sendWebOsRegister(d.id)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleFrame(d, text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    sockets.remove(d.id)
                    markOffline(d.id)
                    setStatus(d.id, "unreachable")
                    if (cont.isActive) cont.resume(false) {}
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    sockets.remove(d.id)
                    markOffline(d.id)
                }
            })
            // handshake window — 4s connect timeout + message pump
            scope.launch {
                var waited = 0
                while (waited < 5000) {
                    if (sockets.containsKey(d.id)) {
                        if (cont.isActive) cont.resume(true) {}
                        return@launch
                    }
                    delay(120); waited += 120
                }
                if (cont.isActive) cont.resume(false) {}
            }
        }
        if (ok) {
            markOnline(d.id)
            setStatus(d.id, if (d.kind == TvDevice.Kind.TIZEN) "paired" else "pairing — accept on TV")
        } else {
            setStatus(d.id, "unreachable")
        }
        ok
    }

    private fun markOnline(id: String) {
        _devices.value = _devices.value.map { if (it.id == id) it.copy(connected = true) else it }
    }

    private fun markOffline(id: String) {
        _devices.value = _devices.value.map { if (it.id == id) it.copy(connected = false) else it }
    }

    private fun handleFrame(d: TvDevice, text: String) {
        runCatching {
            val o = JSONObject(text)
            when (d.kind) {
                TvDevice.Kind.TIZEN -> {
                    // connect ack carries {"data":{"token":"..."}} — persist it.
                    o.optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() }?.let { t ->
                        prefs.edit().putString("token:${d.id}", t).apply()
                        setStatus(d.id, "paired ✓")
                    }
                }
                TvDevice.Kind.WEBOS -> {
                    if (o.optString("type") == "response" && o.optJSONObject("payload")
                            ?.optString("client-key")?.isNotBlank() == true
                    ) {
                        val k = o.getJSONObject("payload").getString("client-key")
                        prefs.edit().putString("token:${d.id}", k).apply()
                        setStatus(d.id, "paired ✓")
                    }
                    if (o.optString("type") == "error") {
                        setStatus(d.id, "rejected — allow the pairing prompt on the TV")
                    }
                }
            }
        }
    }

    // ── Tizen remote-control frames ──────────────────────────────────────────

    private fun tizenKey(key: String) = JSONObject(
        """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$key","Option":"false","TypeOfRemote":"SendRemoteKey"}}""",
    ).toString()

    // ── webOS SSAP frames ────────────────────────────────────────────────────

    private fun sendWebOsRegister(id: String) {
        val key = prefs.getString("token:$id", null)
        // SSAP register — client-key belongs in payload (remembered pairing),
        // the manifest declares the permissions we need.
        val payload = JSONObject(
            """{"id":"register_0","type":"register","payload":{"forcePairing":${key == null},"pairingType":"PROMPT","manifest":{"manifestVersion":1,"appVersion":"1.0","signed":{"created":"2026","appId":"com.dskja.betterstreamflix","vendorId":"com.dskja.betterstreamflix","localizedAppNames":{"":"BetterStreamflix"}},"permissions":["LAUNCH","CONTROL_PLAYBACK","CONTROL_AUDIO","CONTROL_INPUT_MEDIA_TEXT","APP_TO_APP","READ_NOTIFICATIONS"]}}}""",
        )
        if (key != null) payload.getJSONObject("payload").put("client-key", key)
        sockets[id]?.send(payload.toString())
    }

    private fun ssap(uri: String, params: JSONObject? = null): String {
        val id = pendingCommands.values.maxOrNull()?.plus(1) ?: 1
        val o = JSONObject()
        o.put("id", "cmd_$id")
        o.put("type", "request")
        o.put("uri", uri)
        if (params != null) o.put("payload", params)
        return o.toString()
    }

    // ── Public remote API ────────────────────────────────────────────────────

    private fun send(d: TvDevice, frame: String): Boolean {
        val ws = sockets[d.id] ?: run {
            Log.w("SmartTV", "send dropped — ${d.id} not connected")
            return false
        }
        return ws.send(frame)
    }

    fun key(id: String, keyName: String) {
        val d = _devices.value.firstOrNull { it.id == id } ?: return
        when (d.kind) {
            TvDevice.Kind.TIZEN -> send(d, tizenKey("KEY_$keyName"))
            TvDevice.Kind.WEBOS -> when (keyName) {
                "PLAY" -> send(d, ssap("ssap://media.controls/play"))
                "PAUSE" -> send(d, ssap("ssap://media.controls/pause"))
                "STOP" -> send(d, ssap("ssap://media.controls/stop"))
                "REWIND" -> send(d, ssap("ssap://media.controls/rewind"))
                "FASTFORWARD" -> send(d, ssap("ssap://media.controls/fastForward"))
                else -> Log.w("SmartTV", "webOS key unmapped: $keyName")
            }
        }
    }

    fun volume(id: String, up: Boolean) {
        val d = _devices.value.firstOrNull { it.id == id } ?: return
        when (d.kind) {
            TvDevice.Kind.TIZEN -> send(d, tizenKey(if (up) "KEY_VOLUP" else "KEY_VOLDOWN"))
            TvDevice.Kind.WEBOS -> send(d, ssap("ssap://audio.volume/${if (up) "volumeUp" else "volumeDown"}"))
        }
    }

    /**
     * Content handoff — asks the TV to open/play a URL.
     * Tizen: launches the browser app with the link. webOS: ssap launcher.
     * Best used with a proxied progressive URL — TVs can't navigate our
     * header-gated streams on their own.
     */
    fun openUrl(id: String, url: String) {
        val d = _devices.value.firstOrNull { it.id == id } ?: return
        when (d.kind) {
            TvDevice.Kind.TIZEN -> send(
                d,
                JSONObject(
                    """{"method":"ms.channel.emit","params":{"event":"ed.apps.launch","to":"host","data":{"appId":"org.tizen.browser","action_type":"NATIVE_LAUNCH","metaTag":${JSONObject.quote(url)}}}}""",
                ).toString(),
            )
            TvDevice.Kind.WEBOS -> send(
                d,
                ssap("ssap://system.launcher/launch", JSONObject().put("id", "com.webos.app.browser").put("params", JSONObject().put("target", url))),
            )
        }
    }

    fun disconnect(id: String) {
        sockets.remove(id)?.close(1000, "bye")
        markOffline(id)
    }

    fun disconnectAll() {
        sockets.values.forEach { it.close(1000, "bye") }
        sockets.clear()
        _devices.value = _devices.value.map { it.copy(connected = false) }
    }

    companion object {
        @Volatile
        private var instance: SmartTvBridge? = null

        fun get(context: Context): SmartTvBridge = instance ?: synchronized(this) {
            instance ?: SmartTvBridge(context.applicationContext).also { instance = it }
        }
    }
}
