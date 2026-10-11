package com.dskja.betterstreamflix.sync

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lobby UI before synced playback starts: members, host server pick, chat + emoji reacts.
 */
object WatchPartyLobbyUi {

    interface ServerSource {
        fun servers(): List<Video.Server>
        fun currentServer(): Video.Server?
        fun onServerPicked(server: Video.Server)
        fun pauseLocalPlayback()
        fun resumeLocalPlayback()
    }

    fun interface OnPlaybackReady {
        fun openPlayer(token: String, payload: WatchPartyPayload)
    }

    fun showHostLobby(
        fragment: Fragment,
        token: String,
        link: String,
        shortCode: String? = null,
        serverSource: ServerSource?,
    ) {
        val context = fragment.context ?: return
        serverSource?.pauseLocalPlayback()
        val chatLines = ArrayDeque<String>()
        val view = inflateLobby(context)
        val code = shortCode?.takeIf { it.isNotBlank() } ?: WatchPartySession.shortCode
        view.code.text = context.getString(
            R.string.watch_party_code_label,
            code ?: "------",
        )
        view.code.visibility = View.VISIBLE
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.watch_together_pro_lobby_title)
            .setView(view.root)
            .setCancelable(false)
            .setNeutralButton(R.string.watch_party_show_code_action, null)
            .setNegativeButton(R.string.watch_party_end_action, null)
            .setPositiveButton(R.string.watch_party_start_action, null)
            .create()

