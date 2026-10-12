package com.dskja.betterstreamflix.profiles

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * While the app stays foreground, re-run guest expiry so the 2h TTL is enforced
 * without waiting for the next cold [android.app.Activity.onStart].
 */
object GuestForegroundSweep {

    private const val INTERVAL_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private var contextRef: WeakReference<Context>? = null

    private val tick = object : Runnable {
        override fun run() {
            val context = contextRef?.get()?.applicationContext ?: return
            runCatching { ProfileManager.sweepExpiredGuests(context) }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun onStart(context: Context) {
        contextRef = WeakReference(context.applicationContext)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun onStop() {
        handler.removeCallbacks(tick)
        contextRef = null
    }
}
