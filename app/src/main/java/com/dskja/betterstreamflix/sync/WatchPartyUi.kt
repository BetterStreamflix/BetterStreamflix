package com.dskja.betterstreamflix.sync

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.download.DownloadConnectivityMonitor
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.profiles.ProfileUnlockGate
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object WatchPartyUi {
    private val promptedPendingTokens = mutableSetOf<String>()

    @Volatile
    private var confirmInFlight = false

    /** Optional callback so the active player can start host heartbeats immediately. */
    interface WatchPartyHostAware {
        fun onWatchPartyHostStarted()
        fun watchPartyServerSource(): WatchPartyLobbyUi.ServerSource? = null
    }

    fun showHostMenu(
        fragment: Fragment,
        videoType: Video.Type,
        server: Video.Server?,
        positionMs: Long,
        playing: Boolean,
        isLiveOrOffline: Boolean,
    ) {
        val context = fragment.context ?: return
        if (isLiveOrOffline) {
            ExpDialogChrome.notify(context, R.string.watch_party_unavailable_live_offline)
            return
        }
        if (!SupabaseProvider.isConfigured) {
            ExpDialogChrome.notify(context, R.string.watch_party_needs_supabase)
            return
        }
        if (CloudSyncManager.currentUserId() == null) {
            ExpDialogChrome.notify(context, R.string.watch_party_needs_sign_in)
            return
        }

        if (WatchPartySession.isHost()) {
            val options = mutableListOf(
                context.getString(R.string.watch_party_reshare_action),
                context.getString(R.string.watch_party_chat_action),
                context.getString(R.string.watch_party_end_action),
            )
            if (WatchPartySession.isInLobby()) {
                options.add(0, context.getString(R.string.watch_party_open_lobby_action))
            }
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.watch_party_menu_title)
                .setItems(options.toTypedArray()) { _, which ->
                    val label = options[which]
                    when (label) {
                        context.getString(R.string.watch_party_open_lobby_action) ->
                            reopenHostLobby(fragment)
                        context.getString(R.string.watch_party_reshare_action) ->
                            shareExistingHostLink(fragment)
                        context.getString(R.string.watch_party_chat_action) ->
                            WatchPartyLobbyUi.showChatPanel(fragment)
                        context.getString(R.string.watch_party_end_action) ->
                            endHostParty(fragment)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        if (WatchPartySession.isGuest()) {
            val options = arrayOf(
                context.getString(R.string.watch_party_chat_action),
                context.getString(R.string.watch_party_leave_action),
            )
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.watch_party_menu_title)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> WatchPartyLobbyUi.showChatPanel(fragment)
                        1 -> leaveGuestParty(fragment)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.watch_party_menu_title)
            .setMessage(R.string.watch_party_host_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.watch_party_host_action) { _, _ ->
                hostAndShare(fragment, videoType, server, positionMs, playing)
            }
            .show()
    }

    private fun reopenHostLobby(fragment: Fragment) {
        val token = WatchPartySession.token ?: return
        val serverSource = (fragment as? WatchPartyHostAware)?.watchPartyServerSource()
        WatchPartyLobbyUi.showHostLobby(
            fragment = fragment,
            token = token,
            link = WatchPartyLinks.buildUri(token),
            serverSource = serverSource,
        )
    }

    private fun shareExistingHostLink(fragment: Fragment) {
        val token = WatchPartySession.token ?: return
        val ctx = fragment.context ?: return
        shareLink(ctx, WatchPartyLinks.buildUri(token))
    }

    fun shareLink(context: Context, link: String) {
        launchShare(context, link)
    }

    private fun hostAndShare(
        fragment: Fragment,
        videoType: Video.Type,
        server: Video.Server?,
        positionMs: Long,
        playing: Boolean,
    ) {
        val context = fragment.context ?: return
        if (!DownloadConnectivityMonitor.isOnline(context)) {
            ExpDialogChrome.notify(context, R.string.watch_party_offline)
            return
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    WatchPartyRepository.createParty(
                        context = context,
                        videoType = videoType,
                        server = server,
                        positionMs = positionMs,
                    )
                }
            }
            if (!fragment.isAdded) return@launch
            val ctx = fragment.context ?: return@launch
            result.onSuccess { created ->
                WatchPartySession.beginHost(created.token, created.partyId, inLobby = true)
                shareLink(ctx, created.link)
                ExpDialogChrome.notify(ctx, R.string.watch_party_host_lobby_ready)
                val serverSource = (fragment as? WatchPartyHostAware)?.watchPartyServerSource()
                // Pause while waiting in lobby; playback starts when host taps Start.
                if (playing) {
                    serverSource?.pauseLocalPlayback()
                }
                WatchPartyLobbyUi.showHostLobby(
                    fragment = fragment,
                    token = created.token,
                    link = created.link,
                    serverSource = serverSource,
                )
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    ctx,
                    ctx.getString(
                        R.string.watch_party_host_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    fun endHostParty(fragment: Fragment) {
        val ctx = fragment.context ?: return
        val partyId = WatchPartySession.partyId
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { WatchPartyRepository.endParty(ctx, partyId) }
                WatchPartySync.stop()
            }
            WatchPartySession.clear()
            if (fragment.isAdded) {
                ExpDialogChrome.notify(ctx, R.string.watch_party_ended)
            }
        }
    }

    fun leaveGuestParty(fragment: Fragment) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            WatchPartySync.stop()
            WatchPartySession.clear()
            fragment.context?.let {
                ExpDialogChrome.notify(it, R.string.watch_party_left)
            }
        }
    }

    fun handleIncomingToken(activity: FragmentActivity, token: String) {
        val appContext = activity.applicationContext
        if (!WatchPartyLinks.isValidToken(token)) {
            ExpDialogChrome.notify(activity, R.string.watch_party_invalid_link)
            return
        }
        if (ProfileUnlockGate.requiresGateForActive()) {
            WatchPartyPendingJoin.enqueue(appContext, token)
            return
        }
        if (!SupabaseProvider.isConfigured) {
            WatchPartyPendingJoin.enqueue(appContext, token)
            ExpDialogChrome.notify(activity, R.string.watch_party_join_needs_supabase)
            return
        }
        if (!DownloadConnectivityMonitor.isOnline(appContext)) {
            WatchPartyPendingJoin.enqueue(appContext, token)
            ExpDialogChrome.notify(activity, R.string.watch_party_join_queued_offline)
            return
        }
        promptJoin(activity, token)
    }

    fun tryFlushPending(activity: FragmentActivity) {
        if (ProfileUnlockGate.requiresGateForActive()) return
        if (!SupabaseProvider.isConfigured) return
        if (!DownloadConnectivityMonitor.isOnline(activity)) return
        val pending = WatchPartyPendingJoin.peek(activity)
        val next = pending.firstOrNull { it !in promptedPendingTokens } ?: return
        promptedPendingTokens.add(next)
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { WatchPartyRepository.flushStalePending(activity) }
            }
            if (WatchPartyPendingJoin.peek(activity).contains(next)) {
                promptJoin(activity, next)
            } else {
                promptedPendingTokens.remove(next)
            }
        }
    }

    private fun promptJoin(activity: FragmentActivity, token: String) {
        if (confirmInFlight) return
        confirmInFlight = true
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { WatchPartyRepository.fetchByToken(activity, token) }
            }
            confirmInFlight = false
            if (activity.isFinishing || activity.isDestroyed) return@launch
            result.onFailure { error ->
                WatchPartyPendingJoin.acknowledge(activity, token)
                promptedPendingTokens.remove(token)
                ExpDialogChrome.notify(
                    activity,
                    activity.getString(
                        R.string.watch_party_join_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
                return@launch
            }
            val payload = result.getOrNull() ?: return@launch
            val title = payload.title.ifBlank { activity.getString(R.string.watch_party_menu_title) }
            val detail = buildString {
                append(title)
                if (payload.subtitle.isNotBlank()) {
                    append(" · ")
                    append(payload.subtitle)
                }
                append('\n')
                append(activity.getString(R.string.watch_party_join_privacy_note))
                if (payload.inLobby) {
                    append('\n')
                    append(activity.getString(R.string.watch_party_join_lobby_note))
                }
            }
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(R.string.watch_party_join_confirm_title)
                .setMessage(detail)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    WatchPartyPendingJoin.acknowledge(activity, token)
                    promptedPendingTokens.remove(token)
                }
                .setPositiveButton(R.string.watch_party_join_action) { _, _ ->
                    WatchPartyPendingJoin.acknowledge(activity, token)
                    promptedPendingTokens.remove(token)
                    enterParty(activity, token, payload)
                }
                .setOnCancelListener {
                    WatchPartyPendingJoin.acknowledge(activity, token)
                    promptedPendingTokens.remove(token)
                }
                .show()
        }
    }

    private fun enterParty(
        activity: FragmentActivity,
        token: String,
        payload: WatchPartyPayload,
    ) {
        activity.lifecycleScope.launch {
            val lobbyResult = withContext(Dispatchers.IO) {
                runCatching { WatchPartyRepository.joinLobby(activity, token, isHost = false) }
            }
            lobbyResult.onFailure { error ->
                ExpDialogChrome.notify(
                    activity,
                    activity.getString(
                        R.string.watch_party_join_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
                return@launch
            }
            val lobby = lobbyResult.getOrNull() ?: return@launch
            WatchPartySession.beginGuest(token, lobby.party.id, inLobby = lobby.party.inLobby)
            if (lobby.party.inLobby) {
                WatchPartyLobbyUi.showGuestLobby(activity, token) { readyToken, readyPayload ->
                    joinAndNavigate(activity, readyToken, readyPayload)
                }
            } else {
                joinAndNavigate(activity, token, lobby.party)
            }
        }
    }

    private fun joinAndNavigate(
        activity: FragmentActivity,
        token: String,
        payload: WatchPartyPayload,
    ) {
        activity.lifecycleScope.launch {
            val provider = Provider.findByName(payload.provider)
            if (provider == null) {
                ExpDialogChrome.notify(activity, R.string.watch_party_unknown_provider)
                WatchPartySession.clear()
                return@launch
            }
            UserPreferences.setCurrentProviderForPlayback(provider)
            val videoType = runCatching { WatchPartyRepository.toVideoType(payload) }.getOrElse {
                ExpDialogChrome.notify(
                    activity,
                    activity.getString(
                        R.string.watch_party_join_failed,
                        it.message ?: it.javaClass.simpleName,
                    ),
                )
                WatchPartySession.clear()
                return@launch
            }
            WatchPartySession.beginGuest(token, payload.id, inLobby = false)
            val args = playerArgs(videoType, payload)
            val navHost =
                activity.supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
            val nav = navHost?.navController
            if (nav == null) {
                ExpDialogChrome.notify(activity, R.string.watch_party_join_failed_nav)
                WatchPartySession.clear()
                return@launch
            }
            val navigated = runCatching {
                when {
                    nav.currentDestination?.getAction(R.id.action_playerMobileFragment_self) != null ->
                        nav.navigate(R.id.action_playerMobileFragment_self, args)
                    nav.currentDestination?.getAction(R.id.action_playerTvFragment_self) != null ->
                        nav.navigate(R.id.action_playerTvFragment_self, args)
                    else ->
                        nav.navigate(R.id.action_global_player, args)
                }
            }.isSuccess || runCatching {
                nav.navigate(R.id.player, args)
            }.isSuccess
            if (!navigated) {
                ExpDialogChrome.notify(activity, R.string.watch_party_join_failed_nav)
                WatchPartySession.clear()
            }
        }
    }

    private fun playerArgs(videoType: Video.Type, payload: WatchPartyPayload): Bundle {
        val title: String
        val subtitle: String
        when (videoType) {
            is Video.Type.Movie -> {
                title = videoType.title
                subtitle = ""
            }
            is Video.Type.Episode -> {
                title = videoType.tvShow.title
                subtitle = payload.subtitle.ifBlank {
                    "S${videoType.season.number} E${videoType.number}"
                }
            }
        }
        return bundleOf(
            "id" to when (videoType) {
                is Video.Type.Movie -> videoType.id
                is Video.Type.Episode -> videoType.id
            },
            "title" to title,
            "subtitle" to subtitle,
            "videoType" to videoType,
            "preferredServerName" to payload.serverName,
        )
    }

    private fun launchShare(context: Context, link: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.watch_party_share_subject))
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    send,
                    context.getString(R.string.watch_party_share_action),
                ),
            )
        }.onFailure {
            Toast.makeText(context, link, Toast.LENGTH_LONG).show()
        }
    }
}
