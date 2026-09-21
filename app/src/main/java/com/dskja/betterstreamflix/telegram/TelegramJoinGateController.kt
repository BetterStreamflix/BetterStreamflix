package com.dskja.betterstreamflix.telegram

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.dskja.betterstreamflix.BuildConfig
import com.dskja.betterstreamflix.support.SupportStartupController
import com.dskja.betterstreamflix.utils.UserPreferences
import java.lang.ref.WeakReference

/**
 * Mobile-only Telegram join gate.
 *
 * Primary UI: [TelegramJoinGateOverlay] — a hard View on [android.R.id.content].
 * Retries while locked and Main is started until the overlay is attached.
 * TV is intentionally excluded.
 */
object TelegramJoinGateController {

    private const val TAG = "TelegramJoinGate"
    private const val LAUNCH_STALE_MS = 90_000L
    private const val RETRY_INTERVAL_MS = 400L
    private const val MAX_RETRY_ATTEMPTS = 40

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var launching = false

    @Volatile
    private var gateUiAlive = false

    @Volatile
    private var launchStartedAtElapsed = 0L

    @Volatile
    private var retryAttempt = 0

    private var retryRunnable: Runnable? = null

    fun isBlocking(): Boolean {
        pruneStaleLaunchLock()
        // Only while the invite UI is actually on screen (dismissible, not a hard lock).
        return launching || gateUiAlive
    }

    /** Back / close while invite is visible — dismiss without forcing a join. */
    fun onBackPressed(activity: FragmentActivity): Boolean {
        if (!gateUiAlive && !TelegramJoinGateOverlay.isAttached(activity)) return false
        return TelegramJoinGateOverlay.dismissOptional(activity)
    }

    fun onColdStart(activity: FragmentActivity) {
        pruneStaleLaunchLock()
        Log.i(
            TAG,
            "coldStart layout=${BuildConfig.APP_LAYOUT} " +
                "shouldShow=${TelegramJoinGatePolicy.shouldShowGate()} " +
                "unlocked=${UserPreferences.telegramJoinGateUnlocked} " +
                "verPref=${UserPreferences.telegramJoinGateChannelVersion} " +
                "verPolicy=${TelegramJoinGatePolicy.CHANNEL_VERSION}",
        )
        if (!TelegramJoinGatePolicy.shouldShowGate()) {
            launching = false
            gateUiAlive = false
            cancelRetryLoop()
            SupportStartupController.schedule(activity, isTv = false)
            return
        }
        ensureGate(activity)
    }

    /** Immediate kick — call after setContentView once Main will stay on mobile. */
    fun kickEarly(activity: FragmentActivity) {
        if (!TelegramJoinGatePolicy.shouldShowGate()) return
        ensureGate(activity)
    }

    fun onActivityCreated(
        activity: FragmentActivity,
        @Suppress("UNUSED_PARAMETER") launcher: ActivityResultLauncher<Intent>,
    ) {
        if (!TelegramJoinGatePolicy.shouldShowGate()) return
        postEnsure(activity, delayMs = 16L)
        postEnsure(activity, delayMs = 200L)
        postEnsure(activity, delayMs = 800L)
    }

    fun onActivityResumed(
        activity: FragmentActivity,
        @Suppress("UNUSED_PARAMETER") launcher: ActivityResultLauncher<Intent>,
    ) {
        ensureGate(activity)
    }

    fun onActivityStarted(
        activity: FragmentActivity,
        @Suppress("UNUSED_PARAMETER") launcher: ActivityResultLauncher<Intent>,
    ) {
        ensureGate(activity)
    }

    fun onGateActivityStarted() {
        gateUiAlive = true
        launching = true
        launchStartedAtElapsed = SystemClock.elapsedRealtime()
        cancelRetryLoop()
        retryAttempt = 0
        Log.i(TAG, "gate UI started")
    }

    fun onGateActivityStopped() {
        Log.i(TAG, "gate UI stopped (may be under Telegram)")
    }

    fun onGateActivityDestroyed() {
        gateUiAlive = false
        Log.i(TAG, "gate UI destroyed")
    }

