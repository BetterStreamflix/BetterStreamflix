package com.betterstreamflix.app.remotefind

/**
 * Wire protocol for Remote Find Phone (LAN WebSocket text frames).
 *
 * Pairing model (LAN-trust): UDP offers never carry the long-lived session token.
 * Phone connects and sends `hello:<code>` first; TV returns the token in `welcome`.
 * Token is rotated each [RemoteFindHost.start] / Show Code cycle.
 *
 * Phone → TV:
 * - `hello:<code>` pairing challenge (short code, not the WS token)
 * - `keyset:<text>` replace search query
 * - `key:<text>` append to search query
 * - `keyback` backspace
 * - `submit` run search
 * - `pad:<dx>,<dy>` touchpad delta (normalized -1..1 or px)
 * - `tap` center / select
 * - `back` back / cancel
 * - `ping`
 *
 * TV → Phone:
 * - `welcome:<code>:<token>`
 * - `ack:hello`
 * - `status:<text>`
 * - `error:<reason>`
 * - `pong`
 * - `bye`
 */
object RemoteFindProtocol {
    private const val MAX_TEXT_CHARS = 200

    sealed class Inbound {
        data class Hello(val code: String) : Inbound()
        data class KeySet(val text: String) : Inbound()
        data class KeyAppend(val text: String) : Inbound()
        data object KeyBack : Inbound()
        data object Submit : Inbound()
        data class Pad(val dx: Float, val dy: Float) : Inbound()
        data object Tap : Inbound()
        data object Back : Inbound()
        data object Ping : Inbound()
        data class Unknown(val raw: String) : Inbound()
    }

    data class Welcome(val code: String, val token: String)

    fun encodeHello(code: String): String = "hello:${code.trim()}"
    fun encodeKeySet(text: String): String = "keyset:${sanitizeText(text)}"
    fun encodeKeyAppend(text: String): String = "key:${sanitizeText(text)}"
    fun encodeKeyBack(): String = "keyback"
    fun encodeSubmit(): String = "submit"
    fun encodePad(dx: Float, dy: Float): String =
        "pad:${"%.3f".format(java.util.Locale.US, dx)},${"%.3f".format(java.util.Locale.US, dy)}"
    fun encodeTap(): String = "tap"
    fun encodeBack(): String = "back"
    fun encodePing(): String = "ping"

    fun encodeWelcome(code: String, token: String): String =
        "welcome:${code.trim()}:${token.trim()}"
    fun encodeAckHello(): String = "ack:hello"
    fun encodeStatus(text: String): String = "status:${sanitizeText(text)}"
    fun encodeError(reason: String): String = "error:${sanitizeText(reason)}"
    fun encodePong(): String = "pong"
    fun encodeBye(): String = "bye"

    fun parseInbound(raw: String?): Inbound {
        if (raw.isNullOrBlank()) return Inbound.Unknown("")
        val message = raw.trim()
        return when {
            message.startsWith("hello:") ->
                Inbound.Hello(message.substringAfter("hello:").trim())
            message.startsWith("keyset:") ->
                Inbound.KeySet(sanitizeText(message.substringAfter("keyset:")))
            message.startsWith("key:") ->
                Inbound.KeyAppend(sanitizeText(message.substringAfter("key:")))
            message.equals("keyback", ignoreCase = true) -> Inbound.KeyBack
            message.equals("submit", ignoreCase = true) -> Inbound.Submit
            message.startsWith("pad:") -> parsePad(message.substringAfter("pad:"))
            message.equals("tap", ignoreCase = true) -> Inbound.Tap
            message.equals("back", ignoreCase = true) -> Inbound.Back
            message.equals("ping", ignoreCase = true) -> Inbound.Ping
            else -> Inbound.Unknown(message)
        }
    }

    fun parseWelcome(raw: String?): Welcome? {
        if (raw.isNullOrBlank() || !raw.startsWith("welcome:")) return null
        val body = raw.removePrefix("welcome:")
        val sep = body.indexOf(':')
        if (sep <= 0) return null
        val code = body.substring(0, sep).trim()
        val token = body.substring(sep + 1).trim()
        if (code.isBlank() || token.isBlank()) return null
        return Welcome(code = code, token = token)
    }

    private fun parsePad(body: String): Inbound {
        val parts = body.split(',', limit = 2)
        if (parts.size != 2) return Inbound.Unknown("pad:$body")
        val dx = parts[0].trim().toFloatOrNull() ?: return Inbound.Unknown("pad:$body")
        val dy = parts[1].trim().toFloatOrNull() ?: return Inbound.Unknown("pad:$body")
        return Inbound.Pad(dx.coerceIn(-50f, 50f), dy.coerceIn(-50f, 50f))
    }

    fun sanitizeText(raw: String): String {
        return raw
            .replace('\u0000', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(MAX_TEXT_CHARS)
    }
}
