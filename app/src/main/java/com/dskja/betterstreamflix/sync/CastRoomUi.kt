package com.dskja.betterstreamflix.sync

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import com.dskja.betterstreamflix.utils.QrUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

object CastRoomUi {
    private val promptedPendingTokens = mutableSetOf<String>()

    @Volatile
    private var confirmInFlight = false

    @Volatile
    private var hostConsumerRef: WeakReference<HostConsumer>? = null

    @Volatile
    private var latestPayload: CastRoomPayload? = null

    interface HostConsumer {
        fun onCastRoomPlayItem(item: CastRoomItemPayload, preferredServerName: String?)
        fun onCastRoomSetPlaying(playing: Boolean)
        fun onCastRoomSeek(positionMs: Long)
        fun onCastRoomSidekickText(text: String)
        fun onCastRoomEnded()
    }

    fun registerHostConsumer(consumer: HostConsumer?) {
        hostConsumerRef = consumer?.let { WeakReference(it) }
    }

    fun latestPayload(): CastRoomPayload? = latestPayload

    fun hostAndShowQr(fragment: Fragment) {
        val context = fragment.context ?: return
        if (!SupabaseProvider.isConfigured) {
            ExpDialogChrome.notify(context, R.string.cast_room_needs_supabase)
            return
        }
        if (CloudSyncManager.currentUserId() == null) {
            ExpDialogChrome.notify(context, R.string.cast_room_needs_sign_in)
            return
        }
        if (!DownloadConnectivityMonitor.isOnline(context)) {
            ExpDialogChrome.notify(context, R.string.cast_room_offline)
            return
        }
        if (CastRoomSession.isHost()) {
            showHostQr(fragment, CastRoomSession.token!!, CastRoomSession.shortCode)
            return
        }
        if (CastRoomSession.isController()) {
            ExpDialogChrome.notify(context, R.string.cast_room_already_controller)
            return
        }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CastRoomRepository.createRoom(context) }
            }
            if (!fragment.isAdded) return@launch
            val ctx = fragment.context ?: return@launch
            result.onSuccess { created ->
                CastRoomSession.beginHost(created.token, created.roomId, created.shortCode)
                startHostPolling(ctx.applicationContext, created.token)
                showHostQr(fragment, created.token, created.shortCode)
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    ctx,
                    ctx.getString(
                        R.string.cast_room_host_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    fun showHostMenu(fragment: Fragment) {
        val context = fragment.context ?: return
        if (!CastRoomSession.isHost()) {
            hostAndShowQr(fragment)
            return
        }
        val options = arrayOf(
            context.getString(R.string.cast_room_show_qr_action),
            context.getString(R.string.cast_room_share_action),
            context.getString(R.string.cast_room_end_action),
        )
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.cast_room_menu_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showHostQr(fragment, CastRoomSession.token!!, CastRoomSession.shortCode)
                    1 -> shareLink(context, CastRoomLinks.buildUri(CastRoomSession.token!!))
                    2 -> endHostRoom(fragment)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showHostQr(fragment: Fragment, token: String, shortCode: String?) {
        val context = fragment.context ?: return
        val link = CastRoomLinks.buildUri(token)
        val code = shortCode?.takeIf { it.isNotBlank() } ?: "------"
        val qr = runCatching { QrUtils.generate(link, 640) }.getOrNull()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        if (qr != null) {
            container.addView(
                ImageView(context).apply {
                    setImageBitmap(qr)
                    adjustViewBounds = true
                    contentDescription = context.getString(R.string.cast_room_qr_cd)
                    layoutParams = LinearLayout.LayoutParams(560, 560).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                },
            )
        }
        container.addView(
            TextView(context).apply {
                text = context.getString(R.string.cast_room_code_label, code)
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 8)
            },
        )
        container.addView(
            TextView(context).apply {
                text = context.getString(R.string.cast_room_host_qr_message)
                textSize = 14f
                gravity = Gravity.CENTER
            },
        )

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.cast_room_host_qr_title)
            .setView(container)
            .setPositiveButton(R.string.cast_room_share_action) { _, _ ->
                shareLink(context, link)
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    fun endHostRoom(fragment: Fragment) {
        val ctx = fragment.context ?: return
        val roomId = CastRoomSession.roomId
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { CastRoomRepository.endRoom(ctx, roomId) }
                CastRoomSync.stop()
            }
            CastRoomSession.clear()
            latestPayload = null
            if (fragment.isAdded) {
                ExpDialogChrome.notify(ctx, R.string.cast_room_ended)
            }
        }
    }

    fun leaveController(fragment: Fragment) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            CastRoomSync.stop()
            CastRoomSession.clear()
            latestPayload = null
            fragment.context?.let {
                ExpDialogChrome.notify(it, R.string.cast_room_left)
            }
        }
    }

    fun handleIncomingToken(activity: FragmentActivity, token: String) {
        val appContext = activity.applicationContext
        if (!CastRoomLinks.isValidToken(token)) {
            ExpDialogChrome.notify(activity, R.string.cast_room_invalid_link)
            return
        }
        if (ProfileUnlockGate.requiresGateForActive()) {
            CastRoomPendingJoin.enqueue(appContext, token)
            return
        }
        if (!SupabaseProvider.isConfigured) {
            CastRoomPendingJoin.enqueue(appContext, token)
            ExpDialogChrome.notify(activity, R.string.cast_room_join_needs_supabase)
            return
        }
        if (!DownloadConnectivityMonitor.isOnline(appContext)) {
            CastRoomPendingJoin.enqueue(appContext, token)
            ExpDialogChrome.notify(activity, R.string.cast_room_join_queued_offline)
            return
        }
        promptJoin(activity, token)
    }

    fun handleIncomingCode(activity: FragmentActivity, code: String) {
        val cleaned = CastRoomLinks.normalizeShortCode(code)
        if (cleaned == null) {
            ExpDialogChrome.notify(activity, R.string.cast_room_invalid_code)
            return
        }
        if (ProfileUnlockGate.requiresGateForActive()) {
            ExpDialogChrome.notify(activity, R.string.cast_room_join_needs_unlock)
            return
        }
        if (!SupabaseProvider.isConfigured) {
            ExpDialogChrome.notify(activity, R.string.cast_room_join_needs_supabase)
            return
        }
        if (!DownloadConnectivityMonitor.isOnline(activity)) {
            ExpDialogChrome.notify(activity, R.string.cast_room_offline)
            return
        }
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CastRoomRepository.fetchByShortCode(activity, cleaned) }
            }
            result.onSuccess { payload ->
                val token = payload.shareToken ?: return@onSuccess
                promptJoin(activity, token, payload)
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    activity,
                    activity.getString(
                        R.string.cast_room_join_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    fun tryFlushPending(activity: FragmentActivity) {
        if (ProfileUnlockGate.requiresGateForActive()) return
        if (!SupabaseProvider.isConfigured) return
        if (!DownloadConnectivityMonitor.isOnline(activity)) return
        val pending = CastRoomPendingJoin.peek(activity)
        val next = pending.firstOrNull { it !in promptedPendingTokens } ?: return
        promptedPendingTokens.add(next)
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { CastRoomRepository.flushStalePending(activity) }
            }
            if (CastRoomPendingJoin.peek(activity).contains(next)) {
                promptJoin(activity, next)
            } else {
                promptedPendingTokens.remove(next)
            }
        }
    }

    fun handleScannedValue(activity: FragmentActivity, raw: String) {
        val token = CastRoomLinks.parseToken(raw)
        if (token != null) {
            handleIncomingToken(activity, token)
            return
        }
        val code = CastRoomLinks.normalizeShortCode(raw)
        if (code != null) {
            handleIncomingCode(activity, code)
            return
        }
        ExpDialogChrome.notify(activity, R.string.cast_room_invalid_link)
    }

    fun showControllerPanel(fragment: Fragment) {
        val context = fragment.context ?: return
        if (!CastRoomSession.isController()) {
            ExpDialogChrome.notify(context, R.string.cast_room_not_joined)
            return
        }
        val token = CastRoomSession.token ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CastRoomRepository.fetchByToken(context, token) }
            }
            if (!fragment.isAdded) return@launch
            result.onSuccess { payload ->
                latestPayload = payload
                renderControllerPanel(fragment, payload)
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    context,
                    context.getString(
                        R.string.cast_room_join_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    fun videoTypeFromEpisode(episode: com.dskja.betterstreamflix.models.Episode): Video.Type.Episode =
        Video.Type.Episode(
            id = episode.id,
            number = episode.number,
            title = episode.title,
            poster = episode.poster,
            overview = episode.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = episode.tvShow?.id ?: "",
                title = episode.tvShow?.title ?: "",
                poster = episode.tvShow?.poster,
                banner = episode.tvShow?.banner,
                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                imdbId = episode.tvShow?.imdbId,
            ),
            season = Video.Type.Episode.Season(
                number = episode.season?.number ?: 0,
                title = episode.season?.title,
            ),
        )

    fun offerAddFromDetail(fragment: Fragment, videoType: Video.Type) {
        val context = fragment.context ?: return
        if (!CastRoomSession.isController() && !CastRoomSession.isHost()) {
            return
        }
        val token = CastRoomSession.token ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CastRoomRepository.addItem(context, token, videoType) }
            }
            if (!fragment.isAdded) return@launch
            result.onSuccess { payload ->
                latestPayload = payload
                ExpDialogChrome.notify(context, R.string.cast_room_item_added)
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    context,
                    context.getString(
                        R.string.cast_room_add_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    fun maybeAugmentShareClick(
        fragment: Fragment,
        videoType: Video.Type,
        defaultShare: () -> Unit,
    ) {
        if (!CastRoomSession.isActive()) {
            defaultShare()
            return
        }
        val context = fragment.context ?: return
        val options = arrayOf(
            context.getString(R.string.detail_share),
            context.getString(R.string.cast_room_add_action),
        )
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.cast_room_menu_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> defaultShare()
                    1 -> offerAddFromDetail(fragment, videoType)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun applyHostUpdate(context: Context, payload: CastRoomPayload) {
        latestPayload = payload
        if (!CastRoomSession.isHost()) return
        val consumer = hostConsumerRef?.get()
        applySidekickCommands(context.applicationContext, payload, consumer)

        if (payload.revision == CastRoomSession.lastAppliedRevision &&
            CastRoomSession.lastAppliedPlaying == payload.playing
        ) {
            return
        }
        val item = CastRoomQueue.nowPlaying(payload)
        val key = CastRoomQueue.playingKey(item, payload.nowPlayingIndex)
        if (key != null && key != CastRoomSession.lastAppliedPlayingKey && payload.playing) {
            CastRoomSession.lastAppliedPlayingKey = key
            CastRoomSession.lastAppliedRevision = payload.revision
            CastRoomSession.lastAppliedPlaying = payload.playing
            if (item != null) {
                consumer?.onCastRoomPlayItem(item, item.preferredServerName)
            }
            return
        }
        if (CastRoomSession.lastAppliedPlaying != payload.playing) {
            CastRoomSession.lastAppliedPlaying = payload.playing
            consumer?.onCastRoomSetPlaying(payload.playing)
        }
        CastRoomSession.lastAppliedRevision = payload.revision
    }

    private fun applySidekickCommands(
        appContext: Context,
        payload: CastRoomPayload,
        consumer: HostConsumer?,
    ) {
        if (TvSidekick.shouldApplySeek(
                payload.sidekickSeekSeq,
                CastRoomSession.lastAppliedSeekSeq,
                payload.sidekickSeekMs,
            )
        ) {
            CastRoomSession.lastAppliedSeekSeq = payload.sidekickSeekSeq
            consumer?.onCastRoomSeek(payload.sidekickSeekMs!!.coerceAtLeast(0L))
        }
        if (TvSidekick.shouldApplyKeyboard(
                payload.sidekickTextSeq,
                CastRoomSession.lastAppliedTextSeq,
                payload.sidekickText,
            )
        ) {
            CastRoomSession.lastAppliedTextSeq = payload.sidekickTextSeq
            val text = TvSidekick.sanitizeKeyboard(payload.sidekickText) ?: return
            consumer?.onCastRoomSidekickText(text)
            val token = CastRoomSession.token ?: return
            CoroutineScopeIo.launch {
                runCatching { CastRoomRepository.ackSidekickText(appContext, token) }
            }
        }
    }

    fun onPlaybackEndedAdvance(context: Context) {
        if (!CastRoomSession.isHost()) return
        val token = CastRoomSession.token ?: return
        CoroutineScopeIo.launch {
            runCatching { CastRoomRepository.skip(context.applicationContext, token) }
                .onFailure { LogW("Cast Room auto-advance failed", it) }
        }
    }

    private fun promptJoin(
        activity: FragmentActivity,
        token: String,
        prefetched: CastRoomPayload? = null,
    ) {
        if (confirmInFlight) return
        confirmInFlight = true
        activity.lifecycleScope.launch {
            val result = if (prefetched != null) {
                Result.success(prefetched)
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { CastRoomRepository.fetchByToken(activity, token) }
                }
            }
            confirmInFlight = false
            if (activity.isFinishing || activity.isDestroyed) return@launch
            result.onFailure { error ->
                CastRoomPendingJoin.acknowledge(activity, token)
                promptedPendingTokens.remove(token)
                ExpDialogChrome.notify(
                    activity,
                    activity.getString(
                        R.string.cast_room_join_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
                return@launch
            }
            val payload = result.getOrNull() ?: return@launch
            val count = payload.items.size
            val detail = buildString {
                append(activity.getString(R.string.cast_room_join_privacy_note))
                append('\n')
                append(
                    activity.getString(
                        R.string.cast_room_join_queue_count,
                        count,
                        payload.shortCode ?: "------",
                    ),
                )
            }
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(R.string.cast_room_join_confirm_title)
                .setMessage(detail)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    CastRoomPendingJoin.acknowledge(activity, token)
                    promptedPendingTokens.remove(token)
                }
                .setPositiveButton(R.string.cast_room_join_action) { _, _ ->
                    CastRoomPendingJoin.acknowledge(activity, token)
                    promptedPendingTokens.remove(token)
                    enterAsController(activity, token, payload)
                }
                .setOnCancelListener {
                    CastRoomPendingJoin.acknowledge(activity, token)
                    promptedPendingTokens.remove(token)
                }
                .show()
        }
    }

    private fun enterAsController(
        activity: FragmentActivity,
        token: String,
        payload: CastRoomPayload,
    ) {
        CastRoomSession.beginController(token, payload.id, payload.shortCode)
        latestPayload = payload
        startControllerPolling(activity.applicationContext, token)
        ExpDialogChrome.notify(activity, R.string.cast_room_joined)
        // Open companion panel immediately when possible.
        val navHost =
            activity.supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
        val fragment = navHost?.childFragmentManager?.primaryNavigationFragment
        if (fragment != null) {
            showControllerPanel(fragment)
        }
    }

    fun startHostPolling(context: Context, token: String) {
        val appContext = context.applicationContext
        CastRoomSync.startPolling(
            context = appContext,
            token = token,
            listener = { payload -> applyHostUpdate(appContext, payload) },
            onEnded = {
                CastRoomSession.clear()
                latestPayload = null
                hostConsumerRef?.get()?.onCastRoomEnded()
                CoroutineScopeIo.launch { CastRoomSync.stop() }
            },
        )
    }

    fun startControllerPolling(context: Context, token: String) {
        CastRoomSync.startPolling(
            context = context,
            token = token,
            listener = { payload -> latestPayload = payload },
            onEnded = {
                CastRoomSession.clear()
                latestPayload = null
                CoroutineScopeIo.launch { CastRoomSync.stop() }
            },
        )
    }

    private fun renderControllerPanel(fragment: Fragment, payload: CastRoomPayload) {
        val context = fragment.context ?: return
        val token = CastRoomSession.token ?: return
        val scroll = ScrollView(context)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }
        scroll.addView(list)

        val now = CastRoomQueue.nowPlaying(payload)
        list.addView(
            TextView(context).apply {
                text = if (now == null) {
                    context.getString(R.string.cast_room_queue_empty)
                } else {
                    context.getString(
                        R.string.tv_sidekick_now_playing,
                        CastRoomQueue.displayLabel(now),
                        TvSidekick.formatPosition(payload.positionMs),
                    )
                }
                textSize = 16f
                setPadding(0, 0, 0, 12)
            },
        )

        list.addView(sectionLabel(context, R.string.tv_sidekick_scrubber_title))
        list.addView(
            scrubberRow(context) { deltaMs ->
                val target = TvSidekick.relativeSeek(payload.positionMs, deltaMs)
                mutate(fragment, token) { CastRoomRepository.seek(context, it, target) }
            },
        )
        list.addView(
            Button(context).apply {
                text = context.getString(R.string.tv_sidekick_scrub_to_action)
                setOnClickListener { promptAbsoluteSeek(fragment, token, payload.positionMs) }
            },
        )

        list.addView(sectionLabel(context, R.string.tv_sidekick_keyboard_title))
        val keyboardInput = EditText(context).apply {
            hint = context.getString(R.string.tv_sidekick_keyboard_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        list.addView(keyboardInput)
        list.addView(
            Button(context).apply {
                text = context.getString(R.string.tv_sidekick_keyboard_send)
                setOnClickListener {
                    val text = keyboardInput.text?.toString().orEmpty()
                    mutate(fragment, token) {
                        CastRoomRepository.sendSidekickText(context, it, text)
                    }
                }
            },
        )

        list.addView(sectionLabel(context, R.string.tv_sidekick_chat_title))
        val chatView = TextView(context).apply {
            text = context.getString(R.string.tv_sidekick_chat_loading)
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        list.addView(chatView)
        refreshSidekickChat(fragment, token, chatView)
        val chatInput = EditText(context).apply {
            hint = context.getString(R.string.tv_sidekick_chat_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
        }
        list.addView(chatInput)
        list.addView(
            Button(context).apply {
                text = context.getString(R.string.tv_sidekick_chat_send)
                setOnClickListener {
                    val body = chatInput.text?.toString().orEmpty()
                    fragment.viewLifecycleOwner.lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                CastRoomRepository.postMessage(
                                    context,
                                    token,
                                    TvSidekick.KIND_CHAT,
                                    body,
                                )
                            }
                        }
                        if (!fragment.isAdded) return@launch
                        result.onSuccess {
                            chatInput.setText("")
                            refreshSidekickChat(fragment, token, chatView)
                        }.onFailure { error ->
                            ExpDialogChrome.notify(
                                context,
                                context.getString(
                                    R.string.cast_room_control_failed,
                                    error.message ?: error.javaClass.simpleName,
                                ),
                            )
                        }
                    }
                }
            },
        )
        list.addView(reactionRow(context, fragment, token, chatView))

        list.addView(sectionLabel(context, R.string.cast_room_companion_title))
        payload.items.forEachIndexed { index, item ->
            val row = TextView(context).apply {
                val marker = if (index == payload.nowPlayingIndex) "▶ " else "${index + 1}. "
                text = marker + CastRoomQueue.displayLabel(item)
                textSize = 15f
                setPadding(0, 18, 0, 18)
                isClickable = true
                setOnClickListener {
                    showItemActions(fragment, token, payload, index, item)
                }
            }
            list.addView(row)
        }

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.tv_sidekick_title)
            .setView(scroll)
            .setNeutralButton(R.string.cast_room_skip_action) { _, _ ->
                mutate(fragment, token) { CastRoomRepository.skip(context, it) }
            }
            .setNegativeButton(
                if (payload.playing) R.string.cast_room_pause_action else R.string.cast_room_resume_action,
            ) { _, _ ->
                mutate(fragment, token) {
                    CastRoomRepository.setPlaying(context, it, !payload.playing)
                }
            }
            .setPositiveButton(R.string.cast_room_leave_action) { _, _ ->
                leaveController(fragment)
            }
            .show()
    }

    private fun sectionLabel(context: Context, titleRes: Int): TextView =
        TextView(context).apply {
            text = context.getString(titleRes)
            textSize = 14f
            setPadding(0, 18, 0, 8)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
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
        add(R.string.tv_sidekick_seek_back_30, -TvSidekick.SEEK_STEP_LONG_MS)
        add(R.string.tv_sidekick_seek_back_10, -TvSidekick.SEEK_STEP_SHORT_MS)
        add(R.string.tv_sidekick_seek_fwd_10, TvSidekick.SEEK_STEP_SHORT_MS)
        add(R.string.tv_sidekick_seek_fwd_30, TvSidekick.SEEK_STEP_LONG_MS)
        scroll.addView(row)
        return scroll
    }

    private fun reactionRow(
        context: Context,
        fragment: Fragment,
        token: String,
        chatView: TextView,
    ): HorizontalScrollView {
        val scroll = HorizontalScrollView(context)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        TvSidekick.REACTIONS.forEach { emoji ->
            row.addView(
                Button(context).apply {
                    text = emoji
                    setOnClickListener {
                        fragment.viewLifecycleOwner.lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    CastRoomRepository.postMessage(
                                        context,
                                        token,
                                        TvSidekick.KIND_REACT,
                                        emoji,
                                    )
                                }
                            }
                            if (fragment.isAdded) refreshSidekickChat(fragment, token, chatView)
                        }
                    }
                },
            )
        }
        scroll.addView(row)
        return scroll
    }

    private fun promptAbsoluteSeek(fragment: Fragment, token: String, currentMs: Long) {
        val context = fragment.context ?: return
        val input = EditText(context).apply {
            hint = context.getString(R.string.tv_sidekick_scrub_hint)
            setText(TvSidekick.formatPosition(currentMs))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.tv_sidekick_scrub_to_action)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.tv_sidekick_scrub_go) { _, _ ->
                val target = TvSidekick.parsePositionInput(input.text?.toString())
                if (target == null) {
                    ExpDialogChrome.notify(context, R.string.tv_sidekick_scrub_invalid)
                    return@setPositiveButton
                }
                mutate(fragment, token) { CastRoomRepository.seek(context, it, target) }
            }
            .show()
    }

    private fun refreshSidekickChat(fragment: Fragment, token: String, chatView: TextView) {
        val context = fragment.context ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val messages = withContext(Dispatchers.IO) {
                runCatching { CastRoomRepository.listMessages(context, token) }
                    .getOrDefault(emptyList())
            }
            if (!fragment.isAdded) return@launch
            chatView.text = if (messages.isEmpty()) {
                context.getString(R.string.tv_sidekick_chat_empty)
            } else {
                messages.takeLast(12).joinToString("\n") {
                    TvSidekick.formatMessageLine(it.displayName, it.kind, it.body)
                }
            }
        }
    }

    private fun showItemActions(
        fragment: Fragment,
        token: String,
        payload: CastRoomPayload,
        index: Int,
        item: CastRoomItemPayload,
    ) {
        val context = fragment.context ?: return
        val ids = payload.items.mapNotNull { it.id }
        val options = mutableListOf(
            context.getString(R.string.cast_room_play_now_action),
            context.getString(R.string.cast_room_remove_action),
        )
        if (index > 0) options.add(context.getString(R.string.cast_room_move_up_action))
        if (index < payload.items.lastIndex) {
            options.add(context.getString(R.string.cast_room_move_down_action))
        }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(CastRoomQueue.displayLabel(item))
            .setItems(options.toTypedArray()) { _, which ->
                val label = options[which]
                when (label) {
                    context.getString(R.string.cast_room_play_now_action) ->
                        mutate(fragment, token) {
                            CastRoomRepository.playAt(context, it, index)
                        }
                    context.getString(R.string.cast_room_remove_action) -> {
                        val itemId = item.id ?: return@setItems
                        mutate(fragment, token) {
                            CastRoomRepository.removeItem(context, it, itemId)
                        }
                    }
                    context.getString(R.string.cast_room_move_up_action) -> {
                        val moved = CastRoomQueue.moveItem(ids, index, index - 1) ?: return@setItems
                        mutate(fragment, token) {
                            CastRoomRepository.reorder(context, it, moved)
                        }
                    }
                    context.getString(R.string.cast_room_move_down_action) -> {
                        val moved = CastRoomQueue.moveItem(ids, index, index + 1) ?: return@setItems
                        mutate(fragment, token) {
                            CastRoomRepository.reorder(context, it, moved)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun mutate(
        fragment: Fragment,
        token: String,
        block: suspend (String) -> CastRoomPayload,
    ) {
        val context = fragment.context ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { block(token) }
            }
            if (!fragment.isAdded) return@launch
            result.onSuccess { payload ->
                latestPayload = payload
                renderControllerPanel(fragment, payload)
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    context,
                    context.getString(
                        R.string.cast_room_control_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    fun navigateHostToItem(activity: FragmentActivity, item: CastRoomItemPayload) {
        activity.lifecycleScope.launch {
            val providerName = latestPayload?.provider
            val provider = providerName?.let { Provider.findByName(it) }
            if (provider == null) {
                ExpDialogChrome.notify(activity, R.string.cast_room_unknown_provider)
                return@launch
            }
            UserPreferences.setCurrentProviderForPlayback(provider)
            val videoType = runCatching { CastRoomRepository.toVideoType(item) }.getOrElse {
                ExpDialogChrome.notify(
                    activity,
                    activity.getString(
                        R.string.cast_room_join_failed,
                        it.message ?: it.javaClass.simpleName,
                    ),
                )
                return@launch
            }
            val args = playerArgs(videoType, item)
            val navHost =
                activity.supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
            val nav = navHost?.navController
            if (nav == null) {
                ExpDialogChrome.notify(activity, R.string.cast_room_join_failed_nav)
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
                ExpDialogChrome.notify(activity, R.string.cast_room_join_failed_nav)
            }
        }
    }

    private fun playerArgs(videoType: Video.Type, item: CastRoomItemPayload): Bundle {
        val title: String
        val subtitle: String
        when (videoType) {
            is Video.Type.Movie -> {
                title = videoType.title
                subtitle = ""
            }
            is Video.Type.Episode -> {
                title = videoType.tvShow.title
                subtitle = item.subtitle.ifBlank {
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
            "preferredServerName" to item.preferredServerName,
        )
    }

    fun shareLink(context: Context, link: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.cast_room_share_subject))
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    send,
                    context.getString(R.string.cast_room_share_action),
                ),
            )
        }.onFailure {
            Toast.makeText(context, link, Toast.LENGTH_LONG).show()
        }
    }

    private object CoroutineScopeIo {
        private val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
        )

        fun launch(block: suspend () -> Unit) {
            scope.launch { block() }
        }
    }

    private fun LogW(msg: String, error: Throwable) {
        android.util.Log.w("CastRoomUi", msg, error)
    }
}