        bindChatControls(fragment, view, token, chatLines) {
            refreshChat(view.chat, chatLines)
        }
        bindReactions(fragment, view.reactions, token)

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                WatchPartyUi.shareLink(context, link)
                WatchPartyUi.showHostQr(fragment)
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    WatchPartyUi.endHostParty(fragment)
                    WatchPartySync.stopLobbyOnly()
                    dialog.dismiss()
                }
            }
            val startBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            startBtn?.setOnClickListener {
                startHostPlayback(fragment, dialog, token, serverSource)
            }
            // CC-055: request focus on primary action for TV D-pad.
            startBtn?.requestFocus()
            view.server.setOnClickListener {
                if (serverSource != null) {
                    pickServer(fragment, serverSource, view.server)
                }
            }
        }
        dialog.setOnDismissListener {
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                WatchPartySync.stopLobbyOnly()
            }
        }
        dialog.show()

        WatchPartySync.startLobby(
            context = context,
            token = token,
            listener = { lobby, messages ->
                fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    if (!fragment.isAdded || !dialog.isShowing) return@launch
                    applyLobbySnapshot(view, lobby, isHost = true, token = token, owner = fragment)
                    appendMessages(context, chatLines, messages, view.chat)
                    if (!lobby.party.inLobby) {
                        WatchPartySession.markPlaybackStarted()
                        dialog.dismiss()
                        serverSource?.resumeLocalPlayback()
                        (fragment as? WatchPartyUi.WatchPartyHostAware)?.onWatchPartyHostStarted()
                    }
                }
            },
            onEnded = {
                fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    if (!fragment.isAdded) return@launch
                    dialog.dismiss()
                    WatchPartySession.clear()
                    ExpDialogChrome.notify(context, R.string.watch_party_ended)
                }
            },
        )
    }

    fun showGuestLobby(
        activity: FragmentActivity,
        token: String,
        onReady: OnPlaybackReady,
    ) {
        val chatLines = ArrayDeque<String>()
        val view = inflateLobby(activity)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.watch_together_pro_lobby_title)
            .setView(view.root)
            .setCancelable(true)
            .setNegativeButton(R.string.watch_party_leave_action, null)
            .create()

        bindChatControls(activity, view, token, chatLines) {
            refreshChat(view.chat, chatLines)
        }
        bindReactions(activity, view.reactions, token)

        dialog.setOnShowListener {
            val leaveBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            leaveBtn?.setOnClickListener {
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { WatchPartyRepository.leaveMember(activity, token) }
                        WatchPartySync.stop()
                    }
                    WatchPartySession.clear()
                    dialog.dismiss()
                    ExpDialogChrome.notify(activity, R.string.watch_party_left)
                }
            }
            leaveBtn?.requestFocus()
        }
        dialog.setOnCancelListener {
            activity.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { WatchPartyRepository.leaveMember(activity, token) }
                    WatchPartySync.stop()
                }
                WatchPartySession.clear()
            }
        }
        dialog.setOnDismissListener {
            activity.lifecycleScope.launch {
                WatchPartySync.stopLobbyOnly()
            }
        }
        dialog.show()

        WatchPartySync.startLobby(
            context = activity,
            token = token,
            listener = { lobby, messages ->
                activity.lifecycleScope.launch(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@launch
                    applyLobbySnapshot(view, lobby, isHost = false, token = token, owner = activity)
                    appendMessages(activity, chatLines, messages, view.chat)
                    if (!lobby.party.inLobby) {
                        WatchPartySession.markPlaybackStarted()
                        dialog.dismiss()
                        onReady.openPlayer(token, lobby.party)
                    }
                }
            },
            onEnded = {
                activity.lifecycleScope.launch(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@launch
                    dialog.dismiss()
                    WatchPartySession.clear()
                    ExpDialogChrome.notify(activity, R.string.watch_party_ended)
                }
            },
        )
    }

    /** In-player chat / reacts panel (after lobby). */
    fun showChatPanel(fragment: Fragment) {
        val context = fragment.context ?: return
        val token = WatchPartySession.token ?: return
        val chatLines = ArrayDeque<String>()
        val view = inflateLobby(context)
        view.status.setText(R.string.watch_party_chat_title)
        view.server.visibility = View.GONE
        view.members.visibility = View.GONE
        view.kickRow.visibility = View.GONE
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.watch_party_chat_title)
            .setView(view.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        bindChatControls(fragment, view, token, chatLines) {
            refreshChat(view.chat, chatLines)
        }
        bindReactions(fragment, view.reactions, token)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
        }
        dialog.setOnDismissListener {
            // CC-028: only stop chat job — leave lobby polling alone if still open.
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                WatchPartySync.stopChatOnly()
            }
        }
        dialog.show()
        WatchPartySync.startChat(
            context = context,
            token = token,
            listener = { _, messages ->
                fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    if (!fragment.isAdded || !dialog.isShowing) return@launch
                    appendMessages(context, chatLines, messages, view.chat)
                }
            },
            onEnded = {
                fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    if (!fragment.isAdded) return@launch
                    dialog.dismiss()
                }
            },
        )
    }

    private fun startHostPlayback(
        fragment: Fragment,
        dialog: AlertDialog,
        token: String,
        serverSource: ServerSource?,
    ) {
        val context = fragment.context ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val server = serverSource?.currentServer()
            val result = withContext(Dispatchers.IO) {
                runCatching { WatchPartyRepository.startParty(context, token, server) }
            }
            if (!fragment.isAdded) return@launch
            result.onSuccess {
                WatchPartySession.markPlaybackStarted()
                dialog.dismiss()
                serverSource?.resumeLocalPlayback()
                ExpDialogChrome.notify(context, R.string.watch_party_playback_started)
                (fragment as? WatchPartyUi.WatchPartyHostAware)?.onWatchPartyHostStarted()
                if (server != null) {
                    serverSource.onServerPicked(server)
                }
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    context,
                    context.getString(
                        R.string.watch_party_start_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    private fun pickServer(
        fragment: Fragment,
        serverSource: ServerSource,
        serverLabel: TextView,
    ) {
        val context = fragment.context ?: return
        val servers = serverSource.servers()
        if (servers.isEmpty()) {
            ExpDialogChrome.notify(context, R.string.watch_party_no_servers)
            return
        }
        val labels = servers.map { it.name }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.watch_party_pick_server_title)
            .setItems(labels) { _, which ->
                val picked = servers.getOrNull(which) ?: return@setItems
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    val partyId = WatchPartySession.partyId
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            if (partyId != null) {
                                WatchPartyRepository.pickServer(context, partyId, picked)
                            }
                        }
                    }
                    if (!fragment.isAdded) return@launch
                    result.onSuccess {
                        serverSource.onServerPicked(picked)
                        serverLabel.text = context.getString(
                            R.string.watch_party_lobby_server_label,
                            picked.name,
                        )
                        ExpDialogChrome.notify(context, R.string.watch_party_server_picked)
                    }.onFailure { error ->
                        ExpDialogChrome.notify(
                            context,
                            context.getString(
                                R.string.watch_party_server_pick_failed,
                                error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private data class LobbyViews(
        val root: View,
        val status: TextView,
        val code: TextView,
        val syncHealth: TextView,
        val server: TextView,
        val members: TextView,
        val kickRow: LinearLayout,
        val chat: TextView,
        val reactions: LinearLayout,
        val input: EditText,
        val send: Button,
    )

    private fun inflateLobby(context: Context): LobbyViews {
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_watch_party_lobby, null)
        return LobbyViews(
            root = root,
            status = root.findViewById(R.id.tv_watch_party_lobby_status),
            code = root.findViewById(R.id.tv_watch_party_lobby_code),
            syncHealth = root.findViewById(R.id.tv_watch_party_lobby_sync),
            server = root.findViewById(R.id.tv_watch_party_lobby_server),
            members = root.findViewById(R.id.tv_watch_party_lobby_members),
            kickRow = root.findViewById(R.id.ll_watch_party_kick_row),
            chat = root.findViewById(R.id.tv_watch_party_lobby_chat),
            reactions = root.findViewById(R.id.ll_watch_party_reactions),
            input = root.findViewById(R.id.et_watch_party_chat),
            send = root.findViewById(R.id.btn_watch_party_chat_send),
        )
    }

    private fun applyLobbySnapshot(
        view: LobbyViews,
        lobby: WatchPartyLobbyPayload,
        isHost: Boolean,
        token: String,
        owner: Any,
    ) {
        val ctx = view.root.context
        WatchPartySession.updateFromPayload(lobby.party)
        view.status.text = if (lobby.party.inLobby) {
            if (isHost) ctx.getString(R.string.watch_party_lobby_host_waiting)
            else ctx.getString(R.string.watch_party_lobby_guest_waiting)
        } else {
            ctx.getString(R.string.watch_party_lobby_starting)
        }
        val code = lobby.party.shortCode?.takeIf { it.isNotBlank() }
            ?: WatchPartySession.shortCode
        if (!code.isNullOrBlank()) {
            view.code.visibility = View.VISIBLE
            view.code.text = ctx.getString(R.string.watch_party_code_label, code)
        }
        val mode = if (WatchTogetherPro.isTvScreenMode(lobby.party.displayMode)) {
            ctx.getString(R.string.watch_party_mode_tv_screen)
        } else {
            ctx.getString(R.string.watch_party_mode_sync_all)
        }
        view.syncHealth.visibility = View.VISIBLE
        view.syncHealth.text = ctx.getString(
            R.string.watch_party_lobby_sync_line,
            mode,
            lobby.members.size,
            lobby.party.syncRevision,
        )
        val serverName = lobby.party.serverName?.takeIf { it.isNotBlank() }
            ?: ctx.getString(R.string.watch_party_lobby_server_unset)
        view.server.text = ctx.getString(R.string.watch_party_lobby_server_label, serverName)
        view.server.isClickable = isHost && lobby.party.inLobby
        view.server.isFocusable = view.server.isClickable
        val memberNames = lobby.members.joinToString(", ") { member ->
            if (member.isHost) {
                ctx.getString(R.string.watch_party_member_host_label, member.displayName)
            } else {
                member.displayName
            }
        }.ifBlank { ctx.getString(R.string.watch_party_lobby_members_empty) }
        view.members.text = ctx.getString(R.string.watch_party_lobby_members_label, memberNames)
        bindKickControls(view, lobby, isHost, token, owner)
    }

    private fun bindKickControls(
        view: LobbyViews,
        lobby: WatchPartyLobbyPayload,
        isHost: Boolean,
        token: String,
        owner: Any,
    ) {
        view.kickRow.removeAllViews()
        if (!isHost) {
            view.kickRow.visibility = View.GONE
            return
        }
        val guests = lobby.members.filter { !it.isHost && !it.memberKey.isNullOrBlank() }
        if (guests.isEmpty()) {
            view.kickRow.visibility = View.GONE
            return
        }
        view.kickRow.visibility = View.VISIBLE
        val scopeOwner = when (owner) {
            is Fragment -> owner.viewLifecycleOwner.lifecycleScope
            is FragmentActivity -> owner.lifecycleScope
            else -> return
        }
        val context = view.root.context
        for (member in guests) {
            val key = member.memberKey ?: continue
            val btn = Button(context).apply {
                text = context.getString(R.string.watch_party_kick_action, member.displayName)
                setOnClickListener {
                    scopeOwner.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { WatchPartyRepository.kickMember(context, token, key) }
                        }
                        result.onSuccess { updated ->
                            applyLobbySnapshot(view, updated, isHost = true, token = token, owner = owner)
                            ExpDialogChrome.notify(context, R.string.watch_party_kick_done)
                        }.onFailure { error ->
                            ExpDialogChrome.notify(
                                context,
                                context.getString(
                                    R.string.watch_party_kick_failed,
                                    error.message ?: error.javaClass.simpleName,
                                ),
                            )
                        }
                    }
                }
            }
            view.kickRow.addView(btn)
        }
    }

    private fun bindChatControls(
        owner: Any,
        view: LobbyViews,
        token: String,
        chatLines: ArrayDeque<String>,
        onSent: () -> Unit,
    ) {
        val scopeOwner = when (owner) {
            is Fragment -> owner.viewLifecycleOwner.lifecycleScope
            is FragmentActivity -> owner.lifecycleScope
            else -> return
        }
        val contextProvider: () -> Context? = {
            when (owner) {
                is Fragment -> owner.context
                is FragmentActivity -> owner
                else -> null
            }
        }
        view.send.setOnClickListener {
            val body = WatchPartyChat.sanitizeChatBody(view.input.text?.toString()) ?: return@setOnClickListener
            val ctx = contextProvider() ?: return@setOnClickListener
            scopeOwner.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        WatchPartyRepository.postMessage(
                            ctx,
                            token,
                            WatchPartyChat.KIND_CHAT,
                            body,
                        )
                    }
                }
                result.onSuccess { message ->
                    view.input.text = null
                    chatLines.addLast(WatchPartyChat.formatMessageLine(message))
                    while (chatLines.size > 40) chatLines.removeFirst()
                    onSent()
                }.onFailure { error ->
                    ExpDialogChrome.notify(
                        ctx,
                        ctx.getString(
                            R.string.watch_party_chat_failed,
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
            }
        }
    }

    private fun bindReactions(owner: Any, container: LinearLayout, token: String) {
        container.removeAllViews()
        val scopeOwner = when (owner) {
            is Fragment -> owner.viewLifecycleOwner.lifecycleScope
            is FragmentActivity -> owner.lifecycleScope
            else -> return
        }
        val context = container.context
        for (emoji in WatchPartyChat.REACTIONS) {
            val btn = Button(context).apply {
                text = emoji
                textSize = 18f
                minimumWidth = 0
                minWidth = 0
                setPadding(12, 8, 12, 8)
                setOnClickListener {
                    scopeOwner.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                WatchPartyRepository.postMessage(
                                    context,
                                    token,
                                    WatchPartyChat.KIND_REACT,
                                    emoji,
                                )
                            }
                        }
                        result.onFailure { error ->
                            ExpDialogChrome.notify(
                                context,
                                context.getString(
                                    R.string.watch_party_chat_failed,
                                    error.message ?: error.javaClass.simpleName,
                                ),
                            )
                        }
                    }
                }
            }
            container.addView(btn)
        }
    }

    private fun appendMessages(
        context: Context,
        chatLines: ArrayDeque<String>,
        messages: List<WatchPartyMessagePayload>,
        chatView: TextView,
    ) {
        for (message in messages) {
            val line = WatchPartyChat.formatMessageLine(message)
            chatLines.addLast(line)
            while (chatLines.size > 40) chatLines.removeFirst()
            if (message.kind == WatchPartyChat.KIND_REACT) {
                Toast.makeText(context, line, Toast.LENGTH_SHORT).show()
            }
        }
        if (messages.isNotEmpty()) {
            refreshChat(chatView, chatLines)
        }
    }

    private fun refreshChat(chatView: TextView, chatLines: ArrayDeque<String>) {
        chatView.text = if (chatLines.isEmpty()) {
            chatView.context.getString(R.string.watch_party_chat_empty)
        } else {
            chatLines.joinToString("\n")
        }
    }
}
