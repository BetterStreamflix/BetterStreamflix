package com.dskja.betterstreamflix.sync

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
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.download.DownloadConnectivityMonitor
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.UserPreferences
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
        if (!SupabaseProvider.isConfigured) {
            ExpDialogChrome.notify(activity, R.string.session_handoff_needs_supabase)
            return
        }
        if (CloudSyncManager.currentUserId() == null) {
            ExpDialogChrome.notify(activity, R.string.session_handoff_needs_sign_in)
            return
        }
        if (!DownloadConnectivityMonitor.isOnline(activity)) {
            ExpDialogChrome.notify(activity, R.string.session_handoff_offline)
            return
        }

        val input = EditText(activity).apply {
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(7), InputFilter.AllCaps())
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
                claimAndPlay(activity, input.text?.toString().orEmpty())
            }
            .show()
    }

    fun handleIncoming(activity: FragmentActivity, raw: String) {
        val code = SessionHandoffLinks.parseCode(raw)
            ?: SessionHandoffCodes.normalize(raw)
        if (code != null) {
            claimAndPlay(activity, code)
            return
        }
        if (SessionHandoffLinks.parseToken(raw) != null) {
            ExpDialogChrome.notify(activity, R.string.session_handoff_use_code)
        }
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
                // Keep position fresh while the offer is live.
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

    private fun claimAndPlay(activity: FragmentActivity, rawCode: String) {
        val code = SessionHandoffCodes.normalize(rawCode)
        if (code == null) {
            ExpDialogChrome.notify(activity, R.string.session_handoff_invalid_code)
            return
        }
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SessionHandoffRepository.claimByCode(activity, code) }
            }
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
        // Leanback: nudge focus into the player after navigation.
        if (DeviceCapabilities.isLeanbackDevice(activity) ||
            DeviceCapabilities.isAmazonFireTv(activity)
        ) {
            activity.window?.decorView?.post {
                (navHost.childFragmentManager.primaryNavigationFragment
                    as? com.dskja.betterstreamflix.fragments.player.PlayerTvFragment)
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
            "startPositionMs" to payload.positionMs.coerceAtLeast(0L),
        )
    }
}
