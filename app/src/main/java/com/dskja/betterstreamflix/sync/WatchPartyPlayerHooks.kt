package com.dskja.betterstreamflix.sync

import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thin glue between player fragments and Watch Together / Watch-Party session/sync.
 * Call from Mobile + TV player after chrome setup and on progress / teardown.
 */
object WatchPartyPlayerHooks {

    interface PlayerBridge {
        fun isLiveOrOffline(): Boolean
        fun videoType(): Video.Type
        fun currentServer(): Video.Server?
        fun servers(): List<Video.Server>
        fun positionMs(): Long
        fun isPlaying(): Boolean
        fun durationMs(): Long? = null
        fun seekTo(positionMs: Long)
        fun setPlayWhenReady(playing: Boolean)
        fun requestServer(server: Video.Server)
    }

    fun wireChromeButton(
        fragment: Fragment,
        button: android.view.View?,
        bridge: PlayerBridge,
    ) {
        if (button == null) return
        button.setOnClickListener {
            WatchPartyUi.showHostMenu(
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
            WatchPartySession.isHost() ->
                button.context.getString(R.string.watch_party_cd_hosting)
            WatchPartySession.isRemote() ->
                button.context.getString(R.string.watch_party_cd_remote)
            WatchPartySession.isGuest() ->
                button.context.getString(R.string.watch_party_cd_joined)
            else ->
                button.context.getString(R.string.watch_party_cd)
        }
    }

    /** Call once video is ready (and whenever session begins while already playing). */
    fun ensureSyncRunning(fragment: Fragment, bridge: PlayerBridge) {
        if (!WatchPartySession.isActive()) return
        if (WatchPartySession.isRemote()) {
            val token = WatchPartySession.token ?: return
            WatchPartyRemoteUi.ensureRemotePolling(fragment.requireContext(), token)
            return
        }
        // Lobby: presence/chat only — no position sync until the host starts.
        if (WatchPartySession.isInLobby()) return
        if (bridge.isLiveOrOffline()) {
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                teardown(fragment, endHost = false)
            }
            return
        }
        val ctx = fragment.requireContext().applicationContext
        when {
            WatchPartySession.isHost() -> {
                val partyId = WatchPartySession.partyId ?: return
                WatchPartySync.startHost(ctx, partyId) {
                    WatchPartySync.HostSnapshot(
                        positionMs = bridge.positionMs(),
                        playing = bridge.isPlaying(),
                        server = bridge.currentServer(),
                    )
                }
                // TV / host polls for phone-remote sidekick only in tv_screen mode.
                val token = WatchPartySession.token ?: return
                WatchPartySync.startGuest(
                    context = ctx,
                    token = token,
                    listener = { payload ->
                        if (WatchTogetherPro.isTvScreenMode(payload.displayMode) ||
                            WatchPartySession.isTvScreenMode()
                        ) {
                            applyHostSidekick(fragment, bridge, payload)
                        } else {
                            WatchPartySession.updateFromPayload(payload)
                        }
                    },
                    onEnded = {
                        fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            if (!fragment.isAdded) return@launch
                            // Host end is driven by local teardown; ignore guest-ended noise.
                        }
                    },
                )
            }
            WatchPartySession.isGuest() -> {
                val token = WatchPartySession.token ?: return
                WatchPartySync.startGuest(
                    context = ctx,
                    token = token,
                    listener = { payload -> applyGuestUpdate(fragment, bridge, payload) },
                    onEnded = {
                        fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            if (!fragment.isAdded) return@launch
                            WatchPartySession.clear()
                            ExpDialogChrome.notify(fragment.requireContext(), R.string.watch_party_ended)
                            refreshChrome(
                                fragment.view?.findViewById(R.id.btn_exo_watch_party),
                                bridge,
                            )
                        }
                        fragment.viewLifecycleOwner.lifecycleScope.launch {
                            WatchPartySync.stop()
                        }
                    },
                )
            }
        }
    }

    fun onLocalControlChanged(fragment: Fragment, bridge: PlayerBridge) {
        if (WatchPartySession.isRemote()) return
        if (WatchPartySession.isInLobby()) return

        // CC-002: when host locked controls, guests must not lead — snap back.
        if (WatchPartySession.isGuest() && WatchPartySession.hostControlsLocked) {
            fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                if (!fragment.isAdded) return@launch
                val target = WatchPartySession.lastAppliedPositionMs
                if (target >= 0L &&
                    WatchPartySync.shouldSeek(bridge.positionMs(), target)
                ) {
                    bridge.seekTo(target)
                }
                WatchPartySession.lastAppliedPlaying?.let { playing ->
                    if (bridge.isPlaying() != playing) {
                        bridge.setPlayWhenReady(playing)
                    }
                }
            }
            return
        }

        if (!WatchPartySession.isHost()) return
        val partyId = WatchPartySession.partyId ?: return
        WatchPartySync.publishHostNow(
            context = fragment.requireContext(),
            partyId = partyId,
            snapshot = WatchPartySync.HostSnapshot(
                positionMs = bridge.positionMs(),
                playing = bridge.isPlaying(),
                server = bridge.currentServer(),
            ),
        )
        refreshChrome(fragment.view?.findViewById(R.id.btn_exo_watch_party), bridge)
    }

    fun applyHostSidekick(
        fragment: Fragment,
        bridge: PlayerBridge,
        payload: WatchPartyPayload,
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            if (!fragment.isAdded || !WatchPartySession.isHost()) return@launch
            // CC-001: only apply phone remote commands in tv_screen mode.
            if (!WatchTogetherPro.isTvScreenMode(payload.displayMode)) {
                WatchPartySession.updateFromPayload(payload)
                return@launch
            }
            WatchPartySession.updateFromPayload(payload)
            WatchPartySession.lastSeenSidekickSeekSeq =
                maxOf(WatchPartySession.lastSeenSidekickSeekSeq, payload.sidekickSeekSeq)

            if (WatchTogetherPro.shouldApplySeek(
                    payload.sidekickSeekSeq,
                    WatchPartySession.lastAppliedSeekSeq,
                    payload.sidekickSeekMs,
                )
            ) {
                val target = WatchTogetherPro.clampSeek(
                    payload.sidekickSeekMs!!,
                    bridge.durationMs(),
                )
                bridge.seekTo(target)
                WatchPartySession.lastAppliedSeekSeq = payload.sidekickSeekSeq
                WatchPartySession.lastAppliedPositionMs = target
                onLocalControlChanged(fragment, bridge)
            }

            if (WatchTogetherPro.shouldApplyPlay(
                    payload.sidekickPlaySeq,
                    WatchPartySession.lastAppliedPlaySeq,
                    payload.sidekickPlaying,
                )
            ) {
                bridge.setPlayWhenReady(payload.sidekickPlaying!!)
                WatchPartySession.lastAppliedPlaySeq = payload.sidekickPlaySeq
                WatchPartySession.lastAppliedPlaying = payload.sidekickPlaying
                onLocalControlChanged(fragment, bridge)
            }
        }
    }

    fun applyGuestUpdate(
        fragment: Fragment,
        bridge: PlayerBridge,
        payload: WatchPartyPayload,
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            if (!fragment.isAdded || !WatchPartySession.isGuest()) return@launch
            WatchPartySession.updateFromPayload(payload)

            val remoteServerKey = WatchPartySync.serverKey(payload.serverId, payload.serverName)
            if (remoteServerKey != null &&
                remoteServerKey != WatchPartySession.lastAppliedServerKey
            ) {
                val match = bridge.servers().firstOrNull {
                    (!payload.serverId.isNullOrBlank() && it.id == payload.serverId) ||
                        (!payload.serverName.isNullOrBlank() &&
                            it.name.equals(payload.serverName, ignoreCase = true))
                }
                if (match != null &&
                    WatchPartySync.serverKey(bridge.currentServer()) != WatchPartySync.serverKey(match)
                ) {
                    WatchPartySession.lastAppliedServerKey = remoteServerKey
                    bridge.requestServer(match)
                    // CC-085: still apply seek/play this tick after requesting server.
                }
                WatchPartySession.lastAppliedServerKey = remoteServerKey
            }

            val force = WatchTogetherPro.shouldForceResync(
                payload.syncRevision,
                WatchPartySession.lastAppliedSyncRevision,
            )
            val locked = payload.hostControlsLocked
            val target = WatchTogetherPro.clampSeek(payload.positionMs, bridge.durationMs())
            val drifted = WatchPartySync.shouldSeek(bridge.positionMs(), target)
            // When locked, snap on any meaningful drift so local scrub cannot lead.
            if (force || drifted) {
                bridge.seekTo(target)
                WatchPartySession.lastGuestSeekAtMs = System.currentTimeMillis()
                WatchPartySession.lastAppliedPositionMs = target
                WatchPartySession.lastAppliedSyncRevision = payload.syncRevision
            } else if (locked &&
                kotlin.math.abs(bridge.positionMs() - target) > 250L
            ) {
                bridge.seekTo(target)
                WatchPartySession.lastGuestSeekAtMs = System.currentTimeMillis()
                WatchPartySession.lastAppliedPositionMs = target
            }

            if (WatchPartySession.lastAppliedPlaying != payload.playing) {
                bridge.setPlayWhenReady(payload.playing)
                WatchPartySession.lastAppliedPlaying = payload.playing
            }

            val drift = WatchPartySyncHealth.driftBetween(bridge.positionMs(), payload.positionMs)
            WatchPartySession.lastLocalDriftMs = drift
            val token = WatchPartySession.token
            val now = System.currentTimeMillis()
            if (token != null &&
                WatchTogetherPro.shouldReportDrift(
                    nowMs = now,
                    lastReportAtMs = WatchPartySession.lastDriftReportAtMs,
                    lastSeekAtMs = WatchPartySession.lastGuestSeekAtMs,
                    driftMs = drift,
                    lastReportedDriftMs = WatchPartySession.lastReportedDriftMs,
                )
            ) {
                WatchPartySession.lastDriftReportAtMs = now
                WatchPartySession.lastReportedDriftMs = drift
                fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    WatchPartyRepository.reportDrift(fragment.requireContext(), token, drift)
                }
            }
        }
    }

    suspend fun teardown(fragment: Fragment, endHost: Boolean) {
        val partyId = WatchPartySession.partyId
        val wasHost = WatchPartySession.isHost()
        WatchPartySync.stop()
        WatchPartyRemoteUi.clear()
        if (endHost && wasHost) {
            val ctx = fragment.context?.applicationContext
            if (ctx != null) {
                withContext(Dispatchers.IO) {
                    runCatching { WatchPartyRepository.endParty(ctx, partyId) }
                }
            }
        }
        WatchPartySession.clear()
    }

    fun onDestroyView(fragment: Fragment) {
        fragment.lifecycleScope.launch {
            // CC-056: stop heartbeat/polls only — do not end the party or clear session.
            // Explicit End / Leave owns teardown.
            WatchPartySync.stop()
        }
    }

    fun notifyHostStarted(fragment: Fragment, bridge: PlayerBridge) {
        ensureSyncRunning(fragment, bridge)
        refreshChrome(fragment.view?.findViewById(R.id.btn_exo_watch_party), bridge)
        Toast.makeText(
            fragment.requireContext(),
            R.string.watch_party_host_started,
            Toast.LENGTH_SHORT,
        ).show()
    }
}
