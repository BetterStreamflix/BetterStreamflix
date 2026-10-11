package com.dskja.betterstreamflix.sync

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.QrUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object CastHandoffUi {
    private val promptedPendingCodes = mutableSetOf<String>()

    @Volatile
    private var confirmInFlight = false

    fun showOfferMenu(
        fragment: Fragment,
        videoType: Video.Type,
        server: Video.Server?,
        positionMs: Long,
        isLiveOrOffline: Boolean,
        onOfferActive: (() -> Unit)? = null,
        onOfferEnded: (() -> Unit)? = null,
    ) {
        val context = fragment.context ?: return
        if (isLiveOrOffline) {
            ExpDialogChrome.notify(context, R.string.cast_handoff_unavailable_live_offline)
            return
        }
        if (!SupabaseProvider.isConfigured) {
            ExpDialogChrome.notify(context, R.string.cast_handoff_needs_supabase)
            return
        }
        if (CloudSyncManager.currentUserId() == null) {
            ExpDialogChrome.notify(context, R.string.cast_handoff_needs_sign_in)
            return
        }

        if (CastHandoffSession.isOffer()) {
            val options = arrayOf(
                context.getString(R.string.cast_handoff_show_code_action),
                context.getString(R.string.cast_handoff_revoke_action),
            )
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.cast_handoff_menu_title)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> showOfferCode(
                            fragment,
                            CastHandoffSession.shortCode ?: "------",
                            onOfferEnded,
                        )
                        1 -> revokeOffer(fragment, onOfferEnded)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.cast_handoff_menu_title)
            .setMessage(R.string.cast_handoff_offer_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.cast_handoff_offer_action) { _, _ ->
                offerAndShowCode(
                    fragment,
                    videoType,
                    server,
                    positionMs,
                    onOfferActive,
                    onOfferEnded,
                )
            }
            .show()
    }

    private fun offerAndShowCode(
        fragment: Fragment,
        videoType: Video.Type,
        server: Video.Server?,
        positionMs: Long,
        onOfferActive: (() -> Unit)?,
        onOfferEnded: (() -> Unit)?,
    ) {
        val context = fragment.context ?: return
        if (!DownloadConnectivityMonitor.isOnline(context)) {
            ExpDialogChrome.notify(context, R.string.cast_handoff_offline)
            return
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    CastHandoffRepository.offer(
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
                CastHandoffSession.beginOffer(created.shortCode, created.handoffId)
                onOfferActive?.invoke()
                showOfferCode(fragment, created.shortCode, onOfferEnded)
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    ctx,
                    ctx.getString(
                        R.string.cast_handoff_offer_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    fun showOfferCode(
        fragment: Fragment,
        shortCode: String,
        onOfferEnded: (() -> Unit)? = null,
    ) {
        val context = fragment.context ?: return
        val code = CastHandoffLinks.normalizeShortCode(shortCode) ?: shortCode
        val link = CastHandoffLinks.buildUri(code)
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
                    contentDescription = context.getString(R.string.cast_handoff_qr_cd)
                    layoutParams = LinearLayout.LayoutParams(520, 520).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                },
            )
        }
        container.addView(
            TextView(context).apply {
                text = code
                textSize = 36f
                gravity = Gravity.CENTER
                setPadding(0, 28, 0, 12)
                letterSpacing = 0.18f
            },
        )
        container.addView(
            TextView(context).apply {
                text = context.getString(R.string.cast_handoff_offer_code_message)
                textSize = 14f
                gravity = Gravity.CENTER
            },
        )

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.cast_handoff_offer_code_title)
            .setView(container)
            .setPositiveButton(R.string.cast_handoff_share_action) { _, _ ->
                shareLink(context, link)
            }
            .setNeutralButton(R.string.cast_handoff_revoke_action) { _, _ ->
                revokeOffer(fragment, onOfferEnded)
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    fun revokeOffer(fragment: Fragment, onOfferEnded: (() -> Unit)? = null) {
        val ctx = fragment.context ?: return
        val handoffId = CastHandoffSession.handoffId
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { CastHandoffRepository.revoke(ctx, handoffId) }
            }
            CastHandoffSession.clear()
            onOfferEnded?.invoke()
            if (fragment.isAdded) {
                ExpDialogChrome.notify(ctx, R.string.cast_handoff_revoked)
            }
        }
    }

    fun handleIncomingCode(activity: FragmentActivity, code: String) {
        val appContext = activity.applicationContext
        val cleaned = CastHandoffLinks.normalizeShortCode(code)
        if (cleaned == null) {
            ExpDialogChrome.notify(activity, R.string.cast_handoff_invalid_code)
            return
        }
        // Prefer phone for Cast claim (TV→Phone).
        val isTv = DeviceCapabilities.isLeanbackDevice(activity) ||
            DeviceCapabilities.isAmazonFireTv(activity)
        if (isTv) {
            Toast.makeText(
                activity,
                R.string.cast_handoff_claim_prefer_phone,
                Toast.LENGTH_LONG,
            ).show()
        }
        if (ProfileUnlockGate.requiresGateForActive()) {
            CastHandoffPendingClaim.enqueue(appContext, cleaned)
            return
        }
        if (!SupabaseProvider.isConfigured) {
            CastHandoffPendingClaim.enqueue(appContext, cleaned)
            ExpDialogChrome.notify(activity, R.string.cast_handoff_claim_needs_supabase)
            return
        }
        if (CloudSyncManager.currentUserId() == null) {
            CastHandoffPendingClaim.enqueue(appContext, cleaned)
            ExpDialogChrome.notify(activity, R.string.cast_handoff_needs_sign_in)
            return
        }
        if (!DownloadConnectivityMonitor.isOnline(appContext)) {
            CastHandoffPendingClaim.enqueue(appContext, cleaned)
            ExpDialogChrome.notify(activity, R.string.cast_handoff_claim_queued_offline)
            return
        }
        promptClaim(activity, cleaned)
    }

    fun tryFlushPending(activity: FragmentActivity) {
        if (ProfileUnlockGate.requiresGateForActive()) return
        if (!SupabaseProvider.isConfigured) return
        if (CloudSyncManager.currentUserId() == null) return
        if (!DownloadConnectivityMonitor.isOnline(activity)) return
        val pending = CastHandoffPendingClaim.peek(activity)
        val next = pending.firstOrNull { it !in promptedPendingCodes } ?: return
        promptedPendingCodes.add(next)
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { CastHandoffRepository.flushStalePending(activity) }
            }
            if (CastHandoffPendingClaim.peek(activity).contains(next)) {
                promptClaim(activity, next)
            } else {
                promptedPendingCodes.remove(next)
            }
        }
    }

    private fun promptClaim(activity: FragmentActivity, code: String) {
        if (confirmInFlight) return
        confirmInFlight = true
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CastHandoffRepository.peekByCode(activity, code) }
            }
            confirmInFlight = false
            if (activity.isFinishing || activity.isDestroyed) return@launch
            result.onFailure { error ->
                CastHandoffPendingClaim.acknowledge(activity, code)
                promptedPendingCodes.remove(code)
                ExpDialogChrome.notify(
                    activity,
                    activity.getString(
                        R.string.cast_handoff_claim_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
                return@launch
            }
            val payload = result.getOrNull() ?: return@launch
            val title = payload.title.ifBlank {
                activity.getString(R.string.cast_handoff_menu_title)
            }
            val positionLabel = CastHandoffRepository.formatPositionLabel(payload.positionMs)
            val detail = buildString {
                append(title)
                if (payload.subtitle.isNotBlank()) {
                    append(" · ")
                    append(payload.subtitle)
                }
                append('\n')
                append(activity.getString(R.string.cast_handoff_claim_position_note, positionLabel))
                if (!payload.serverName.isNullOrBlank()) {
                    append('\n')
                    append(activity.getString(R.string.cast_handoff_claim_server_note, payload.serverName))
                }
                append('\n')
                append(activity.getString(R.string.cast_handoff_claim_privacy_note))
            }
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(R.string.cast_handoff_claim_confirm_title)
                .setMessage(detail)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    CastHandoffPendingClaim.acknowledge(activity, code)
                    promptedPendingCodes.remove(code)
                }
                .setPositiveButton(R.string.cast_handoff_claim_action) { _, _ ->
                    CastHandoffPendingClaim.acknowledge(activity, code)
                    promptedPendingCodes.remove(code)
                    claimAndNavigate(activity, code)
                }
                .setOnCancelListener {
                    CastHandoffPendingClaim.acknowledge(activity, code)
                    promptedPendingCodes.remove(code)
                }
                .show()
        }
    }

    private fun claimAndNavigate(activity: FragmentActivity, code: String) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CastHandoffRepository.claim(activity, code) }
            }
            result.onFailure { error ->
                ExpDialogChrome.notify(
                    activity,
                    activity.getString(
                        R.string.cast_handoff_claim_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
                return@launch
            }
            val payload = result.getOrNull() ?: return@launch
            joinAndNavigate(activity, payload)
        }
    }

    private fun joinAndNavigate(activity: FragmentActivity, payload: CastHandoffPayload) {
        activity.lifecycleScope.launch {
            val provider = Provider.findByName(payload.provider)
            if (provider == null) {
                ExpDialogChrome.notify(activity, R.string.cast_handoff_unknown_provider)
                return@launch
            }
            UserPreferences.setCurrentProviderForPlayback(provider)
            val videoType = runCatching { CastHandoffRepository.toVideoType(payload) }.getOrElse {
                ExpDialogChrome.notify(
                    activity,
                    activity.getString(
                        R.string.cast_handoff_claim_failed,
                        it.message ?: it.javaClass.simpleName,
                    ),
                )
                return@launch
            }
            CastHandoffSession.beginClaim(
                shortCode = payload.shortCode ?: "",
                handoffId = payload.id,
                positionMs = payload.positionMs,
                serverName = payload.serverName,
                serverId = payload.serverId,
            )
            val args = playerArgs(videoType, payload)
            val navHost =
                activity.supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
            val nav = navHost?.navController
            if (nav == null) {
                ExpDialogChrome.notify(activity, R.string.cast_handoff_claim_failed_nav)
                CastHandoffSession.clear()
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
                ExpDialogChrome.notify(activity, R.string.cast_handoff_claim_failed_nav)
                CastHandoffSession.clear()
            } else {
                ExpDialogChrome.notify(activity, R.string.cast_handoff_claim_success)
            }
        }
    }

    private fun playerArgs(videoType: Video.Type, payload: CastHandoffPayload): Bundle {
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
            "preferredServerName" to (
                payload.serverName?.takeIf { it.isNotBlank() }
                    ?: CastHandoffSession.preferredServerNameOrNull()
                ),
            "preferredServerId" to (
                payload.serverId?.takeIf { it.isNotBlank() }
                    ?: CastHandoffSession.preferredServerIdOrNull()
                ),
        )
    }

    private fun shareLink(context: Context, link: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.cast_handoff_share_subject))
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    send,
                    context.getString(R.string.cast_handoff_share_action),
                ),
            )
        }.onFailure {
            Toast.makeText(context, link, Toast.LENGTH_LONG).show()
        }
    }
}
