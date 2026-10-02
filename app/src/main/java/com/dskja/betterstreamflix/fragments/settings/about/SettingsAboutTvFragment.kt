package com.dskja.betterstreamflix.fragments.settings.about

import android.os.Bundle
import androidx.leanback.preference.LeanbackPreferenceFragmentCompat
import androidx.preference.Preference
import com.dskja.betterstreamflix.BuildConfig
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.support.SupportLinkOpener
import com.dskja.betterstreamflix.support.SupportProvider
import com.dskja.betterstreamflix.support.SupportersCatalog
import com.dskja.betterstreamflix.support.SupportUrls

class SettingsAboutTvFragment : LeanbackPreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_about_tv, rootKey)
        displaySettingsAbout()
    }

    private fun displaySettingsAbout() {
        findPreference<Preference>("p_settings_about_version")?.apply {
            summary = getString(R.string.settings_about_version_name, BuildConfig.VERSION_NAME)
        }

        fun openLink(url: String, markAppreciation: Boolean = false): Boolean {
            if (markAppreciation) {
                SupportLinkOpener.open(requireContext(), url, markAppreciation = true)
            } else {
                SupportLinkOpener.open(requireContext(), url)
            }
            return true
        }

        findPreference<Preference>("p_settings_github")?.setOnPreferenceClickListener {
            openLink(SupportUrls.GITHUB_REPOSITORY_URL)
        }

        findPreference<Preference>("p_settings_buy_me_a_coffee")?.setOnPreferenceClickListener {
            openLink(SupportUrls.BUY_ME_A_COFFEE_URL, markAppreciation = true)
        }

        findPreference<Preference>("p_settings_github_sponsors")?.setOnPreferenceClickListener {
            openLink(SupportUrls.GITHUB_SPONSORS_URL, markAppreciation = true)
        }

        findPreference<Preference>("p_settings_patreon")?.setOnPreferenceClickListener {
            SupportLinkOpener.openProvider(requireContext(), SupportProvider.PATREON)
            true
        }

        findPreference<Preference>("p_settings_discord")?.setOnPreferenceClickListener {
            SupportLinkOpener.openProvider(requireContext(), SupportProvider.DISCORD)
            true
        }

        findPreference<Preference>("p_settings_upstream")?.setOnPreferenceClickListener {
            openLink(SupportUrls.UPSTREAM_REPOSITORY_URL)
        }

        val supporterNames = SupportersCatalog.displayNames(requireContext())
        findPreference<Preference>("p_settings_supporters")?.apply {
            isVisible = supporterNames.isNotBlank()
            summary = if (supporterNames.isNotBlank()) {
                getString(R.string.support_supporters_summary, supporterNames)
            } else {
                ""
            }
        }
    }
}
