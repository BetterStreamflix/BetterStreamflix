package com.betterstreamflix.app.sync

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.betterstreamflix.app.R
import com.betterstreamflix.app.download.DownloadConnectivityMonitor
import com.betterstreamflix.app.models.Video
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.profiles.ProfileUnlockGate
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.ui.LeanbackDensityPolicy
import com.betterstreamflix.app.utils.ExpDialogChrome
import com.betterstreamflix.app.utils.QrUtils
import com.betterstreamflix.app.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object WatchPartyUi {
    private val promptedPendingTokens = mutableSetOf<String>()
    private val promptedPendingCodes = mutableSetOf<String>()

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
        if (ProfileManager.isKidsActive()) {
            ExpDialogChrome.notify(context, R.string.watch_party_kids_blocked)
            return
        }
        if (CompanionSessions.refuseIfOtherCompanionActive(
                context,
                CompanionSessions.Kind.WATCH_TOGETHER,
            )
        ) {
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
                context.getString(R.string.watch_party_show_code_action),
                context.getString(R.string.watch_party_reshare_action),
                context.getString(R.string.watch_party_chat_action),
                context.getString(R.string.watch_party_sync_health_action),
                context.getString(R.string.watch_party_force_resync_action),
                context.getString(
                    if (WatchPartySession.isTvScreenMode()) {
                        R.string.watch_party_mode_sync_all_action
                    } else {
                        R.string.watch_party_mode_tv_screen_action
                    },
                ),
                context.getString(
                    if (WatchPartySession.hostControlsLocked) {
                        R.string.watch_party_unlock_controls_action
                    } else {
                        R.string.watch_party_lock_controls_action
                    },
                ),
                context.getString(R.string.watch_party_pause_all_action),
                context.getString(R.string.watch_party_end_action),
            )
            if (WatchPartySession.isInLobby()) {
                options.add(0, context.getString(R.string.watch_party_open_lobby_action))
            }
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.watch_together_pro_menu_title)
                .setItems(options.toTypedArray()) { _, which ->
                    val label = options[which]
                    when (label) {
                        context.getString(R.string.watch_party_open_lobby_action) ->
                            reopenHostLobby(fragment)
                        context.getString(R.string.watch_party_show_code_action) ->
                            showHostQr(fragment)
                        context.getString(R.string.watch_party_reshare_action) ->
                            shareExistingHostLink(fragment)
                        context.getString(R.string.watch_party_chat_action) ->
                            WatchPartyLobbyUi.showChatPanel(fragment)
                        context.getString(R.string.watch_party_sync_health_action) ->
                            showSyncHealth(fragment)
                        context.getString(R.string.watch_party_force_resync_action) ->
                            forceResync(fragment, positionMs)
                        context.getString(R.string.watch_party_mode_tv_screen_action),
                        context.getString(R.string.watch_party_mode_sync_all_action),
                        -> toggleDisplayMode(fragment)
                        context.getString(R.string.watch_party_lock_controls_action),
                        context.getString(R.string.watch_party_unlock_controls_action),
                        -> toggleControlsLocked(fragment)
                        context.getString(R.string.watch_party_pause_all_action) ->
                            pauseAll(fragment, positionMs)
                        context.getString(R.string.watch_party_end_action) ->
                            endHostParty(fragment)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        if (WatchPartySession.isRemote()) {
            val options = arrayOf(
                context.getString(R.string.watch_party_remote_open_action),
                context.getString(R.string.watch_party_chat_action),
                context.getString(R.string.watch_party_leave_action),
            )
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.watch_together_pro_menu_title)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> WatchPartyRemoteUi.showRemotePanel(fragment)
                        1 -> WatchPartyLobbyUi.showChatPanel(fragment)
                        2 -> leaveGuestParty(fragment)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        if (WatchPartySession.isGuest()) {
            val options = arrayOf(
                context.getString(R.string.watch_party_chat_action),
                context.getString(R.string.watch_party_sync_health_action),
                context.getString(R.string.watch_party_leave_action),
            )
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.watch_together_pro_menu_title)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> WatchPartyLobbyUi.showChatPanel(fragment)
                        1 -> showSyncHealth(fragment)
                        2 -> leaveGuestParty(fragment)
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
            shortCode = WatchPartySession.shortCode,
            serverSource = serverSource,
        )
    }

    private fun shareExistingHostLink(fragment: Fragment) {
        val token = WatchPartySession.token ?: return
        val ctx = fragment.context ?: return
        shareLink(ctx, WatchPartyLinks.buildUri(token))
    }

    fun showHostQr(fragment: Fragment) {
        val context = fragment.context ?: return
        val token = WatchPartySession.token ?: return
        val link = WatchPartyLinks.buildUri(token)
        val code = WatchPartySession.shortCode?.takeIf { it.isNotBlank() } ?: "------"
        val qr = runCatching { QrUtils.generate(link, 640) }.getOrNull()
        val density = context.resources.displayMetrics.density
        val chrome = LeanbackDensityPolicy.overlayChromePadPx(density)
        val gap = LeanbackDensityPolicy.overlayStackGapPx(density)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(chrome, chrome, chrome, chrome)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        if (qr != null) {
            container.addView(
                ImageView(context).apply {
                    setImageBitmap(qr)
                    adjustViewBounds = true
                    contentDescription = context.getString(R.string.watch_party_qr_cd)
                    layoutParams = LinearLayout.LayoutParams(560, 560).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                },
            )
        }
        container.addView(
            TextView(context).apply {
                text = context.getString(R.string.watch_party_code_label, code)
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(0, gap * 2, 0, gap)
            },
        )
        container.addView(
            TextView(context).apply {
                text = context.getString(R.string.watch_party_host_qr_message)
                textSize = 14f
                gravity = Gravity.CENTER
            },
        )
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.watch_party_host_qr_title)
            .setView(container)
            .setPositiveButton(R.string.watch_party_share_action) { _, _ ->
                shareLink(context, link)
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun showSyncHealth(fragment: Fragment) {
        val context = fragment.context ?: return
        val token = WatchPartySession.token ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val health = withContext(Dispatchers.IO) {
                runCatching { WatchPartyRepository.fetchSyncHealth(context, token) }.getOrNull()
            }
            if (!fragment.isAdded) return@launch
            val snapshot = if (health != null) {
                WatchPartySyncHealth.fromHealthPayload(health, WatchPartySession.lastLocalDriftMs)
            } else {
                WatchPartySyncHealth.Snapshot(
                    level = WatchPartySyncHealth.Level.UNKNOWN,
                    driftMs = WatchPartySession.lastLocalDriftMs,
                    hostAgeMs = null,
                    syncRevision = WatchPartySession.lastAppliedSyncRevision,
                    memberCount = 0,
                    maxReportedDriftMs = WatchPartySession.lastLocalDriftMs,
                    displayMode = WatchPartySession.displayMode,
                    controlsLocked = WatchPartySession.hostControlsLocked,
                )
            }
            val levelLabel = when (snapshot.level) {
                WatchPartySyncHealth.Level.GOOD -> context.getString(R.string.watch_party_sync_good)
                WatchPartySyncHealth.Level.LAGGING -> context.getString(R.string.watch_party_sync_lagging)
                WatchPartySyncHealth.Level.LOST -> context.getString(R.string.watch_party_sync_lost)
                WatchPartySyncHealth.Level.STALE -> context.getString(R.string.watch_party_sync_stale)
                WatchPartySyncHealth.Level.UNKNOWN -> context.getString(R.string.watch_party_sync_unknown)
            }
            val modeLabel = if (WatchTogetherPro.isTvScreenMode(snapshot.displayMode)) {
                context.getString(R.string.watch_party_mode_tv_screen)
            } else {
                context.getString(R.string.watch_party_mode_sync_all)
            }
            val message = context.getString(
                R.string.watch_party_sync_health_detail,
                levelLabel,
                snapshot.maxReportedDriftMs,
                snapshot.memberCount,
                snapshot.syncRevision,
                modeLabel,
            )
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.watch_party_sync_health_action)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun forceResync(fragment: Fragment, positionMs: Long) {
        val context = fragment.context ?: return
        val token = WatchPartySession.token ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { WatchPartyRepository.forceResync(context, token, positionMs) }
            }
            if (!fragment.isAdded) return@launch
            result.onSuccess {
                WatchPartySession.updateFromPayload(it)
                ExpDialogChrome.notify(context, R.string.watch_party_force_resync_done)
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    context,
                    context.getString(
                        R.string.watch_party_force_resync_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    private fun toggleDisplayMode(fragment: Fragment) {
        val context = fragment.context ?: return
        val token = WatchPartySession.token ?: return
        val next = if (WatchPartySession.isTvScreenMode()) {
            WatchTogetherPro.MODE_SYNC_ALL
        } else {
            WatchTogetherPro.MODE_TV_SCREEN
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { WatchPartyRepository.setDisplayMode(context, token, next) }
            }
            if (!fragment.isAdded) return@launch
            result.onSuccess {
                WatchPartySession.updateFromPayload(it)
                ExpDialogChrome.notify(
                    context,
                    if (WatchTogetherPro.isTvScreenMode(next)) {
                        R.string.watch_party_mode_tv_screen_on
                    } else {
                        R.string.watch_party_mode_sync_all_on
                    },
                )
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    context,
                    context.getString(
                        R.string.watch_party_mode_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    private fun toggleControlsLocked(fragment: Fragment) {
        val context = fragment.context ?: return
        val token = WatchPartySession.token ?: return
        val next = !WatchPartySession.hostControlsLocked
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { WatchPartyRepository.setControlsLocked(context, token, next) }
            }
            if (!fragment.isAdded) return@launch
            result.onSuccess {
                WatchPartySession.updateFromPayload(it)
                ExpDialogChrome.notify(
                    context,
                    if (next) R.string.watch_party_controls_locked
                    else R.string.watch_party_controls_unlocked,
                )
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    context,
                    context.getString(
                        R.string.watch_party_mode_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    /** CC-086: pause host + force guests to the paused playhead. */
    private fun pauseAll(fragment: Fragment, positionMs: Long) {
        val context = fragment.context ?: return
        val token = WatchPartySession.token ?: return
        val partyId = WatchPartySession.partyId ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    WatchPartyRepository.heartbeat(
                        context = context,
                        partyId = partyId,
                        server = null,
                        positionMs = positionMs,
                        playing = false,
                    )
                    WatchPartyRepository.forceResync(context, token, positionMs)
                }
            }
            if (!fragment.isAdded) return@launch
            result.onSuccess {
                WatchPartySession.updateFromPayload(it)
                ExpDialogChrome.notify(context, R.string.watch_party_pause_all_done)
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    context,
                    context.getString(
                        R.string.watch_party_force_resync_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
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
                WatchPartySession.beginHost(
                    token = created.token,
                    partyId = created.partyId,
                    inLobby = true,
                    shortCode = created.shortCode,
                    displayMode = created.displayMode,
                )
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
                    shortCode = created.shortCode,
                    serverSource = serverSource,
                )
                showHostQr(fragment)
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
        val token = WatchPartySession.token
        val ctx = fragment.context?.applicationContext
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (ctx != null && token != null) {
                    runCatching { WatchPartyRepository.leaveMember(ctx, token) }
                }
                WatchPartySync.stop()
            }
            WatchPartyRemoteUi.clear()
            WatchPartySession.clear()
            fragment.context?.let {
                ExpDialogChrome.notify(it, R.string.watch_party_left)
            }
        }
    }

    fun handleIncomingCode(activity: FragmentActivity, rawCode: String) {
        val code = WatchPartyLinks.normalizeShortCode(rawCode)
        if (code == null) {
            ExpDialogChrome.notify(activity, R.string.watch_party_invalid_code)
            return
        }
        if (ProfileManager.isKidsActive()) {
            ExpDialogChrome.notify(activity, R.string.watch_party_kids_blocked)
            return
        }
        val appContext = activity.applicationContext
        if (ProfileUnlockGate.requiresGateForActive()) {
            WatchPartyPendingJoin.enqueueCode(appContext, code)
            ExpDialogChrome.notify(activity, R.string.watch_party_join_needs_unlock)
            return
        }
        if (!SupabaseProvider.isConfigured) {
            WatchPartyPendingJoin.enqueueCode(appContext, code)
            ExpDialogChrome.notify(activity, R.string.watch_party_join_needs_supabase)
            return
        }
        if (!DownloadConnectivityMonitor.isOnline(activity)) {
            WatchPartyPendingJoin.enqueueCode(appContext, code)
            ExpDialogChrome.notify(activity, R.string.watch_party_join_queued_offline)
            return
        }
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { WatchPartyRepository.fetchByCode(activity, code) }
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            result.onSuccess { payload ->
                val token = payload.shareToken ?: return@onSuccess
                promptJoin(
                    activity,
                    token,
                    preferRemote = WatchTogetherPro.isTvScreenMode(payload.displayMode),
                )
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    activity,
                    activity.getString(
                        R.string.watch_party_join_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    fun handleIncomingToken(activity: FragmentActivity, token: String) {
        val appContext = activity.applicationContext
        if (!WatchPartyLinks.isValidToken(token)) {
            ExpDialogChrome.notify(activity, R.string.watch_party_invalid_link)
            return
        }
        if (ProfileManager.isKidsActive()) {
            ExpDialogChrome.notify(activity, R.string.watch_party_kids_blocked)
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
        // preferRemote resolved from payload display_mode inside promptJoin.
        promptJoin(activity, token, preferRemote = true)
    }

    fun tryFlushPending(activity: FragmentActivity) {
        if (ProfileUnlockGate.requiresGateForActive()) return
        if (ProfileManager.isKidsActive()) return
        if (!SupabaseProvider.isConfigured) return
        if (!DownloadConnectivityMonitor.isOnline(activity)) return

        val pendingCode = WatchPartyPendingJoin.peekCodes(activity)
            .firstOrNull { it !in promptedPendingCodes }
        if (pendingCode != null) {
            promptedPendingCodes.add(pendingCode)
            activity.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { WatchPartyRepository.fetchByCode(activity, pendingCode) }
                }
                if (activity.isFinishing || activity.isDestroyed) return@launch
                result.onSuccess { payload ->
                    val token = payload.shareToken
                    if (token.isNullOrBlank()) {
                        WatchPartyPendingJoin.acknowledgeCode(activity, pendingCode)
                        promptedPendingCodes.remove(pendingCode)
                        return@onSuccess
                    }
                    WatchPartyPendingJoin.acknowledgeCode(activity, pendingCode)
                    promptedPendingCodes.remove(pendingCode)
                    promptJoin(
                        activity,
                        token,
                        preferRemote = WatchTogetherPro.isTvScreenMode(payload.displayMode),
                    )
                }.onFailure {
                    WatchPartyPendingJoin.acknowledgeCode(activity, pendingCode)
                    promptedPendingCodes.remove(pendingCode)
                }
            }
            return
        }

        val pending = WatchPartyPendingJoin.peek(activity)
        val next = pending.firstOrNull { it !in promptedPendingTokens } ?: return
        promptedPendingTokens.add(next)
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { WatchPartyRepository.flushStalePending(activity) }
            }
            if (WatchPartyPendingJoin.peek(activity).contains(next)) {
                promptJoin(activity, next, preferRemote = true)
            } else {
                promptedPendingTokens.remove(next)
            }
        }
    }

    private fun promptJoin(
        activity: FragmentActivity,
        token: String,
        @Suppress("UNUSED_PARAMETER") preferRemote: Boolean = true,
    ) {
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
            // CC-030: tv_screen parties default to phone-remote join path.
            val tvScreen = WatchTogetherPro.isTvScreenMode(payload.displayMode)
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
                if (tvScreen) {
                    append('\n')
                    append(activity.getString(R.string.watch_party_join_remote_note))
                }
                payload.shortCode?.takeIf { it.isNotBlank() }?.let { code ->
                    append('\n')
                    append(activity.getString(R.string.watch_party_code_label, code))
                }
            }
            val builder = androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(R.string.watch_party_join_confirm_title)
                .setMessage(detail)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    WatchPartyPendingJoin.acknowledge(activity, token)
                    promptedPendingTokens.remove(token)
                }
                .setOnCancelListener {
                    WatchPartyPendingJoin.acknowledge(activity, token)
                    promptedPendingTokens.remove(token)
                }
            if (tvScreen) {
                // CC-030: default / primary path is phone remote for tv_screen parties.
                builder.setPositiveButton(R.string.watch_party_join_as_remote_action) { _, _ ->
                    WatchPartyPendingJoin.acknowledge(activity, token)
                    promptedPendingTokens.remove(token)
                    enterParty(activity, token, payload, asRemote = true)
                }
                builder.setNeutralButton(R.string.watch_party_join_action) { _, _ ->
                    WatchPartyPendingJoin.acknowledge(activity, token)
                    promptedPendingTokens.remove(token)
                    enterParty(activity, token, payload, asRemote = false)
                }
            } else {
                builder.setPositiveButton(R.string.watch_party_join_action) { _, _ ->
                    WatchPartyPendingJoin.acknowledge(activity, token)
                    promptedPendingTokens.remove(token)
                    enterParty(activity, token, payload, asRemote = false)
                }
            }
            val dialog = builder.create()
            dialog.setOnShowListener {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.requestFocus()
            }
            dialog.show()
        }
    }

    private fun enterParty(
        activity: FragmentActivity,
        token: String,
        payload: WatchPartyPayload,
        asRemote: Boolean = false,
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
            if (asRemote) {
                WatchPartySession.beginRemote(
                    token = token,
                    partyId = lobby.party.id,
                    shortCode = lobby.party.shortCode ?: payload.shortCode,
                    displayMode = lobby.party.displayMode,
                )
                WatchPartyRemoteUi.ensureRemotePolling(activity, token)
                WatchPartyRemoteUi.showRemotePanel(activity)
                ExpDialogChrome.notify(activity, R.string.watch_party_remote_joined)
                return@launch
            }
            WatchPartySession.beginGuest(
                token = token,
                partyId = lobby.party.id,
                inLobby = lobby.party.inLobby,
                shortCode = lobby.party.shortCode ?: payload.shortCode,
                displayMode = lobby.party.displayMode,
            )
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
            WatchPartySession.beginGuest(
                token = token,
                partyId = payload.id,
                inLobby = false,
                shortCode = payload.shortCode,
                displayMode = payload.displayMode,
            )
            val args = playerArgs(videoType, payload)
            fun navigateToPlayer() {
                val navHost =
                    activity.supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
                val nav = navHost?.navController
                if (nav == null) {
                    ExpDialogChrome.notify(activity, R.string.watch_party_join_failed_nav)
                    WatchPartySession.clear()
                    return
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
            if (com.betterstreamflix.app.kids.KidsBedtime.shouldBlockEnteringPlayer()) {
                com.betterstreamflix.app.kids.KidsBedtimeUi.blockPlayerEntry(activity) {
                    navigateToPlayer()
                }
                return@launch
            }
            navigateToPlayer()
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
