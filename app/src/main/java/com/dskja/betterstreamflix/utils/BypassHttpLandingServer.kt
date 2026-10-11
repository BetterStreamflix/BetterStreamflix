package com.dskja.betterstreamflix.utils

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * Tiny HTTP landing page for TV SerienStream captcha bypass.
 *
 * Phone cameras often cannot open `streamflix://` deep links (they search Google instead).
 * Encoding an `http://TV-IP:port/resolve?...` URL in the QR lets the camera open a page that
 * bridges into the BetterStreamflix app and explains the in-app scanner fallback.
 *
 * Security (SEC-DL-02 / DL-08): `/` never exposes the session token; `/resolve` requires the
 * active session token (and optional path secret). HTML/JS escaping neutralizes `</script>`.
 */
class BypassHttpLandingServer(
    port: Int,
) : NanoHTTPD(port) {

    data class Session(val deepLink: String, val ws: String, val token: String)

    private val session = AtomicReference<Session?>(null)
    private val pathSecret: String = generateSecret()

    /** Opaque path segment required on `/r/<secret>/resolve` (optional alternate). */
    fun landingPathSecret(): String = pathSecret

    fun setDeepLink(deepLink: String) {
        val parsed = com.dskja.betterstreamflix.providers.SerienStreamResolveLink.parse(deepLink)
        if (parsed == null) {
            Log.w(TAG, "Ignoring invalid deep link for landing server")
            session.set(null)
            return
        }
        session.set(Session(deepLink = deepLink, ws = parsed.ws, token = parsed.token))
    }

    override fun serve(sessionHttp: IHTTPSession): Response {
        return try {
            val uri = sessionHttp.uri.orEmpty()
            val active = session.get()
            when {
                uri == "/resolve" || uri.startsWith("/resolve") ||
                    uri == "/r/$pathSecret/resolve" || uri.startsWith("/r/$pathSecret/resolve") -> {
                    if (active == null) {
                        return newFixedLengthResponse(
                            Response.Status.NOT_FOUND,
                            MIME_PLAINTEXT,
                            "no active session",
                        )
                    }
                    val params = sessionHttp.parms
                    val presentedToken = params["token"].orEmpty()
                    // Require the active session token — blocks LAN token fishing on `/`
                    // and anonymous `/resolve` probes (SEC-DL-02).
                    if (presentedToken.isBlank() || presentedToken != active.token) {
                        return newFixedLengthResponse(
                            Response.Status.UNAUTHORIZED,
                            MIME_PLAINTEXT,
                            "unauthorized",
                        )
                    }
                    htmlResponse(landingHtml(active.deepLink))
                }
                uri == "/" || uri.isBlank() -> {
                    // Never leak the deep link / token on `/` (SEC-DL-02).
                    htmlResponse(idleHtml())
                }
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "not found",
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serve bypass landing page", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Bypass landing page error",
            )
        }
    }

    private fun htmlResponse(body: String): Response {
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
    }

    private fun idleHtml(): String {
        val accent = ExperimentalMobileDesign.accentCssHex()
        return """
            <!DOCTYPE html>
            <html lang="de">
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1"/>
              <title>BetterStreamflix TV Bypass</title>
              <style>
                body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;margin:0;padding:24px;background:#111;color:#f5f5f5;line-height:1.45}
                .card{max-width:520px;margin:0 auto;background:#1c1c1c;border-radius:16px;padding:20px;border:1px solid #2a2a2a}
                h1{font-size:1.35rem;margin:0 0 12px}
                p{margin:0 0 12px;color:#ddd}
                .muted{color:#9a9a9a;font-size:.92rem}
              </style>
            </head>
            <body>
              <div class="card">
                <h1>BetterStreamflix TV-Captcha</h1>
                <p>Scanne den QR-Code auf dem TV, oder öffne in der App: Einstellungen → TV-Bypass-QR scannen.</p>
                <p class="muted">EN: Scan the QR on your TV, or use Settings → Scan TV bypass QR in the app.</p>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun landingHtml(deepLink: String): String {
        val safeDeepLink = escapeHtmlAttr(deepLink)
        val jsDeepLink = escapeJsString(deepLink)
        val accent = ExperimentalMobileDesign.accentCssHex()
        return """
            <!DOCTYPE html>
            <html lang="de">
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1"/>
              <meta http-equiv="refresh" content="0;url=$safeDeepLink"/>
              <title>BetterStreamflix TV Bypass</title>
              <style>
                body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;margin:0;padding:24px;background:#111;color:#f5f5f5;line-height:1.45}
                .card{max-width:520px;margin:0 auto;background:#1c1c1c;border-radius:16px;padding:20px;border:1px solid #2a2a2a}
                h1{font-size:1.35rem;margin:0 0 12px}
                p{margin:0 0 12px;color:#ddd}
                ol{margin:0 0 16px 18px;padding:0}
                li{margin:0 0 8px}
                a.button{display:block;text-align:center;background:$accent;color:#fff;text-decoration:none;padding:14px 16px;border-radius:10px;font-weight:700}
                .muted{color:#9a9a9a;font-size:.92rem}
              </style>
            </head>
            <body>
              <div class="card">
                <h1>BetterStreamflix TV-Captcha</h1>
                <p><strong>DE:</strong> Dies ist kein SerienStream-Link. Öffne die Captcha-Seite in der <strong>BetterStreamflix-App</strong>.</p>
                <ol>
                  <li>Tippe unten auf „In BetterStreamflix öffnen“.</li>
                  <li>Oder in der App: Einstellungen → <strong>TV-Bypass-QR scannen</strong>.</li>
                  <li>Captcha lösen → <strong>Weiter</strong>. Der QR auf dem TV schließt sich automatisch.</li>
                </ol>
                <p class="muted"><strong>EN:</strong> Do not use a normal camera search. Open BetterStreamflix on this phone, then use Settings → Scan TV bypass QR if the button below does nothing.</p>
                <p><a class="button" href="$safeDeepLink">In BetterStreamflix öffnen / Open in BetterStreamflix</a></p>
              </div>
              <script>
                try { window.location.href = "$jsDeepLink"; } catch (e) {}
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    companion object {
        private const val TAG = "BypassHttpLanding"

        private fun generateSecret(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun escapeHtmlAttr(value: String): String {
            return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&#39;")
        }

        private fun escapeJsString(value: String): String {
            // Neutralize </script> and other breakouts (SEC-DL-08).
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("&", "\\u0026")
                .replace("/", "\\/")
        }

        @Suppress("unused")
        fun buildDeepLink(ws: String, token: String): String {
            val encodedWs = URLEncoder.encode(ws, StandardCharsets.UTF_8.name())
            val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8.name())
            return "betterstreamflix://resolve?ws=$encodedWs&token=$encodedToken"
        }
    }
}
