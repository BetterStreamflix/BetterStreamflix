package com.dskja.betterstreamflix.remotefind

import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.activities.tools.QrScannerActivity
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.profiles.ProfileUnlockGate
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.ExpDialogChrome

object RemoteFindSettingsController {

    const val KEY_HOST = "remote_find_host"
    const val KEY_SHOW_CODE = "remote_find_show_code"
    const val KEY_JOIN_SCAN = "remote_find_join_scan"
    const val KEY_JOIN_CODE = "remote_find_join_code"
    const val KEY_OPEN_PAD = "remote_find_open_pad"
    const val KEY_END = "remote_find_end"

    fun bind(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
        scanLauncher: ((Intent) -> Unit)? = null,
    ) {
        val host = findPreference(KEY_HOST)
        val showCode = findPreference(KEY_SHOW_CODE)
        val joinScan = findPreference(KEY_JOIN_SCAN)
        val joinCode = findPreference(KEY_JOIN_CODE)
        val openPad = findPreference(KEY_OPEN_PAD)
        val end = findPreference(KEY_END)
        val leanback = DeviceCapabilities.isLeanbackDevice(fragment.requireContext())
        val kidsBlocked = ProfileManager.isKidsActive() || ProfileUnlockGate.requiresGateForActive()

        fun refresh() {
            val hosting = RemoteFindSession.isHost()
            val phone = RemoteFindSession.isPhone()
            host?.isVisible = leanback && !phone && !kidsBlocked
            host?.isEnabled = !kidsBlocked
            host?.summary = when {
                kidsBlocked -> fragment.getString(R.string.remote_find_kids_blocked)
                hosting -> fragment.getString(
                    R.string.remote_find_host_active_summary,
                    RemoteFindSession.shortCode ?: "------",
                )
                else -> fragment.getString(R.string.remote_find_host_summary)
            }
            showCode?.isVisible = hosting
            joinScan?.isVisible = !leanback && !hosting
            joinCode?.isVisible = !leanback && !hosting
            openPad?.isVisible = phone
            end?.isVisible = hosting || phone
            end?.title = when {
                hosting -> fragment.getString(R.string.remote_find_end_action)
                else -> fragment.getString(R.string.remote_find_leave_action)
            }
        }

        host?.setOnPreferenceClickListener {
            if (ProfileManager.isKidsActive() || ProfileUnlockGate.requiresGateForActive()) {
                ExpDialogChrome.notify(fragment.requireContext(), R.string.remote_find_kids_blocked)
                return@setOnPreferenceClickListener true
            }
            RemoteFindUi.hostAndShowCode(fragment)
            fragment.view?.postDelayed({ refresh() }, 300)
            true
        }
        showCode?.setOnPreferenceClickListener {
            RemoteFindUi.showHostCode(fragment)
            true
        }
        joinScan?.setOnPreferenceClickListener {
            if (scanLauncher == null) {
                ExpDialogChrome.notify(fragment.requireContext(), R.string.remote_find_scan_unavailable)
                return@setOnPreferenceClickListener true
            }
            scanLauncher.invoke(Intent(fragment.requireContext(), QrScannerActivity::class.java))
            true
        }
        joinCode?.setOnPreferenceClickListener {
            promptCode(fragment) { refresh() }
            true
        }
        openPad?.setOnPreferenceClickListener {
            val ws = RemoteFindSession.wsUrl
            val code = RemoteFindSession.shortCode
            if (ws != null) {
                RemoteFindUi.openPhoneRemote(
                    fragment.requireActivity(),
                    ws,
                    code,
                    RemoteFindSession.token,
                )
            }
            true
        }
        end?.setOnPreferenceClickListener {
            when {
                RemoteFindSession.isHost() -> RemoteFindUi.endHost(fragment)
                RemoteFindSession.isPhone() -> {
                    RemoteFindSession.leavePhone()
                    ExpDialogChrome.notify(fragment.requireContext(), R.string.remote_find_left)
                }
            }
            fragment.view?.postDelayed({ refresh() }, 200)
            true
        }
        refresh()
    }

    fun onScanResult(activity: FragmentActivity, raw: String?) {
        if (raw.isNullOrBlank()) {
            ExpDialogChrome.notify(activity, R.string.remote_find_invalid_link)
            return
        }
        val target = RemoteFindLinks.parse(raw.trim())
        if (target != null) {
            RemoteFindUi.handleIncomingTarget(activity, target)
            return
        }
        // Scanned value might be a bare 6-char code.
        val code = RemoteFindCode.normalize(raw)
        if (code != null) {
            Thread {
                val offer = RemoteFindDiscovery.discover(code)
                activity.runOnUiThread {
                    if (offer == null) {
                        ExpDialogChrome.notify(activity, R.string.remote_find_code_not_found)
                    } else {
                        RemoteFindUi.openPhoneRemote(
                            activity,
                            offer.wsUrl,
                            offer.code,
                            null,
                        )
                    }
                }
            }.start()
            return
        }
        ExpDialogChrome.notify(activity, R.string.remote_find_invalid_link)
    }

    private fun promptCode(fragment: Fragment, onDone: () -> Unit) {
        val context = fragment.context ?: return
        val input = EditText(context).apply {
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = context.getString(R.string.remote_find_code_hint)
            setSingleLine()
        }
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.remote_find_join_code_title)
            .setView(wrap)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.remote_find_join_action) { _, _ ->
                RemoteFindUi.joinByCode(fragment, input.text?.toString().orEmpty())
                fragment.view?.postDelayed({ onDone() }, 500)
            }
            .show()
    }
}
