package com.betterstreamflix.app.sync

import android.content.Context
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.betterstreamflix.app.R
import com.betterstreamflix.app.download.DownloadConnectivityMonitor
import com.betterstreamflix.app.models.Video
import com.betterstreamflix.app.profiles.ProfileUnlockGate
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.utils.DeviceCapabilities
import com.betterstreamflix.app.utils.ExpDialogChrome
import com.betterstreamflix.app.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone→TV Session Handoff UI: offer a 6-char code on the phone, claim on TV.
 */
object SessionHandoffUi {

    interface OfferAware {
        fun onSessionHandoffOffered()
        fun pauseLocalForSessionHandoff()
        fun handoffPositionMs(): Long
        fun handoffServer(): Video.Server?
    }

    private var offerPollJob: Job? = null
    private val promptedPendingCodes = mutableSetOf<String>()

    @Volatile
    private var confirmInFlight = false

    fun offerFromPlayer(
        fragment: Fragment,
        videoType: Video.Type,
        server: Video.Server?,
        positionMs: Long,
        playing: Boolean,
        isLiveOrOffline: Boolean,
    ) {
        val context = fragment.context ?: return
        if (isLiveOrOffline) {
            ExpDialogChrome.notify(context, R.string.session_handoff_unavailable_live_offline)
            return
        }
        if (!SupabaseProvider.isConfigured) {
            ExpDialogChrome.notify(context, R.string.session_handoff_needs_supabase)
            return
        }
        if (CloudSyncManager.currentUserId() == null) {
            ExpDialogChrome.notify(context, R.string.session_handoff_needs_sign_in)
            return
        }
        if (!DownloadConnectivityMonitor.isOnline(context)) {
            ExpDialogChrome.notify(context, R.string.session_handoff_offline)
            return
        }
        // Prefer phone for offer (directional UX).
        if (DeviceCapabilities.isLeanbackDevice(context) ||
            DeviceCapabilities.isAmazonFireTv(context)
        ) {
            ExpDialogChrome.notify(context, R.string.session_handoff_offer_prefer_phone)
            return
        }

        if (SessionHandoffSession.isOffering()) {
            showOfferDialog(fragment, SessionHandoffSession.shortCode!!)
            return
        }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    SessionHandoffRepository.createOffer(
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
                SessionHandoffSession.beginOffer(
                    token = created.token,
                    handoffId = created.handoffId,
                    shortCode = created.shortCode,
                )
                if (playing) {
                    (fragment as? OfferAware)?.pauseLocalForSessionHandoff()
                }
                (fragment as? OfferAware)?.onSessionHandoffOffered()
                showOfferDialog(fragment, created.shortCode)
                startOfferPolling(fragment)
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    ctx,
                    ctx.getString(
                        R.string.session_handoff_offer_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    fun cancelOffer(fragment: Fragment) {
        val ctx = fragment.context ?: return
        val handoffId = SessionHandoffSession.handoffId
        offerPollJob?.cancel()
        offerPollJob = null
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { SessionHandoffRepository.endOffer(ctx, handoffId) }
            }
            SessionHandoffSession.clear()
            if (fragment.isAdded) {
                ExpDialogChrome.notify(ctx, R.string.session_handoff_cancelled)
            }
        }
    }

    fun promptClaimCode(activity: FragmentActivity) {
        if (ProfileUnlockGate.requiresGateForActive()) {
            // Wait for unlock; Settings re-entry or tryFlushPending resumes claim.
            return
        }
        if (!SupabaseProvider.isConfigured) {
            ExpDialogChrome.notify(activity, R.string.session_handoff_needs_supabase)
            return
        }
        if (CloudSyncManager.currentUserId() == null) {
            ExpDialogChrome.notify(activity, R.string.session_handoff_needs_sign_in)
            return
        }

        val input = EditText(activity).apply {
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(8), InputFilter.AllCaps())
            hint = activity.getString(R.string.session_handoff_code_hint)
            setPadding(48, 32, 48, 32)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.session_handoff_claim_message)
                    setPadding(24, 0, 24, 16)
                },
            )
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.session_handoff_claim_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.session_handoff_claim_action) { _, _ ->
                handleIncomingCode(activity, input.text?.toString().orEmpty())
            }
            .show()
    }

    fun handleIncoming(activity: FragmentActivity, raw: String) {
        val code = SessionHandoffLinks.parseCode(raw)
            ?: SessionHandoffCodes.normalize(raw)
        if (code != null) {
            handleIncomingCode(activity, code)
            return
        }
        if (SessionHandoffLinks.parseToken(raw) != null) {
            ExpDialogChrome.notify(activity, R.string.session_handoff_use_code)
        }
    }

    fun handleIncomingCode(activity: FragmentActivity, rawCode: String) {
        val appContext = activity.applicationContext
        val cleaned = SessionHandoffCodes.normalize(rawCode)
        if (cleaned == null) {
            ExpDialogChrome.notify(activity, R.string.session_handoff_invalid_code)
            return
        }
        // Prefer TV for claim; warn on mobile but allow with confirm.
        val isTv = DeviceCapabilities.isLeanbackDevice(activity) ||
            DeviceCapabilities.isAmazonFireTv(activity)
        if (!isTv) {
            Toast.makeText(
                activity,
                R.string.session_handoff_claim_prefer_tv,
                Toast.LENGTH_LONG,
            ).show()
        }
        // PIN gate + offline / auth queue via SessionHandoffPendingClaim.
        if (ProfileUnlockGate.requiresGateForActive()) {
            SessionHandoffPendingClaim.enqueue(appContext, cleaned)
            return
        }
        if (!SupabaseProvider.isConfigured) {
            SessionHandoffPendingClaim.enqueue(appContext, cleaned)
            ExpDialogChrome.notify(activity, R.string.session_handoff_needs_supabase)
            return
        }
        if (CloudSyncManager.currentUserId() == null) {
            SessionHandoffPendingClaim.enqueue(appContext, cleaned)
            ExpDialogChrome.notify(activity, R.string.session_handoff_needs_sign_in)
            return
        }
        if (!DownloadConnectivityMonitor.isOnline(appContext)) {
            SessionHandoffPendingClaim.enqueue(appContext, cleaned)
            ExpDialogChrome.notify(activity, R.string.session_handoff_claim_queued_offline)
            return
        }
        promptClaim(activity, cleaned)
    }

    fun tryFlushPending(activity: FragmentActivity) {
        if (ProfileUnlockGate.requiresGateForActive()) return
        if (!SupabaseProvider.isConfigured) return
        if (CloudSyncManager.currentUserId() == null) return
        if (!DownloadConnectivityMonitor.isOnline(activity)) return
        val pending = SessionHandoffPendingClaim.peek(activity)
        val next = pending.firstOrNull { it !in promptedPendingCodes } ?: return
        promptedPendingCodes.add(next)
        promptClaim(activity, next)
    }

    private fun showOfferDialog(fragment: Fragment, shortCode: String) {
        val context = fragment.context ?: return
        val display = SessionHandoffCodes.formatDisplay(shortCode)
        val message = context.getString(R.string.session_handoff_offer_message, display)
        AlertDialog.Builder(context)
            .setTitle(R.string.session_handoff_offer_title)
            .setMessage(message)
            .setPositiveButton(R.string.session_handoff_offer_done, null)
            .setNegativeButton(R.string.session_handoff_cancel_action) { _, _ ->
                cancelOffer(fragment)
            }
            .show()
    }

    private fun startOfferPolling(fragment: Fragment) {
        offerPollJob?.cancel()
        val handoffId = SessionHandoffSession.handoffId ?: return
        val token = SessionHandoffSession.token ?: return
        offerPollJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && SessionHandoffSession.isOffering()) {
                delay(2_500L)
                val payload = withContext(Dispatchers.IO) {
                    runCatching { SessionHandoffRepository.fetchByToken(fragment.requireContext(), token) }
                        .getOrNull()
                } ?: continue
                if (payload.claimed || payload.ended) {
                    SessionHandoffSession.clear()
                    offerPollJob = null
                    if (fragment.isAdded) {
                        ExpDialogChrome.notify(
                            fragment.requireContext(),
                            R.string.session_handoff_claimed_on_tv,
                        )
                    }
                    return@launch
                }
                val offer = fragment as? OfferAware
                if (offer != null && handoffId.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            SessionHandoffRepository.updateProgress(
                                context = fragment.requireContext(),
                                handoffId = handoffId,
                                server = offer.handoffServer(),
                                positionMs = offer.handoffPositionMs(),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun promptClaim(activity: FragmentActivity, code: String) {
        if (confirmInFlight) return
        confirmInFlight = true
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SessionHandoffRepository.fetchByCode(activity, code) }
            }
            confirmInFlight = false
            if (activity.isFinishing || activity.isDestroyed) return@launch
            result.onFailure { error ->
                SessionHandoffPendingClaim.acknowledge(activity, code)
                promptedPendingCodes.remove(code)
                ExpDialogChrome.notify(
                    activity,
                    activity.getString(
                        R.string.session_handoff_claim_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
                return@launch
            }
            val payload = result.getOrNull() ?: return@launch
            val title = payload.title.ifBlank {
                activity.getString(R.string.session_handoff_claim_title)
            }
            val positionLabel = CastHandoffRepository.formatPositionLabel(payload.positionMs)
            val detail = buildString {
                append(title)
                if (payload.subtitle.isNotBlank()) {
                    append(" · ")
                    append(payload.subtitle)
                }
                append('\n')
                append(activity.getString(R.string.session_handoff_claim_position_note, positionLabel))
                if (!payload.serverName.isNullOrBlank()) {
                    append('\n')
                    append(activity.getString(R.string.session_handoff_claim_server_note, payload.serverName))
                }
            }
            AlertDialog.Builder(activity)
                .setTitle(R.string.session_handoff_claim_confirm_title)
                .setMessage(detail)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    SessionHandoffPendingClaim.acknowledge(activity, code)
                    promptedPendingCodes.remove(code)
                }
                .setPositiveButton(R.string.session_handoff_claim_action) { _, _ ->
                    SessionHandoffPendingClaim.acknowledge(activity, code)
                    promptedPendingCodes.remove(code)
                    claimAndPlay(activity, code)
                }
                .setOnCancelListener {
                    SessionHandoffPendingClaim.acknowledge(activity, code)
                    promptedPendingCodes.remove(code)
                }
                .show()
        }
    }

    private fun claimAndPlay(activity: FragmentActivity, rawCode: String) {
        val code = SessionHandoffCodes.normalize(rawCode)
        if (code == null) {
            ExpDialogChrome.notify(activity, R.string.session_handoff_invalid_code)
            return
        }
        if (ProfileUnlockGate.requiresGateForActive()) {
            SessionHandoffPendingClaim.enqueue(activity.applicationContext, code)
            return
        }
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SessionHandoffRepository.claimByCode(activity, code) }
            }
            promptedPendingCodes.remove(code)
            if (activity.isFinishing || activity.isDestroyed) return@launch
            result.onFailure { error ->
                ExpDialogChrome.notify(
                    activity,
                    activity.getString(
                        R.string.session_handoff_claim_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
                return@launch
            }
            val payload = result.getOrNull() ?: return@launch
            navigateToPlayer(activity, payload)
        }
    }

    private fun navigateToPlayer(activity: FragmentActivity, payload: SessionHandoffPayload) {
        val provider = Provider.findByName(payload.provider)
        if (provider == null) {
            ExpDialogChrome.notify(activity, R.string.session_handoff_unknown_provider)
            return
        }
        UserPreferences.setCurrentProviderForPlayback(provider)
        val videoType = runCatching { SessionHandoffRepository.toVideoType(payload) }.getOrElse {
            ExpDialogChrome.notify(
                activity,
                activity.getString(
                    R.string.session_handoff_claim_failed,
                    it.message ?: it.javaClass.simpleName,
                ),
            )
            return
        }
        val args = playerArgs(videoType, payload)
        val navHost =
            activity.supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
        val nav = navHost?.navController
        if (nav == null) {
            ExpDialogChrome.notify(activity, R.string.session_handoff_nav_failed)
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
            ExpDialogChrome.notify(activity, R.string.session_handoff_nav_failed)
            return
        }
        Toast.makeText(
            activity,
            activity.getString(R.string.session_handoff_playing),
            Toast.LENGTH_SHORT,
        ).show()
        if (DeviceCapabilities.isLeanbackDevice(activity) ||
            DeviceCapabilities.isAmazonFireTv(activity)
        ) {
            activity.window?.decorView?.post {
                (navHost.childFragmentManager.primaryNavigationFragment
                    as? com.betterstreamflix.app.fragments.player.PlayerTvFragment)
                    ?.requestPlayerFocus()
            }
        }
    }

    private fun playerArgs(videoType: Video.Type, payload: SessionHandoffPayload): Bundle {
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
            "preferredServerId" to payload.serverId,
            "startPositionMs" to payload.positionMs.coerceAtLeast(0L),
        )
    }
}
