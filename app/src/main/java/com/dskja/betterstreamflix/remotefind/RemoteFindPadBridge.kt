package com.dskja.betterstreamflix.remotefind

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import java.lang.ref.WeakReference

/**
 * Converts phone touchpad deltas into DPAD focus moves on the TV activity.
 * Injects are throttled to avoid main-thread DPAD storms.
 */
object RemoteFindPadBridge {
    private const val THRESHOLD = 28f
    private const val MIN_INJECT_INTERVAL_MS = 40L

    @Volatile
    private var activityRef: WeakReference<Activity>? = null
    private var accX = 0f
    private var accY = 0f
    private var lastInjectAt = 0L

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
            accX = 0f
            accY = 0f
        }
    }

    fun onPad(dx: Float, dy: Float) {
        accX += dx
        accY += dy
        while (accX >= THRESHOLD) {
            accX -= THRESHOLD
            inject(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        while (accX <= -THRESHOLD) {
            accX += THRESHOLD
            inject(KeyEvent.KEYCODE_DPAD_LEFT)
        }
        while (accY >= THRESHOLD) {
            accY -= THRESHOLD
            inject(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        while (accY <= -THRESHOLD) {
            accY += THRESHOLD
            inject(KeyEvent.KEYCODE_DPAD_UP)
        }
    }

    fun tap() = inject(KeyEvent.KEYCODE_DPAD_CENTER, force = true)

    fun back() = inject(KeyEvent.KEYCODE_BACK, force = true)

    private fun inject(keyCode: Int, force: Boolean = false) {
        val activity = activityRef?.get() ?: return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastInjectAt < MIN_INJECT_INTERVAL_MS) {
            return
        }
        lastInjectAt = now
        activity.runOnUiThread {
            activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            activity.dispatchKeyEvent(KeyEvent(now, now + 16, KeyEvent.ACTION_UP, keyCode, 0))
        }
    }
}
