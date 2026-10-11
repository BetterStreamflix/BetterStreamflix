package com.dskja.betterstreamflix.sync

import androidx.fragment.app.Fragment
import androidx.preference.Preference
import com.dskja.betterstreamflix.R

object SessionHandoffSettingsController {
    const val KEY_CLAIM = "session_handoff_claim"

    fun bind(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
    ) {
        val claim = findPreference(KEY_CLAIM) ?: return
        claim.summary = fragment.getString(R.string.session_handoff_claim_summary)
        claim.setOnPreferenceClickListener {
            SessionHandoffUi.promptClaimCode(fragment.requireActivity())
            true
        }
    }
}
