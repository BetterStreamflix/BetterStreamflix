package com.betterstreamflix.app.fragments.settings

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.betterstreamflix.app.R
import com.betterstreamflix.app.logo.TmdbLogoCache
import com.betterstreamflix.app.utils.ExpDialogChrome
import com.betterstreamflix.app.utils.TMDb3
import com.betterstreamflix.app.utils.TmdbCache
import com.betterstreamflix.app.utils.UserPreferences
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
        val statusPref = findPreference("TMDB_STATUS")
        val statsPref = findPreference("TMDB_LOGO_STATS")
        val refreshPref = findPreference("TMDB_REFRESH_LOGOS")

        fun refreshStats() {
            val summary = com.betterstreamflix.app.logo.TmdbLogoTelemetry.settingsSummary()
            statsPref?.summary = summary.ifBlank {
                context.getString(R.string.settings_tmdb_logo_stats_empty)
            }
        }
        refreshStats()

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
            TmdbCache.clearLogos()
            com.betterstreamflix.app.logo.ChannelLogoStore.clearAll()
            com.betterstreamflix.app.logo.ProviderLogoStore.clearAll()
            notifyUser(context, R.string.settings_tmdb_logo_cache_cleared)
            true
        }

        refreshPref?.setOnPreferenceClickListener {
            if (com.betterstreamflix.app.logo.LogoBulkRefresh.running) {
                return@setOnPreferenceClickListener true
            }
            refreshPref.isEnabled = false
            scope.launch {
                val count = withContext(Dispatchers.IO) {
                    com.betterstreamflix.app.logo.LogoBulkRefresh.refreshPersisted(context)
                }
                refreshPref.isEnabled = true
                refreshStats()
                ExpDialogChrome.notify(
                    context,
                    context.getString(R.string.settings_tmdb_logos_refreshed, count),
                    R.string.settings_category_tmdb,
                )
            }
            true
        }
    }
}
