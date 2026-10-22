package com.betterstreamflix.app.cast

import android.util.Log

/**
 * Process-scoped DLNA session — survives the cast sheet closing so the TV
 * keeps playing while the user browses. Mirrors [CastPlaybackHub] semantics:
 * the local player pauses when a DLNA session starts and resumes at the TV's
 * last reported position when it stops.
 */
object DlnaCastSession {

    private const val TAG = "DlnaCastSession"

    @Volatile
    var activeRenderer: DlnaRenderer? = null
        private set

    val isActive: Boolean get() = activeRenderer != null

    /** Stream the URL on the renderer. The URL must already be LAN-reachable. */
    suspend fun start(renderer: DlnaRenderer, url: String, title: String, mime: String): Boolean {
        val ok = DlnaController.setUri(renderer, url, title, mime) && DlnaController.play(renderer)
        if (ok) {
            activeRenderer = renderer
            Log.i(TAG, "DLNA session started on ${renderer.name}")
        } else {
            Log.w(TAG, "DLNA SetAVTransportURI/Play failed on ${renderer.name}")
        }
        return ok
    }

    /** Stop the TV session — returns the TV's last position in ms (for local resume). */
    suspend fun stop(): Long? {
        val target = activeRenderer ?: return null
        activeRenderer = null
        val seconds = runCatching { DlnaController.positionSeconds(target) }.getOrNull()
        runCatching { DlnaController.stop(target) }
        Log.i(TAG, "DLNA session stopped at ${seconds ?: -1}s")
        return seconds?.times(1000)
    }
}