    fun onGateFinished(activity: FragmentActivity) {
        cancelRetryLoop()
        launching = false
        gateUiAlive = false
        launchStartedAtElapsed = 0L
        retryAttempt = 0
        if (TelegramJoinGatePolicy.isUnlocked()) {
            SupportStartupController.schedule(activity, isTv = false)
        } else if (TelegramJoinGatePolicy.shouldShowGate()) {
            Log.w(TAG, "gate finished still locked; will relaunch on resume")
        }
    }

    fun resetLaunchState() {
        cancelRetryLoop()
        launching = false
        gateUiAlive = false
        launchStartedAtElapsed = 0L
        retryAttempt = 0
    }

    fun cancelPendingOnly() {
        cancelRetryLoop()
    }

    private fun postEnsure(activity: FragmentActivity, delayMs: Long) {
        val activityRef = WeakReference(activity)
        mainHandler.postDelayed({
            val host = activityRef.get() ?: return@postDelayed
            if (host.isFinishing || host.isDestroyed) return@postDelayed
            ensureGate(host)
        }, delayMs)
    }

    private fun ensureGate(activity: FragmentActivity) {
        pruneStaleLaunchLock()
        if (!TelegramJoinGatePolicy.shouldShowGate()) {
            val wasBlocking = launching || gateUiAlive
            launching = false
            gateUiAlive = false
            cancelRetryLoop()
            TelegramJoinGateOverlay.dismiss(activity)
            if (wasBlocking) {
                SupportStartupController.schedule(activity, isTv = false)
            }
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return
        if (gateUiAlive || TelegramJoinGateOverlay.isAttached(activity)) {
            gateUiAlive = true
            cancelRetryLoop()
            return
        }
        showGateNow(activity)
        startRetryLoop(activity)
    }

    private fun showGateNow(activity: FragmentActivity) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) {
            postEnsure(activity, delayMs = 100L)
            return
        }
        launching = true
        launchStartedAtElapsed = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "showing gate overlay phase=${TelegramJoinGatePolicy.phase()} " +
                "attempt=$retryAttempt content=${activity.findViewById<android.view.ViewGroup>(android.R.id.content) != null}",
        )
        val ok = TelegramJoinGateOverlay.show(activity)
        if (!ok) {
            launching = false
            Log.w(TAG, "gate overlay show returned false — will retry")
        }
    }

    private fun startRetryLoop(activity: FragmentActivity) {
        if (retryRunnable != null) return
        retryAttempt = 0
        val activityRef = WeakReference(activity)
        val runnable = object : Runnable {
            override fun run() {
                retryRunnable = null
                val host = activityRef.get()
                if (host == null || host.isFinishing || host.isDestroyed) return
                if (!TelegramJoinGatePolicy.shouldShowGate()) return
                if (gateUiAlive || TelegramJoinGateOverlay.isAttached(host)) {
                    gateUiAlive = true
                    return
                }
                if (!host.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                    retryRunnable = this
                    mainHandler.postDelayed(this, RETRY_INTERVAL_MS)
                    return
                }
                if (retryAttempt >= MAX_RETRY_ATTEMPTS) {
                    Log.e(TAG, "gate retry exhausted after $MAX_RETRY_ATTEMPTS — forcing one more overlay show")
                    showGateNow(host)
                    // Keep trying slowly while still locked — never soft-fail forever.
                    retryAttempt = 0
                    retryRunnable = this
                    mainHandler.postDelayed(this, 2_000L)
                    return
                }
                retryAttempt++
                Log.w(TAG, "gate not alive — retry #$retryAttempt")
                showGateNow(host)
                retryRunnable = this
                mainHandler.postDelayed(this, RETRY_INTERVAL_MS)
            }
        }
        retryRunnable = runnable
        mainHandler.postDelayed(runnable, RETRY_INTERVAL_MS)
    }

    private fun cancelRetryLoop() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
    }

    private fun pruneStaleLaunchLock() {
        if (!launching && !gateUiAlive) return
        if (gateUiAlive) return
        val age = SystemClock.elapsedRealtime() - launchStartedAtElapsed
        if (launchStartedAtElapsed <= 0L || age >= LAUNCH_STALE_MS) {
            if (TelegramJoinGatePolicy.shouldShowGate()) {
                Log.w(TAG, "clearing stale gate launch lock age=${age}ms")
            }
            launching = false
            launchStartedAtElapsed = 0L
        }
    }
}
