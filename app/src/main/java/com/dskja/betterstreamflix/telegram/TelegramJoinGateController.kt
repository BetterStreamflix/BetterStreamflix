package com.dskja.betterstreamflix.telegram

import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.FragmentActivity
import com.dskja.betterstreamflix.support.SupportStartupController

/**
 * Mobile-only soft Telegram join gate.
 *
 * Launches [TelegramJoinGateActivity] before the soft Support startup prompt.
 * TV is intentionally excluded.
 */
object TelegramJoinGateController {

    private const val TAG = "TelegramJoinGate"

    @Volatile
    private var launching = false

    /** True while the gate Activity launch is in flight / on screen. */
    fun isBlocking(): Boolean = launching

    /** Call from MainMobileActivity.onCreate — only schedules Support when already unlocked. */
    fun onColdStart(activity: FragmentActivity) {
        if (!TelegramJoinGatePolicy.shouldShowGate()) {
            SupportStartupController.schedule(activity, isTv = false)
        }
    }

    /** Call from MainMobileActivity.onResume — launches gate if still locked. */
    fun onActivityResumed(
        activity: FragmentActivity,
        launcher: ActivityResultLauncher<Intent>,
    ) {
        if (!TelegramJoinGatePolicy.shouldShowGate()) {
            launching = false
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return
        if (launching) return
        launch(activity, launcher)
    }

    fun onGateFinished(activity: FragmentActivity) {
        launching = false
        if (TelegramJoinGatePolicy.isUnlocked()) {
            SupportStartupController.schedule(activity, isTv = false)
        } else if (TelegramJoinGatePolicy.shouldShowGate()) {
            // Result without unlock (edge) — next resume relaunches.
            Log.w(TAG, "gate finished still locked; will relaunch on resume")
        }
    }

    private fun launch(
        activity: FragmentActivity,
        launcher: ActivityResultLauncher<Intent>,
    ) {
        if (launching) return
        launching = true
        runCatching {
            Log.i(TAG, "launching TelegramJoinGateActivity")
            launcher.launch(Intent(activity, TelegramJoinGateActivity::class.java))
        }.onFailure { e ->
            launching = false
            Log.e(TAG, "failed to launch gate activity", e)
            SupportStartupController.schedule(activity, isTv = false)
        }
    }
}
