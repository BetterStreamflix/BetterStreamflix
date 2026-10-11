package com.dskja.betterstreamflix.remotefind

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import java.lang.ref.WeakReference

/**
 * Converts phone touchpad deltas into DPAD focus moves on the TV activity.
 */
object RemoteFindPadBridge {
    private const val THRESHOLD = 28f

    @Volatile
    private var activityRef: WeakReference<Activity>? = null
    private var accX = 0f
    private var accY = 0f

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

    fun tap() = inject(KeyEvent.KEYCODE_DPAD_CENTER)

    fun back() = inject(KeyEvent.KEYCODE_BACK)

    private fun inject(keyCode: Int) {
        val activity = activityRef?.get() ?: return
        val now = SystemClock.uptimeMillis()
        activity.runOnUiThread {
            activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            activity.dispatchKeyEvent(KeyEvent(now, now + 16, KeyEvent.ACTION_UP, keyCode, 0))
        }
    }
}
