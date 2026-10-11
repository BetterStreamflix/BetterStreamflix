package com.dskja.betterstreamflix.sync

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.ExpDialogChrome

object CastHandoffSettingsController {

    const val KEY_CLAIM_CODE = "cast_handoff_claim_code"

    fun bind(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
    ) {
        val claim = findPreference(KEY_CLAIM_CODE)
        claim?.setOnPreferenceClickListener {
            promptCode(fragment)
            true
        }
        claim?.summary = fragment.getString(R.string.cast_handoff_claim_summary)
    }

    private fun promptCode(fragment: Fragment) {
        val context = fragment.context ?: return
        val input = EditText(context).apply {
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = context.getString(R.string.cast_handoff_code_hint)
            setSingleLine()
        }
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.cast_handoff_claim_title)
            .setMessage(R.string.cast_handoff_claim_message)
            .setView(wrap)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.cast_handoff_claim_action) { _, _ ->
                val code = CastHandoffLinks.normalizeShortCode(input.text?.toString())
                if (code == null) {
                    ExpDialogChrome.notify(context, R.string.cast_handoff_invalid_code)
                    return@setPositiveButton
                }
                val activity = fragment.activity ?: return@setPositiveButton
                CastHandoffUi.handleIncomingCode(activity, code)
            }
            .show()
        input.requestFocus()
    }
}
