package com.betterstreamflix.app.remotefind

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.betterstreamflix.app.R
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.profiles.ProfileUnlockGate
import com.betterstreamflix.app.utils.BypassWebSocketEndpointHelper
import com.betterstreamflix.app.utils.ExpDialogChrome
import com.betterstreamflix.app.utils.QrUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object RemoteFindUi {

    fun hostAndShowCode(fragment: Fragment) {
        val context = fragment.context ?: return
        if (ProfileManager.isKidsActive() || ProfileUnlockGate.requiresGateForActive()) {
            ExpDialogChrome.notify(context, R.string.remote_find_kids_blocked)
            return
        }
        if (RemoteFindSession.isPhone()) {
            ExpDialogChrome.notify(context, R.string.remote_find_already_phone)
            return
        }
        val hosted = RemoteFindHost.start(context.applicationContext)
        if (hosted == null) {
            ExpDialogChrome.notify(context, R.string.remote_find_host_failed_lan)
            return
        }
        showHostCode(fragment, hosted)
    }

    fun showHostCode(fragment: Fragment, hosted: RemoteFindHost.Hosted? = null) {
        val context = fragment.context ?: return
        val session = hosted ?: run {
            val token = RemoteFindSession.token
            val code = RemoteFindSession.shortCode
            val ws = RemoteFindSession.wsUrl
            if (token.isNullOrBlank() || code.isNullOrBlank() || ws.isNullOrBlank()) {
                hostAndShowCode(fragment)
                return
            }
            RemoteFindHost.Hosted(
                token = token,
                shortCode = code,
                wsUrl = ws,
                deepLink = RemoteFindLinks.buildDeepLink(ws, code),
            )
        }
        val qr = runCatching { QrUtils.generate(session.deepLink, 640) }.getOrNull()
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
                    contentDescription = context.getString(R.string.remote_find_qr_cd)
                    layoutParams = LinearLayout.LayoutParams(560, 560).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                },
            )
        }
        container.addView(
            TextView(context).apply {
                text = context.getString(R.string.remote_find_code_label, session.shortCode)
                textSize = 28f
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 8)
            },
        )
        val statusView = TextView(context).apply {
            text = if (RemoteFindSession.phoneConnected) {
                context.getString(R.string.remote_find_host_phone_connected)
            } else {
                context.getString(R.string.remote_find_host_qr_message)
            }
            textSize = 14f
            gravity = Gravity.CENTER
        }
        container.addView(statusView)
        val dialog = ExpDialogChrome.showAlert(context) { builder ->
            builder
                .setTitle(R.string.remote_find_host_qr_title)
                .setView(container)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.remote_find_end_action) { _, _ ->
                    endHost(fragment)
                }
        }
        // Live-update when a phone pairs while the dialog is open.
        val poll = object : Runnable {
            override fun run() {
                if (!dialog.isShowing || !fragment.isAdded) return
                statusView.text = if (RemoteFindSession.phoneConnected) {
                    context.getString(R.string.remote_find_host_phone_connected)
                } else {
                    context.getString(R.string.remote_find_host_qr_message)
                }
                statusView.postDelayed(this, 500)
            }
        }
        statusView.postDelayed(poll, 500)
        dialog.setOnDismissListener { statusView.removeCallbacks(poll) }
    }

    fun endHost(fragment: Fragment) {
        RemoteFindHost.stop()
        fragment.context?.let {
            ExpDialogChrome.notify(it, R.string.remote_find_ended)
        }
    }

    fun endHost(context: Context) {
        RemoteFindHost.stop()
        ExpDialogChrome.notify(context, R.string.remote_find_ended)
    }

    fun handleIncomingDeepLink(activity: FragmentActivity, raw: String?) {
        val target = RemoteFindLinks.parse(raw) ?: run {
            ExpDialogChrome.notify(activity, R.string.remote_find_invalid_link)
            return
        }
        handleIncomingTarget(activity, target)
    }

    fun handleIncomingTarget(activity: Activity, target: RemoteFindLinks.Target) {
        val code = target.code
        val ws = target.wsUrl
        when {
            !ws.isNullOrBlank() -> openPhoneRemote(activity, ws, code, target.token)
            !code.isNullOrBlank() -> {
                // HTTPS code-only link: discover WS on LAN.
                if (activity is FragmentActivity) {
                    activity.lifecycleScope.launch {
                        val offer = withContext(Dispatchers.IO) {
                            RemoteFindDiscovery.discover(code)
                        }
                        if (activity.isFinishing || activity.isDestroyed) return@launch
                        if (offer == null) {
                            ExpDialogChrome.notify(activity, R.string.remote_find_code_not_found)
                        } else {
                            openPhoneRemote(activity, offer.wsUrl, offer.code, null)
                        }
                    }
                } else {
                    Thread {
                        val offer = RemoteFindDiscovery.discover(code)
                        activity.runOnUiThread {
                            if (offer == null) {
                                ExpDialogChrome.notify(activity, R.string.remote_find_code_not_found)
                            } else {
                                openPhoneRemote(activity, offer.wsUrl, offer.code, null)
                            }
                        }
                    }.start()
                }
            }
            else -> ExpDialogChrome.notify(activity, R.string.remote_find_invalid_link)
        }
    }

    fun joinByCode(fragment: Fragment, rawCode: String) {
        val context = fragment.context ?: return
        val code = RemoteFindCode.normalize(rawCode)
        if (code == null) {
            ExpDialogChrome.notify(context, R.string.remote_find_invalid_code)
            return
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val offer = withContext(Dispatchers.IO) {
                RemoteFindDiscovery.discover(code)
            }
            if (!fragment.isAdded) return@launch
            val activity = fragment.activity ?: return@launch
            if (offer == null) {
                ExpDialogChrome.notify(activity, R.string.remote_find_code_not_found)
                return@launch
            }
            openPhoneRemote(activity, offer.wsUrl, offer.code, null)
        }
    }

    fun openPhoneRemote(
        activity: Activity,
        wsUrl: String,
        code: String?,
        legacyToken: String? = null,
    ) {
        if (RemoteFindSession.isHost()) {
            ExpDialogChrome.notify(activity, R.string.remote_find_already_host)
            return
        }
        if (!BypassWebSocketEndpointHelper.isAllowedPeerWs(wsUrl)) {
            ExpDialogChrome.notify(activity, R.string.remote_find_invalid_link)
            return
        }
        val pairingCode = RemoteFindCode.normalize(code)
        if (pairingCode == null && legacyToken.isNullOrBlank()) {
            ExpDialogChrome.notify(activity, R.string.remote_find_invalid_code)
            return
        }
        RemoteFindSession.beginPhone(legacyToken, pairingCode, wsUrl)
        val intent = Intent(activity, RemoteFindPhoneActivity::class.java).apply {
            putExtra(RemoteFindPhoneActivity.EXTRA_WS, wsUrl)
            putExtra(RemoteFindPhoneActivity.EXTRA_CODE, pairingCode)
            if (!legacyToken.isNullOrBlank()) {
                putExtra(RemoteFindPhoneActivity.EXTRA_TOKEN, legacyToken)
            }
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        activity.startActivity(intent)
    }
}
