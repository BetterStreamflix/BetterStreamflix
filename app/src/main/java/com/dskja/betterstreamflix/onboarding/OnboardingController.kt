package com.dskja.betterstreamflix.onboarding

import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.dskja.betterstreamflix.ui.onboarding.OnboardingMobileDialog
import com.dskja.betterstreamflix.ui.onboarding.OnboardingTvDialog
import java.lang.ref.WeakReference

/**
 * Shows first-launch onboarding on Mobile + TV. Blocks Telegram soft-invite until done.
 */
object OnboardingController {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var showing = false

    fun isShowing(): Boolean = showing

    /**
     * @return true when the wizard should own cold start — callers defer
     * Telegram / support soft prompts.
     */
    fun maybeShow(activity: FragmentActivity, isTv: Boolean): Boolean {
        if (!OnboardingPolicy.shouldShow()) {
            showing = false
            return false
        }
        if (activity.isFinishing || activity.isDestroyed) return false
        if (showing) return true

        showing = true
        val activityRef = WeakReference(activity)
        fun attempt(attempt: Int) {
            val host = activityRef.get()
            if (host == null || host.isFinishing || host.isDestroyed) {
                showing = false
                return
            }
            if (!OnboardingPolicy.shouldShow()) {
                showing = false
                return
            }
            if (!host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (attempt < 12) {
                    mainHandler.postDelayed({ attempt(attempt + 1) }, 50L)
                } else {
                    showing = false
                }
                return
            }
            showNow(host, isTv)
        }
        mainHandler.post { attempt(0) }
        return true
    }

    private fun showNow(activity: FragmentActivity, isTv: Boolean) {
        if (!OnboardingPolicy.shouldShow()) {
            showing = false
            return
        }
        runCatching {
            val dialog = if (isTv) {
                OnboardingTvDialog(activity) { showing = false }
            } else {
                OnboardingMobileDialog(activity) { showing = false }
            }
            dialog.setOnDismissListener { showing = false }
            dialog.show()
        }.onFailure {
            showing = false
            // Never block cold start if inflate fails — mark done so we don't loop.
            OnboardingPolicy.markComplete()
        }
    }
}
