package com.dskja.betterstreamflix.sync

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        CastRoomSync.startHostHeartbeat(ctx, roomId) {
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

    fun onLocalPlaybackEnded(fragment: Fragment) {
        if (!CastRoomSession.isHost()) return
        val ctx = fragment.context ?: return
        CastRoomUi.onPlaybackEndedAdvance(ctx)
    }

    suspend fun teardown(fragment: Fragment, endHost: Boolean) {
        val roomId = CastRoomSession.roomId
        val wasHost = CastRoomSession.isHost()
        // Controllers keep their session when leaving an unrelated player.
        if (!wasHost) return
        CastRoomSync.stop()
        if (endHost) {
            val ctx = fragment.context?.applicationContext
            if (ctx != null) {
                withContext(Dispatchers.IO) {
                    runCatching { CastRoomRepository.endRoom(ctx, roomId) }
                }
            }
            CastRoomSession.clear()
        }
    }

    fun onDestroyView(fragment: Fragment) {
        // Leaving the player does not end the Cast Room — phone can still queue.
        // Host heartbeat pauses until the next item starts.
        fragment.lifecycleScope.launch {
            if (CastRoomSession.isHost()) {
                CastRoomSync.stop()
            }
        }
        refreshChrome(null)
    }

    fun notifyHostReady(fragment: Fragment, bridge: PlayerBridge) {
        ensureHostHeartbeat(fragment, bridge)
        refreshChrome(fragment.view?.findViewById(R.id.btn_exo_cast_room))
        ExpDialogChrome.notify(fragment.requireContext(), R.string.cast_room_host_ready)
    }
}
