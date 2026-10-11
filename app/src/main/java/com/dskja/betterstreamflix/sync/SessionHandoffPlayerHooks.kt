package com.dskja.betterstreamflix.sync

import androidx.fragment.app.Fragment
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Video

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
        val disabled = bridge.isLiveOrOffline()
        button.isEnabled = !disabled
        button.alpha = if (disabled) 0.4f else 1f
        button.contentDescription = when {
            SessionHandoffSession.isOffering() ->
                button.context.getString(
                    R.string.session_handoff_cd_offering,
                    SessionHandoffSession.shortCode ?: "------",
                )
            else ->
                button.context.getString(R.string.session_handoff_cd)
        }
    }
}
