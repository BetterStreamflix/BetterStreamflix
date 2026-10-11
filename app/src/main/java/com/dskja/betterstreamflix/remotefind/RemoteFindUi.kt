package com.dskja.betterstreamflix.remotefind

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.QrUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object RemoteFindUi {

    fun hostAndShowCode(fragment: Fragment) {
        val context = fragment.context ?: return
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
                deepLink = RemoteFindLinks.buildDeepLink(ws, token, code),
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
        container.addView(
            TextView(context).apply {
                text = context.getString(R.string.remote_find_host_qr_message)
                textSize = 14f
                gravity = Gravity.CENTER
            },
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.remote_find_host_qr_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.remote_find_end_action) { _, _ ->
                endHost(fragment)
            }
            .show()
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
        openPhoneRemote(activity, target.wsUrl, target.token, target.code)
    }

    fun handleIncomingTarget(activity: Activity, target: RemoteFindLinks.Target) {
        openPhoneRemote(activity, target.wsUrl, target.token, target.code)
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
            openPhoneRemote(activity, offer.wsUrl, offer.token, offer.code)
        }
    }

    fun openPhoneRemote(
        activity: Activity,
        wsUrl: String,
        token: String,
        code: String?,
    ) {
        if (RemoteFindSession.isHost()) {
            ExpDialogChrome.notify(activity, R.string.remote_find_already_host)
            return
        }
        RemoteFindSession.beginPhone(token, code, wsUrl)
        val intent = Intent(activity, RemoteFindPhoneActivity::class.java).apply {
            putExtra(RemoteFindPhoneActivity.EXTRA_WS, wsUrl)
            putExtra(RemoteFindPhoneActivity.EXTRA_TOKEN, token)
            putExtra(RemoteFindPhoneActivity.EXTRA_CODE, code)
        }
        activity.startActivity(intent)
    }
}
