package com.dskja.betterstreamflix.fragments.settings

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.logo.TmdbLogoCache
import com.dskja.betterstreamflix.logo.TmdbLogoTelemetry
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.TMDb3
import com.dskja.betterstreamflix.utils.TmdbCache
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object TmdbSettingsController {

    private fun notifyUser(
        context: Context,
        messageRes: Int,
        titleRes: Int = R.string.settings_category_tmdb,
    ) {
        ExpDialogChrome.notify(context, messageRes, titleRes)
    }

    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
    ) {
        val context = fragment.requireContext()
        val testPref = findPreference("TMDB_TEST_CONNECTION")
        val clearPref = findPreference("TMDB_CLEAR_CACHE")
        val clearLogoPref = findPreference("TMDB_CLEAR_LOGO_CACHE")
        val telemetryPref = findPreference("TMDB_LOGO_TELEMETRY")
        val statusPref = findPreference("TMDB_STATUS")
        val logosPref = findPreference("ENABLE_TMDB_LOGOS") as? SwitchPreferenceCompat
        val qualityPref = findPreference("TMDB_LOGO_QUALITY") as? ListPreference
        val missTtlPref = findPreference("TMDB_LOGO_MISS_TTL_HOURS") as? ListPreference
        val hitTtlPref = findPreference("TMDB_LOGO_HIT_TTL_HOURS") as? ListPreference

        fun refreshTelemetry() {
            telemetryPref?.summary = TmdbLogoTelemetry.debugSummary()
        }

        fun refreshStatus() {
            statusPref?.summary = when {
                !UserPreferences.enableTmdb ->
                    context.getString(R.string.settings_tmdb_status_disabled)
                !TMDb3.hasApiKey() ->
                    context.getString(R.string.settings_tmdb_status_no_key)
                else ->
                    context.getString(R.string.settings_tmdb_status_ready)
            }
        }

        refreshStatus()
        refreshTelemetry()

        logosPref?.apply {
            isChecked = UserPreferences.enableTmdbLogos
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.enableTmdbLogos = newValue as Boolean
                true
            }
        }

        qualityPref?.apply {
            value = UserPreferences.tmdbLogoQuality
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.tmdbLogoQuality = newValue as String
                true
            }
        }

        missTtlPref?.apply {
            value = UserPreferences.tmdbLogoMissTtlHours.toString()
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.tmdbLogoMissTtlHours = (newValue as String).toIntOrNull() ?: 6
                true
            }
        }

        hitTtlPref?.apply {
            value = UserPreferences.tmdbLogoHitTtlHours.toString()
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.tmdbLogoHitTtlHours = (newValue as String).toIntOrNull() ?: 24
                true
            }
        }

        testPref?.setOnPreferenceClickListener {
            if (!UserPreferences.enableTmdb) {
                notifyUser(context, R.string.settings_tmdb_test_disabled)
                return@setOnPreferenceClickListener true
            }
            if (!TMDb3.hasApiKey()) {
                notifyUser(context, R.string.settings_tmdb_api_key_missing)
                return@setOnPreferenceClickListener true
            }
            testPref.isEnabled = false
            testPref.summary = context.getString(R.string.settings_tmdb_test_running)
            scope.launch {
                val ok = withContext(Dispatchers.IO) { TMDb3.ping() }
                testPref.isEnabled = true
                testPref.summary = context.getString(R.string.settings_tmdb_test_summary)
                refreshStatus()
                notifyUser(
                    context,
                    if (ok) R.string.settings_tmdb_test_success else R.string.settings_tmdb_test_failed,
                )
            }
            true
        }

        clearPref?.setOnPreferenceClickListener {
            TmdbCache.clear()
            TMDb3.rebuildService()
            refreshStatus()
            notifyUser(context, R.string.settings_tmdb_cache_cleared)
            true
        }

        clearLogoPref?.setOnPreferenceClickListener {
            TmdbLogoCache.clearLogoOnly()
            TmdbLogoTelemetry.reset()
            refreshTelemetry()
            notifyUser(context, R.string.settings_tmdb_logo_cache_cleared)
            true
        }

        telemetryPref?.isVisible = true
        telemetryPref?.setOnPreferenceClickListener {
            refreshTelemetry()
            val summary = TmdbLogoTelemetry.debugSummary()
            telemetryPref.summary = summary
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.settings_tmdb_logo_telemetry_title)
                .setMessage(summary)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }
    }
}
