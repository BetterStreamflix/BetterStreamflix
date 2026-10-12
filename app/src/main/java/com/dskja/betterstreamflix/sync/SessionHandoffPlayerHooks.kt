package com.dskja.betterstreamflix.sync

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Player chrome glue for Phone→TV Session Handoff.
 */
object SessionHandoffPlayerHooks {

    interface PlayerBridge {
        fun isLiveOrOffline(): Boolean
        fun videoType(): Video.Type
        fun currentServer(): Video.Server?
        fun positionMs(): Long
        fun isPlaying(): Boolean
    }

    fun wireChromeButton(
        fragment: Fragment,
        button: android.view.View?,
        bridge: PlayerBridge,
    ) {
        if (button == null) return
        button.setOnClickListener {
            SessionHandoffUi.offerFromPlayer(
                fragment = fragment,
                videoType = bridge.videoType(),
                server = bridge.currentServer(),
                positionMs = bridge.positionMs(),
                playing = bridge.isPlaying(),
                isLiveOrOffline = bridge.isLiveOrOffline(),
            )
        }
        refreshChrome(button, bridge)
    }

    fun refreshChrome(button: android.view.View?, bridge: PlayerBridge) {
        if (button == null) return
        val active = SessionHandoffSession.isOffering()
        button.visibility = if (active) android.view.View.VISIBLE else android.view.View.GONE
        if (!active) return
        val disabled = bridge.isLiveOrOffline()
        button.isEnabled = !disabled
        button.alpha = if (disabled) 0.4f else 1f
        button.contentDescription = button.context.getString(
            R.string.session_handoff_cd_offering,
            SessionHandoffSession.shortCode ?: "------",
        )
    }

    /** End/revoke open offer when the offering player is destroyed (match Cast reverse). */
    fun onDestroyView(fragment: Fragment) {
        if (!SessionHandoffSession.isOffering()) return
        val handoffId = SessionHandoffSession.handoffId
        val ctx = fragment.context?.applicationContext
        fragment.lifecycleScope.launch {
            if (ctx != null) {
                withContext(Dispatchers.IO) {
                    runCatching { SessionHandoffRepository.endOffer(ctx, handoffId) }
                }
            }
            SessionHandoffSession.clear()
        }
    }
}
