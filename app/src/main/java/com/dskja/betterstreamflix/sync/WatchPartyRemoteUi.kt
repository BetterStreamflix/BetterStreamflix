package com.dskja.betterstreamflix.sync

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone remote for Watch Together Pro when display_mode is tv_screen.
 * TV stays the screen; phone scrubber / play / chat only (no stream relay).
 */
object WatchPartyRemoteUi {

    @Volatile
    private var latestPayload: WatchPartyPayload? = null

    private var openDialog: AlertDialog? = null
    private var nowPlayingView: TextView? = null
    private var healthView: TextView? = null
    private var playButton: Button? = null
    private var panelToken: String? = null
    private var panelOwner: Any? = null

    fun showRemotePanel(owner: Any) {
        val fragment = owner as? Fragment
        val activity = owner as? FragmentActivity
        val context: Context = fragment?.context ?: activity ?: return
        val token = WatchPartySession.token ?: return
        ensureRemotePolling(context, token)
        val payload = latestPayload
        if (payload == null) {
            // CC-068: await first poll before rendering (do not force reopen).
            ExpDialogChrome.notify(context, R.string.watch_party_remote_loading)
            launchIo(owner) {
                var waited = 0
                while (latestPayload == null && waited < 8_000) {
                    delay(250)
                    waited += 250
                }
                val ready = latestPayload
                if (ready == null) {
                    ExpDialogChrome.notify(context, R.string.watch_party_remote_loading)
                    return@launchIo
                }
                if (!isOwnerAlive(owner)) return@launchIo
                renderPanel(owner, context, token, ready)
            }
            return
        }
        renderPanel(owner, context, token, payload)
    }

