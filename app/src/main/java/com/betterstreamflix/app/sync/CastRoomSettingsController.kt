package com.betterstreamflix.app.sync

import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.Preference
import com.betterstreamflix.app.R
import com.betterstreamflix.app.activities.tools.QrScannerActivity
import com.betterstreamflix.app.ui.LeanbackDensityPolicy
import com.betterstreamflix.app.utils.ExpDialogChrome

object CastRoomSettingsController {

    const val KEY_HOST = "cast_room_host"
    const val KEY_JOIN_SCAN = "cast_room_join_scan"
    const val KEY_JOIN_CODE = "cast_room_join_code"
    const val KEY_OPEN_QUEUE = "cast_room_open_queue"
    const val KEY_SHOW_QR = "cast_room_show_qr"
    const val KEY_END = "cast_room_end"

    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
        scanLauncher: ((Intent) -> Unit)? = null,
    ) {
        val host = findPreference(KEY_HOST)
        val joinScan = findPreference(KEY_JOIN_SCAN)
        val joinCode = findPreference(KEY_JOIN_CODE)
        val openQueue = findPreference(KEY_OPEN_QUEUE)
        val showQr = findPreference(KEY_SHOW_QR)
        val end = findPreference(KEY_END)

        fun refresh() {
            val hosting = CastRoomSession.isHost()
            val controlling = CastRoomSession.isController()
            val kids = com.betterstreamflix.app.profiles.ProfileManager.isKidsActive()
            host?.isVisible = !controlling && !kids
            host?.summary = when {
                hosting -> fragment.getString(
                    R.string.cast_room_host_active_summary,
                    CastRoomSession.shortCode ?: "------",
                )
                kids -> fragment.getString(R.string.cast_room_kids_blocked_host)
                else -> fragment.getString(R.string.cast_room_host_summary)
            }
            // CC-075: hide QR scan on TV / surfaces without a scanner.
            joinScan?.isVisible = !hosting && scanLauncher != null
            joinCode?.isVisible = !hosting
            openQueue?.isVisible = controlling
            showQr?.isVisible = hosting
            end?.isVisible = hosting || controlling
            end?.title = when {
                hosting -> fragment.getString(R.string.cast_room_end_action)
                else -> fragment.getString(R.string.cast_room_leave_action)
            }
        }

        host?.setOnPreferenceClickListener {
            CastRoomUi.hostAndShowQr(fragment)
            fragment.view?.postDelayed({ refresh() }, 400)
            true
        }
        showQr?.setOnPreferenceClickListener {
            val token = CastRoomSession.token
            if (token != null) {
                CastRoomUi.showHostQr(fragment, token, CastRoomSession.shortCode)
            }
            true
        }
        joinScan?.setOnPreferenceClickListener {
            if (scanLauncher == null) {
                ExpDialogChrome.notify(fragment.requireContext(), R.string.cast_room_scan_unavailable)
                return@setOnPreferenceClickListener true
            }
            scanLauncher.invoke(Intent(fragment.requireContext(), QrScannerActivity::class.java))
            true
        }
        joinCode?.setOnPreferenceClickListener {
            promptCode(fragment)
            true
        }
        openQueue?.setOnPreferenceClickListener {
            CastRoomUi.showControllerPanel(fragment)
            true
        }
        end?.setOnPreferenceClickListener {
            when {
                CastRoomSession.isHost() -> CastRoomUi.endHostRoom(fragment)
                CastRoomSession.isController() -> CastRoomUi.leaveController(fragment)
            }
            fragment.view?.postDelayed({ refresh() }, 400)
            true
        }

        refresh()
    }

    fun onScanResult(activity: FragmentActivity, raw: String?) {
        if (raw.isNullOrBlank()) {
            ExpDialogChrome.notify(activity, R.string.cast_room_invalid_link)
            return
        }
        CastRoomUi.handleScannedValue(activity, raw.trim())
    }

    private fun promptCode(fragment: Fragment) {
        val context = fragment.context ?: return
        val input = EditText(context).apply {
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = context.getString(R.string.cast_room_code_hint)
            setSingleLine()
        }
        val pad = LeanbackDensityPolicy.overlayChromePadPx(
            context.resources.displayMetrics.density,
        )
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.cast_room_join_code_title)
            .setView(wrap)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.cast_room_join_action) { _, _ ->
                val activity = fragment.activity ?: return@setPositiveButton
                CastRoomUi.handleIncomingCode(activity, input.text?.toString().orEmpty())
            }
            .show()
    }
}
