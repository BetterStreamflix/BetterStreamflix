package com.dskja.betterstreamflix.sync

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thin glue between player fragments and Cast Hand-off offer/claim.
 * TV offers a code from chrome; Phone seeks [CastHandoffSession.pendingPositionMs]
 * once after [displayVideo].
 */
object CastHandoffPlayerHooks {

    interface PlayerBridge {
        fun isLiveOrOffline(): Boolean
        fun videoType(): Video.Type
        fun currentServer(): Video.Server?
        fun positionMs(): Long
        fun seekTo(positionMs: Long)
    }

    @Volatile
    private var offerRefreshJob: Job? = null

    fun wireChromeButton(
        fragment: Fragment,
        button: android.view.View?,
        bridge: PlayerBridge,
    ) {
        if (button == null) return
        button.setOnClickListener {
            CastHandoffUi.showOfferMenu(
                fragment = fragment,
                videoType = bridge.videoType(),
                server = bridge.currentServer(),
                positionMs = bridge.positionMs(),
                isLiveOrOffline = bridge.isLiveOrOffline(),
                onOfferActive = { startOfferRefresh(fragment, bridge) },
                onOfferEnded = { stopOfferRefresh() },
            )
        }
        refreshChrome(button, bridge)
    }

    fun refreshChrome(button: android.view.View?, bridge: PlayerBridge) {
        if (button == null) return
        val disabled = bridge.isLiveOrOffline()
        button.isEnabled = !disabled
        button.alpha = if (disabled) 0.4f else 1f
        button.contentDescription = when {
            CastHandoffSession.isOffer() ->
                button.context.getString(R.string.cast_handoff_cd_offering)
            else ->
                button.context.getString(R.string.cast_handoff_cd)
        }
    }

    /**
     * Apply one-shot hand-off seek after the player is ready.
     * Returns true when a hand-off position was (or will be) applied — callers
     * should skip Continue Watching resume in that case.
     */
    fun applyPendingSeek(fragment: Fragment, bridge: PlayerBridge): Boolean {
        if (!CastHandoffSession.hasPendingPosition()) return false
        val target = CastHandoffSession.consumePendingPositionMs() ?: return false
        fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            if (!fragment.isAdded) return@launch
            // Wait briefly for duration / buffered windows so seek sticks.
            delay(350)
            if (!fragment.isAdded) return@launch
            bridge.seekTo(target.coerceAtLeast(0L))
            CastHandoffSession.clear()
        }
        return true
    }

    fun onDestroyView(fragment: Fragment) {
        stopOfferRefresh()
        if (!CastHandoffSession.isOffer()) return
        val handoffId = CastHandoffSession.handoffId
        val ctx = fragment.context?.applicationContext
        fragment.lifecycleScope.launch {
            if (ctx != null && handoffId != null) {
                withContext(Dispatchers.IO) {
                    runCatching { CastHandoffRepository.revoke(ctx, handoffId) }
                }
            }
            CastHandoffSession.clear()
        }
    }

    private fun startOfferRefresh(fragment: Fragment, bridge: PlayerBridge) {
        stopOfferRefresh()
        val handoffId = CastHandoffSession.handoffId ?: return
        val ctx = fragment.requireContext().applicationContext
        offerRefreshJob = fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && CastHandoffSession.isOffer()) {
                runCatching {
                    CastHandoffRepository.refreshPosition(
                        context = ctx,
                        handoffId = handoffId,
                        server = bridge.currentServer(),
                        positionMs = bridge.positionMs(),
                    )
                }
                delay(2_500L)
            }
        }
    }

    private fun stopOfferRefresh() {
        offerRefreshJob?.cancel()
        offerRefreshJob = null
    }
}
