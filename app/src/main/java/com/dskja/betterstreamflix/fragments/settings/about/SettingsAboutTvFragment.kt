package com.dskja.betterstreamflix.fragments.settings.about

import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.leanback.preference.LeanbackPreferenceFragmentCompat
import androidx.preference.Preference
import com.dskja.betterstreamflix.BuildConfig
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.fragments.settings.SettingsListStyler
import com.dskja.betterstreamflix.support.SupportLinkOpener
import com.dskja.betterstreamflix.support.SupportProvider
import com.dskja.betterstreamflix.support.SupportUrls
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.google.android.material.color.MaterialColors

class SettingsAboutTvFragment : LeanbackPreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_about_tv, rootKey)
        displaySettingsAbout()
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (ExperimentalMobileDesign.enabled()) {
            ExperimentalMobileDesign.applyReducedGlass(view)
            view.setBackgroundResource(R.drawable.bg_exp_canvas_atmosphere)
            SettingsListStyler.attach(view, isTv = true)
            ExpMotion.enterScreen(view)
            tintAboutPreferences()
        }
    }

    private fun displaySettingsAbout() {
        findPreference<Preference>("p_settings_about_version")?.apply {
            summary = getString(R.string.settings_about_version_name, BuildConfig.VERSION_NAME)
            if (ExperimentalMobileDesign.enabled()) {
                val brand = getString(R.string.app_name)
                val spannable = SpannableString(brand)
                spannable.setSpan(
                    ForegroundColorSpan(
                        MaterialColors.getColor(
                            requireContext(),
                            androidx.appcompat.R.attr.colorPrimary,
                            0xFFE50914.toInt(),
                        ),
                    ),
                    0,
                    brand.length,
                    0,
                )
                title = spannable
            }
        }

        fun openLink(url: String, markAppreciation: Boolean = false): Boolean {
            if (ExperimentalMobileDesign.enabled()) {
                view?.let { ExpMotion.hapticTap(it) }
            }
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
            if (ExperimentalMobileDesign.enabled()) view?.let { ExpMotion.hapticTap(it) }
            SupportLinkOpener.openProvider(requireContext(), SupportProvider.PATREON)
            true
        }

        findPreference<Preference>("p_settings_discord")?.setOnPreferenceClickListener {
            if (ExperimentalMobileDesign.enabled()) view?.let { ExpMotion.hapticTap(it) }
            SupportLinkOpener.openProvider(requireContext(), SupportProvider.DISCORD)
            true
        }

        findPreference<Preference>("p_settings_upstream")?.setOnPreferenceClickListener {
            openLink(SupportUrls.UPSTREAM_REPOSITORY_URL)
        }
    }

    private fun tintAboutPreferences() {
        val primary = MaterialColors.getColor(
            requireContext(),
            androidx.appcompat.R.attr.colorPrimary,
            0xFFE50914.toInt(),
        )
        val onVariant = MaterialColors.getColor(
            requireContext(),
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            0xFFA3A3A3.toInt(),
        )
        fun tintTitle(key: String) {
            findPreference<Preference>(key)?.let { pref ->
                val titleText = pref.title?.toString() ?: return@let
                val spannable = SpannableString(titleText)
                spannable.setSpan(ForegroundColorSpan(primary), 0, titleText.length, 0)
                pref.title = spannable
            }
        }
        fun tintSummary(key: String) {
            findPreference<Preference>(key)?.let { pref ->
                val summaryText = pref.summary?.toString() ?: return@let
                val spannable = SpannableString(summaryText)
                spannable.setSpan(ForegroundColorSpan(onVariant), 0, summaryText.length, 0)
                pref.summary = spannable
            }
        }
        listOf(
            "p_settings_github",
            "p_settings_buy_me_a_coffee",
            "p_settings_github_sponsors",
            "p_settings_patreon",
            "p_settings_discord",
            "p_settings_upstream",
        ).forEach { key ->
            tintTitle(key)
            tintSummary(key)
        }
        tintTitle("p_settings_about_maintainer")
        tintTitle("p_settings_credits")
        tintSummary("p_settings_credits")
        tintSummary("p_settings_about_version")
    }
}
