package com.dskja.betterstreamflix.sync

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Thin glue between TV/Mobile player and Cast Room host session.
 */
object CastRoomPlayerHooks {

    interface PlayerBridge {
        fun isLiveOrOffline(): Boolean
        fun videoType(): Video.Type
        fun positionMs(): Long
        fun isPlaying(): Boolean
        fun setPlayWhenReady(playing: Boolean)
        fun seekTo(positionMs: Long)
        fun currentCastRoomIndex(): Int
    }

    fun wireChromeButton(fragment: Fragment, button: android.view.View?) {
        if (button == null) return
        button.setOnClickListener {
            CastRoomUi.showHostMenu(fragment)
        }
        refreshChrome(button)
    }

    fun refreshChrome(button: android.view.View?) {
        if (button == null) return
        val active = CastRoomSession.isHost() || CastRoomSession.isController()
        button.visibility = if (active) android.view.View.VISIBLE else android.view.View.GONE
        if (!active) return
        button.isEnabled = true
        button.alpha = 1f
        button.contentDescription = when {
            CastRoomSession.isHost() ->
                button.context.getString(R.string.cast_room_cd_hosting)
            CastRoomSession.isController() ->
                button.context.getString(R.string.cast_room_cd_controller)
            else ->
                button.context.getString(R.string.cast_room_cd)
        }
    }

    fun ensureHostHeartbeat(fragment: Fragment, bridge: PlayerBridge) {
        if (!CastRoomSession.isHost()) return
        if (bridge.isLiveOrOffline()) return
        val roomId = CastRoomSession.roomId ?: return
        val ctx = fragment.requireContext().applicationContext
        CastRoomSync.resumeHeartbeat(ctx, roomId) {
            CastRoomSync.HostSnapshot(
                nowPlayingIndex = bridge.currentCastRoomIndex(),
                positionMs = bridge.positionMs(),
                playing = bridge.isPlaying(),
            )
        }
        val token = CastRoomSession.token
        if (token != null) {
            CastRoomUi.startHostPolling(ctx, token)
        }
        refreshChrome(fragment.view?.findViewById(R.id.btn_exo_cast_room))
    }

    fun applyRemotePlaying(fragment: Fragment, bridge: PlayerBridge, playing: Boolean) {
        fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            if (!fragment.isAdded || !CastRoomSession.isHost()) return@launch
            if (bridge.isPlaying() != playing) {
                bridge.setPlayWhenReady(playing)
            }
        }
    }

    fun applyRemoteSeek(fragment: Fragment, bridge: PlayerBridge, positionMs: Long) {
        fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            if (!fragment.isAdded || !CastRoomSession.isHost()) return@launch
            if (bridge.isLiveOrOffline()) return@launch
            bridge.seekTo(TvSidekick.clampSeek(positionMs))
        }
    }

    fun onLocalPlaybackEnded(fragment: Fragment) {
        if (!CastRoomSession.isHost()) return
        val ctx = fragment.context ?: return
        CastRoomUi.onPlaybackEndedAdvance(ctx)
    }

    fun onDestroyView(fragment: Fragment) {
        // Leaving the player does not end the Cast Room — poll stays alive so
        // Sidekick seek/play/queue still apply via the activity HostConsumer.
        // Only pause host heartbeat until the next item starts.
        if (CastRoomSession.isHost()) {
            CastRoomSync.pauseHeartbeatAsync()
        }
        refreshChrome(null)
    }
}
