package com.dskja.betterstreamflix.sync

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import com.dskja.betterstreamflix.R

object WatchPartySettingsController {

    const val KEY_JOIN_CODE = "watch_party_join_code"
    const val KEY_OPEN_REMOTE = "watch_party_open_remote"
    const val KEY_SHOW_CODE = "watch_party_show_code"

    fun bind(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
    ) {
        val joinCode = findPreference(KEY_JOIN_CODE)
        val openRemote = findPreference(KEY_OPEN_REMOTE)
        val showCode = findPreference(KEY_SHOW_CODE)

        fun refresh() {
            val hosting = WatchPartySession.isHost()
            val remote = WatchPartySession.isRemote()
            joinCode?.isVisible = !hosting
            openRemote?.isVisible = remote
            showCode?.isVisible = hosting
            showCode?.summary = if (hosting) {
                fragment.getString(
                    R.string.watch_party_code_label,
                    WatchPartySession.shortCode ?: "------",
                )
            } else {
                fragment.getString(R.string.watch_party_show_code_summary)
            }
            joinCode?.summary = fragment.getString(R.string.watch_party_join_code_summary)
        }

        joinCode?.setOnPreferenceClickListener {
            promptCode(fragment)
            true
        }
        openRemote?.setOnPreferenceClickListener {
            WatchPartyRemoteUi.showRemotePanel(fragment)
            true
        }
        showCode?.setOnPreferenceClickListener {
            if (WatchPartySession.isHost()) {
                WatchPartyUi.showHostQr(fragment)
            }
            true
        }

        refresh()
    }

    private fun promptCode(fragment: Fragment) {
        val context = fragment.context ?: return
        val input = EditText(context).apply {
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = context.getString(R.string.watch_party_code_hint)
            setSingleLine()
        }
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.watch_party_join_code_title)
            .setView(wrap)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.watch_party_join_action) { _, _ ->
                val activity = fragment.activity ?: return@setPositiveButton
                WatchPartyUi.handleIncomingCode(activity, input.text?.toString().orEmpty())
            }
            .show()
    }
}
