package com.betterstreamflix.app.support

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.NavHostFragment
import com.betterstreamflix.app.R
import com.betterstreamflix.app.fragments.player.PlayerMobileFragment
import com.betterstreamflix.app.fragments.player.PlayerTvFragment
import com.betterstreamflix.app.onboarding.OnboardingController
import com.betterstreamflix.app.onboarding.OnboardingPolicy
import com.betterstreamflix.app.ui.support.SupportStartupMobileDialog
import com.betterstreamflix.app.ui.support.SupportStartupTvDialog
import com.betterstreamflix.app.utils.getCurrentFragment

/**
 * Shows the premium startup support presentation once the main surface is ready.
 * Never blocks playback; respects [SupportPromptPolicy] cooldown + opt-out.
 *
 * Mobile: scheduled by [com.betterstreamflix.app.telegram.TelegramJoinGateController]
 * after the soft Telegram join gate is cleared. TV: still scheduled from MainTvActivity.
 */
object SupportStartupController {

    private const val SHOW_DELAY_MS = 1_600L

    fun schedule(activity: FragmentActivity, isTv: Boolean) {
        if (!SupportPromptPolicy.shouldShowStartup()) return
        if (activity.isFinishing) return
        if (OnboardingPolicy.shouldShow() || OnboardingController.isShowing()) return

        activity.window.decorView.postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@postDelayed
            if (OnboardingPolicy.shouldShow() || OnboardingController.isShowing()) return@postDelayed
            if (!SupportPromptPolicy.shouldShowStartup()) return@postDelayed
            if (isPlaybackActive(activity)) return@postDelayed

            show(activity, isTv)
        }, SHOW_DELAY_MS)
    }

    private fun show(activity: FragmentActivity, isTv: Boolean) {
        val openHub: () -> Unit = {
            runCatching {
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
                navHost?.navController?.navigate(R.id.support)
            }
            Unit
        }
        // Inflate can throw (missing theme attrs); never crash cold start for support UX.
        runCatching {
            val dialog = if (isTv) {
                SupportStartupTvDialog(activity, onOpenSupportHub = openHub)
            } else {
                SupportStartupMobileDialog(activity, onOpenSupportHub = openHub)
            }
            dialog.show()
            SupportPromptPolicy.recordShown()
        }
    }

    private fun isPlaybackActive(activity: FragmentActivity): Boolean {
        val current = activity.getCurrentFragment()
        return current is PlayerMobileFragment || current is PlayerTvFragment
    }
}
