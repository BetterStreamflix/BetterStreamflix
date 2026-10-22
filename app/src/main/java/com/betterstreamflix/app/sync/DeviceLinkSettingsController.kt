package com.betterstreamflix.app.sync

import androidx.fragment.app.Fragment
import androidx.preference.Preference
import com.betterstreamflix.app.R
import com.betterstreamflix.app.profiles.ProfileManager

object DeviceLinkSettingsController {
    const val KEY_SHOW = "device_link_show_code"
    const val KEY_ENTER = "device_link_enter_code"

    fun bind(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
        onAccountChanged: (() -> Unit)? = null,
    ) {
        val show = findPreference(KEY_SHOW)
        val enter = findPreference(KEY_ENTER)
        if (show == null && enter == null) return

        fun refresh() {
            val kids = ProfileManager.isKidsActive()
            val signedIn = CloudSyncManager.currentUserId() != null
            val configured = SupabaseProvider.isConfigured
            show?.isVisible = !kids
            enter?.isVisible = !kids
            show?.isEnabled = configured && signedIn && !kids
            enter?.isEnabled = configured && !signedIn && !kids
            show?.summary = fragment.getString(
                if (signedIn) R.string.device_link_show_summary
                else R.string.device_link_show_summary_signed_out,
            )
            enter?.summary = fragment.getString(R.string.device_link_enter_summary)
        }

        show?.setOnPreferenceClickListener {
            DeviceLinkUi.showOfferCode(fragment)
            true
        }
        enter?.setOnPreferenceClickListener {
            DeviceLinkUi.promptEnterCode(fragment.requireActivity()) {
                refresh()
                onAccountChanged?.invoke()
            }
            true
        }
        refresh()
    }
}