    fun ensureRemotePolling(context: Context, token: String) {
        WatchPartySync.startGuest(
            context = context,
            token = token,
            listener = { payload ->
                latestPayload = payload
                WatchPartySession.updateFromPayload(payload)
                refreshOpenPanel(payload)
            },
            onEnded = {
                WatchPartySession.clear()
                latestPayload = null
                clearPanelRefs()
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    WatchPartySync.stop()
                }
            },
        )
    }

    fun clear() {
        latestPayload = null
        clearPanelRefs()
    }

    private fun clearPanelRefs() {
        openDialog = null
        nowPlayingView = null
        healthView = null
        playButton = null
        panelToken = null
        panelOwner = null
    }

    private fun refreshOpenPanel(payload: WatchPartyPayload) {
        val dialog = openDialog ?: return
        if (!dialog.isShowing) return
        val context = nowPlayingView?.context ?: return
        val owner = panelOwner
        launchIo(owner ?: return) {
            nowPlayingView?.text = context.getString(
                R.string.watch_party_remote_now_playing,
                payload.title.ifBlank { context.getString(R.string.watch_party_menu_title) },
                WatchTogetherPro.formatPosition(payload.positionMs),
            )
            val health = WatchPartySyncHealth.fromPayload(
                localPositionMs = payload.positionMs,
                payload = payload,
            )
            healthView?.text = context.getString(
                R.string.watch_party_sync_health_label,
                healthLabel(context, health.level),
                health.driftMs,
            )
            playButton?.text = context.getString(
                if (payload.playing) R.string.watch_party_remote_pause
                else R.string.watch_party_remote_play,
            )
        }
    }

    private fun renderPanel(
        owner: Any,
        context: Context,
        token: String,
        payload: WatchPartyPayload,
    ) {
        val scroll = ScrollView(context)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }
        scroll.addView(list)

        val health = WatchPartySyncHealth.fromPayload(
            localPositionMs = payload.positionMs,
            payload = payload,
        )
        val nowPlaying = TextView(context).apply {
            text = context.getString(
                R.string.watch_party_remote_now_playing,
                payload.title.ifBlank { context.getString(R.string.watch_party_menu_title) },
                WatchTogetherPro.formatPosition(payload.positionMs),
            )
            textSize = 16f
            setPadding(0, 0, 0, 8)
        }
        list.addView(nowPlaying)
        val healthTv = TextView(context).apply {
            text = context.getString(
                R.string.watch_party_sync_health_label,
                healthLabel(context, health.level),
                health.driftMs,
            )
            textSize = 13f
            setPadding(0, 0, 0, 12)
        }
        list.addView(healthTv)

        list.addView(sectionLabel(context, R.string.watch_party_remote_scrubber_title))
        list.addView(
            scrubberRow(context) { deltaMs ->
                val base = latestPayload?.positionMs ?: payload.positionMs
                val duration = null // remote has no local duration; server clamps floor
                val target = WatchTogetherPro.relativeSeek(base, deltaMs, duration)
                mutate(owner, context, token) {
                    WatchPartyRepository.sidekickSeek(context, it, target)
                }
            },
        )
        list.addView(
            Button(context).apply {
                text = context.getString(R.string.watch_party_remote_scrub_to_action)
                setOnClickListener {
                    val current = latestPayload?.positionMs ?: payload.positionMs
                    promptAbsoluteSeek(owner, context, token, current)
                }
            },
        )

        val playBtn = Button(context).apply {
            text = context.getString(
                if (payload.playing) R.string.watch_party_remote_pause
                else R.string.watch_party_remote_play,
            )
            setOnClickListener {
                val playing = latestPayload?.playing ?: payload.playing
                mutate(owner, context, token) {
                    WatchPartyRepository.sidekickPlay(context, it, !playing)
                }
            }
        }
        list.addView(playBtn)

        list.addView(sectionLabel(context, R.string.watch_party_chat_title))
        val chatView = TextView(context).apply {
            text = context.getString(R.string.watch_party_chat_empty)
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        list.addView(chatView)
        refreshChat(owner, context, token, chatView)
        val chatInput = EditText(context).apply {
            hint = context.getString(R.string.watch_party_chat_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
        }
        list.addView(chatInput)
        list.addView(
            Button(context).apply {
                text = context.getString(R.string.watch_party_chat_send)
                setOnClickListener {
                    val body = WatchPartyChat.sanitizeChatBody(chatInput.text?.toString())
                        ?: return@setOnClickListener
                    launchIo(owner) {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                WatchPartyRepository.postMessage(
                                    context,
                                    token,
                                    WatchPartyChat.KIND_CHAT,
                                    body,
                                )
                            }
                        }
                        result.onSuccess {
                            chatInput.setText("")
                            refreshChat(owner, context, token, chatView)
                        }.onFailure { error ->
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
            },
        )
        list.addView(reactionRow(owner, context, token, chatView))

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.watch_party_remote_title)
            .setView(scroll)
            .setNegativeButton(R.string.watch_party_leave_action) { _, _ ->
                leaveRemote(owner)
            }
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        }
        dialog.setOnDismissListener {
            if (openDialog === dialog) {
                clearPanelRefs()
            }
        }
        openDialog = dialog
        nowPlayingView = nowPlaying
        healthView = healthTv
        playButton = playBtn
        panelToken = token
        panelOwner = owner
        dialog.show()
    }

    private fun leaveRemote(owner: Any) {
        launchIo(owner) {
            val token = WatchPartySession.token
            val ctx = contextOf(owner)?.applicationContext
            withContext(Dispatchers.IO) {
                if (ctx != null && token != null) {
                    runCatching { WatchPartyRepository.leaveMember(ctx, token) }
                }
                WatchPartySync.stop()
            }
            WatchPartySession.clear()
            clear()
            val notifyCtx = contextOf(owner) ?: return@launchIo
            ExpDialogChrome.notify(notifyCtx, R.string.watch_party_left)
        }
    }

    private fun promptAbsoluteSeek(
        owner: Any,
        context: Context,
        token: String,
        currentMs: Long,
    ) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.watch_party_remote_scrub_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(WatchTogetherPro.formatPosition(currentMs))
            setSelection(text?.length ?: 0)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.watch_party_remote_scrub_to_action)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val target = WatchTogetherPro.parsePositionInput(input.text?.toString())
                if (target == null) {
                    ExpDialogChrome.notify(context, R.string.watch_party_remote_scrub_invalid)
                    return@setPositiveButton
                }
                mutate(owner, context, token) {
                    WatchPartyRepository.sidekickSeek(context, it, target)
                }
            }
            .show()
    }

    private fun mutate(
        owner: Any,
        context: Context,
        token: String,
        block: suspend (String) -> WatchPartyPayload,
    ) {
        launchIo(owner) {
            val result = withContext(Dispatchers.IO) {
                runCatching { block(token) }
            }
            result.onSuccess { payload ->
                latestPayload = payload
                WatchPartySession.updateFromPayload(payload)
                refreshOpenPanel(payload)
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    context,
                    context.getString(
                        R.string.watch_party_remote_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    private fun refreshChat(
        owner: Any,
        context: Context,
        token: String,
        chatView: TextView,
    ) {
        launchIo(owner) {
            val messages = withContext(Dispatchers.IO) {
                runCatching { WatchPartyRepository.listMessages(context, token) }
                    .getOrDefault(emptyList())
            }
            chatView.text = if (messages.isEmpty()) {
                context.getString(R.string.watch_party_chat_empty)
            } else {
                messages.takeLast(30).joinToString("\n") { WatchPartyChat.formatMessageLine(it) }
            }
        }
    }

    private fun reactionRow(
        owner: Any,
        context: Context,
        token: String,
        chatView: TextView,
    ): HorizontalScrollView {
        val scroll = HorizontalScrollView(context)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        WatchPartyChat.REACTIONS.forEach { emoji ->
            row.addView(
                Button(context).apply {
                    text = emoji
                    setOnClickListener {
                        launchIo(owner) {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    WatchPartyRepository.postMessage(
                                        context,
                                        token,
                                        WatchPartyChat.KIND_REACT,
                                        emoji,
                                    )
                                }
                            }
                            refreshChat(owner, context, token, chatView)
                        }
                    }
                },
            )
        }
        scroll.addView(row)
        return scroll
    }

    private fun scrubberRow(context: Context, onDelta: (Long) -> Unit): HorizontalScrollView {
        val scroll = HorizontalScrollView(context)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(labelRes: Int, delta: Long) {
            row.addView(
                Button(context).apply {
                    text = context.getString(labelRes)
                    setOnClickListener { onDelta(delta) }
                },
            )
        }
        add(R.string.watch_party_remote_seek_back_30, -WatchTogetherPro.SEEK_STEP_LONG_MS)
        add(R.string.watch_party_remote_seek_back_10, -WatchTogetherPro.SEEK_STEP_SHORT_MS)
        add(R.string.watch_party_remote_seek_fwd_10, WatchTogetherPro.SEEK_STEP_SHORT_MS)
        add(R.string.watch_party_remote_seek_fwd_30, WatchTogetherPro.SEEK_STEP_LONG_MS)
        scroll.addView(row)
        return scroll
    }

    private fun sectionLabel(context: Context, titleRes: Int): TextView =
        TextView(context).apply {
            text = context.getString(titleRes)
            textSize = 14f
            setPadding(0, 18, 0, 8)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.START
        }

    private fun healthLabel(context: Context, level: WatchPartySyncHealth.Level): String =
        when (level) {
            WatchPartySyncHealth.Level.GOOD ->
                context.getString(R.string.watch_party_sync_good)
            WatchPartySyncHealth.Level.LAGGING ->
                context.getString(R.string.watch_party_sync_lagging)
            WatchPartySyncHealth.Level.LOST ->
                context.getString(R.string.watch_party_sync_lost)
            WatchPartySyncHealth.Level.STALE ->
                context.getString(R.string.watch_party_sync_stale)
            WatchPartySyncHealth.Level.UNKNOWN ->
                context.getString(R.string.watch_party_sync_unknown)
        }

    private fun contextOf(owner: Any): Context? = when (owner) {
        is Fragment -> owner.context
        is FragmentActivity -> owner
        else -> null
    }

    private fun isOwnerAlive(owner: Any): Boolean = when (owner) {
        is Fragment -> owner.isAdded
        is FragmentActivity -> !owner.isFinishing && !owner.isDestroyed
        else -> false
    }

    private fun launchIo(owner: Any, block: suspend () -> Unit) {
        when (owner) {
            is Fragment -> owner.viewLifecycleOwner.lifecycleScope.launch { block() }
            is FragmentActivity -> owner.lifecycleScope.launch { block() }
        }
    }
}
