package com.betterstreamflix.app.fragments.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.utils.ExpDialogChrome
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.betterstreamflix.app.BuildConfig
import com.betterstreamflix.app.R
import com.betterstreamflix.app.platform.ConnectionDiagnostics
import com.betterstreamflix.app.platform.IntegrationStatus
import com.betterstreamflix.app.utils.CacheUtils
import com.betterstreamflix.app.utils.DnsResolver
import com.betterstreamflix.app.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared Connection & Services (screen_network) binder for Mobile + TV.
 */
object ConnectionServicesController {
    private fun alertBuilder(context: android.content.Context) =
        androidx.appcompat.app.AlertDialog.Builder(context)

    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
        openScreen: ((String) -> Unit)? = null,
        onScanResolverQr: (() -> Unit)? = null,
        onDohChanged: (() -> Unit)? = null,
        onWebSocketBypassTest: (() -> Unit)? = null,
    ) {
        val context = fragment.requireContext()

        findPreference("p_scan_resolver_qr")?.setOnPreferenceClickListener {
            haptic(fragment)
            onScanResolverQr?.invoke()
            true
        }

        findPreference("p_doh_provider_url")?.let { pref ->
            val list = pref as? ListPreference ?: return@let
            list.value = UserPreferences.dohProviderUrl
            list.summary = list.entry
            list.setOnPreferenceChangeListener { preference, newValue ->
                val newUrl = newValue as String
                UserPreferences.dohProviderUrl = newUrl
                DnsResolver.setDnsUrl(newUrl)
                if (preference is ListPreference) {
                    val index = preference.findIndexOfValue(newUrl)
                    preference.summary = preference.entries?.getOrNull(index)
                }
                refreshStatus(findPreference, context)
                if (onDohChanged != null) {
                    onDohChanged.invoke()
                } else {
                    ExpDialogChrome.notify(context, R.string.doh_provider_updated)
                }
                true
            }
        }

        findPreference("SUBDL_API_KEY")?.let { pref ->
            val edit = pref as? EditTextPreference ?: return@let
            val value = UserPreferences.subdlApiKey
            edit.text = value
            edit.summary = maskedKeySummary(context, value)
            edit.setOnBindEditTextListener { editText ->
                editText.setSingleLine()
                val pad = (20 * context.resources.displayMetrics.density).toInt()
                editText.setBackgroundResource(R.drawable.bg_search_edittext)
                editText.setPadding(pad, pad, pad, pad)
                editText.setTextAppearance(R.style.TextAppearance_CinemaInk_Body)
            }
            edit.setOnPreferenceChangeListener { preference, newValue ->
                val typed = (newValue as? String).orEmpty().trim()
                UserPreferences.subdlApiKey = typed
                preference.summary = maskedKeySummary(context, typed)
                ExpDialogChrome.notify(
                    context,
                    if (typed.isBlank()) R.string.settings_subdl_api_key_reset
                    else R.string.settings_subdl_api_key_success,
                )
                true
            }
        }

        findPreference("BYPASS_WS_ADVERTISED_HOST")?.let { pref ->
            val edit = pref as? EditTextPreference ?: return@let
            val current = UserPreferences.bypassWsAdvertisedHost
            edit.text = current
            edit.summary = if (current.isBlank()) {
                context.getString(R.string.settings_bypass_advertised_host_auto)
            } else {
                current
            }
            edit.setOnBindEditTextListener { editText ->
                editText.setSingleLine()
                editText.hint = "192.168.1.50"
                editText.setText(UserPreferences.bypassWsAdvertisedHost)
                editText.setSelection(editText.text?.length ?: 0)
                val pad = (20 * context.resources.displayMetrics.density).toInt()
                editText.setBackgroundResource(R.drawable.bg_search_edittext)
                editText.setPadding(pad, pad, pad, pad)
                editText.setTextAppearance(R.style.TextAppearance_CinemaInk_Body)
            }
            edit.setOnPreferenceChangeListener { preference, newValue ->
                val value = (newValue as? String).orEmpty().trim()
                UserPreferences.bypassWsAdvertisedHost = value
                preference.summary = if (value.isBlank()) {
                    context.getString(R.string.settings_bypass_advertised_host_auto)
                } else {
                    value
                }
                true
            }
        }

        findPreference("test_websocket_bypass")?.apply {
            isVisible = BuildConfig.DEBUG && onWebSocketBypassTest != null
            setOnPreferenceClickListener {
                haptic(fragment)
                onWebSocketBypassTest?.invoke()
                true
            }
        }

        findPreference("NETWORK_REFRESH_STATUS")?.setOnPreferenceClickListener {
            haptic(fragment)
            refresh(findPreference, context)
            ExpDialogChrome.notify(context, R.string.connection_status_refreshed)
            true
        }

        findPreference("NETWORK_TEST_CONNECTION")?.setOnPreferenceClickListener { pref ->
            haptic(fragment)
            pref.isEnabled = false
            pref.summary = context.getString(R.string.connection_probe_running)
            scope.launch {
                val report = withContext(Dispatchers.IO) {
                    ConnectionDiagnostics.runFullDiagnostic(context)
                }
                pref.isEnabled = true
                pref.summary = report.asSummary()
                applyLastProbe(findPreference, report)
                val glass = ExpDialogChrome.buildGlassMessage(context, report.lines.joinToString("\n"))
                val builder = alertBuilder(context).setTitle(report.title)
                builder.setView(glass.root)
                builder
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.connection_copy_report) { _, _ ->
                        copyReport(context, report)
                    }
                    .create()
                    .also { dialog ->
                        dialog.setOnShowListener {
                            ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                        }
                        dialog.show()
                    }
            }
            true
        }

        findPreference("NETWORK_TEST_DOH")?.setOnPreferenceClickListener { pref ->
            haptic(fragment)
            pref.isEnabled = false
            pref.summary = context.getString(R.string.connection_probe_running)
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    ConnectionDiagnostics.probeDoh()
                }
                pref.isEnabled = true
                pref.summary = result.message
                showProbeResult(
                    context,
                    titleRes = if (result.ok) R.string.platform_test_ok else R.string.platform_test_fail,
                    message = result.message,
                )
            }
            true
        }

        findPreference("NETWORK_TEST_PROVIDER")?.setOnPreferenceClickListener { pref ->
            haptic(fragment)
            pref.isEnabled = false
            pref.summary = context.getString(R.string.connection_probe_running)
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    ConnectionDiagnostics.probeProvider()
                }
                pref.isEnabled = true
                pref.summary = result.message
                showProbeResult(
                    context,
                    titleRes = if (result.ok) R.string.platform_test_ok else R.string.platform_test_fail,
                    message = result.message,
                )
            }
            true
        }

        findPreference("NETWORK_TEST_SUBDL")?.setOnPreferenceClickListener { pref ->
            haptic(fragment)
            pref.isEnabled = false
            pref.summary = context.getString(R.string.connection_probe_running)
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    ConnectionDiagnostics.probeSubdl()
                }
                pref.isEnabled = true
                pref.summary = result.message
                showProbeResult(
                    context,
                    titleRes = if (result.ok) R.string.platform_test_ok else R.string.platform_test_fail,
                    message = result.message,
                )
            }
            true
        }

        findPreference("NETWORK_TEST_SERVICES")?.setOnPreferenceClickListener { pref ->
            haptic(fragment)
            pref.isEnabled = false
            pref.summary = context.getString(R.string.connection_probe_running)
            scope.launch {
                val report = withContext(Dispatchers.IO) {
                    ConnectionDiagnostics.probeAllIntegrations()
                }
                pref.isEnabled = true
                pref.summary = report.title
                applyLastProbe(findPreference, report)
                val glass = ExpDialogChrome.buildGlassMessage(context, report.lines.joinToString("\n"))
                val builder = alertBuilder(context).setTitle(report.title)
                builder.setView(glass.root)
                builder
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.connection_copy_report) { _, _ ->
                        copyReport(context, report)
                    }
                    .create()
                    .also { dialog ->
                        dialog.setOnShowListener {
                            ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                        }
                        dialog.show()
                    }
            }
            true
        }

        findPreference("NETWORK_COPY_LAST_PROBE")?.setOnPreferenceClickListener {
            haptic(fragment)
            val report = ConnectionDiagnostics.lastReport()
            if (report == null) {
                ExpDialogChrome.notify(context, R.string.connection_probe_idle)
            } else {
                copyReport(context, report)
            }
            true
        }

        findPreference("NETWORK_CLEAR_CACHE")?.setOnPreferenceClickListener {
            haptic(fragment)
            CacheUtils.clearAppCache(context)
            ExpDialogChrome.notify(context, R.string.clear_cache_done, R.string.loading_error_clear_cache)
            true
        }

        findPreference("NETWORK_CLEAR_COOKIES")?.setOnPreferenceClickListener {
            haptic(fragment)
            val glass = ExpDialogChrome.buildGlassMessage(
                    context,
                    context.getString(R.string.connection_clear_cookies_confirm),
                )
            val builder = alertBuilder(context)
            builder.setView(glass.root)
            builder
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runCatching {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                    }
                    ExpDialogChrome.notify(context, R.string.connection_cookies_cleared)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    dialog.setOnShowListener {
                        ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                    }
                    dialog.show()
                }
            true
        }

        findPreference("NETWORK_OPEN_INTEGRATIONS")?.setOnPreferenceClickListener {
            haptic(fragment)
            openScreen?.invoke("screen_platform")
            true
        }

        findPreference("NETWORK_OPEN_TMDB")?.setOnPreferenceClickListener {
            haptic(fragment)
            openScreen?.invoke("screen_content")
            true
        }

        findPreference("NETWORK_OPEN_OPENSUBTITLES")?.setOnPreferenceClickListener {
            haptic(fragment)
            openScreen?.invoke("screen_platform_subtitles")
            true
        }

        findPreference("NETWORK_OPEN_TRAKT")?.setOnPreferenceClickListener {
            haptic(fragment)
            openScreen?.invoke("screen_platform_trakt")
            true
        }

        findPreference("NETWORK_OPEN_DEBRID")?.setOnPreferenceClickListener {
            haptic(fragment)
            openScreen?.invoke("screen_platform_debrid")
            true
        }

        findPreference("NETWORK_OPEN_JELLYFIN")?.setOnPreferenceClickListener {
            haptic(fragment)
            openScreen?.invoke("screen_platform_jellyfin")
            true
        }

        findPreference("NETWORK_OPEN_PLEX")?.setOnPreferenceClickListener {
            haptic(fragment)
            openScreen?.invoke("screen_platform_plex")
            true
        }

        refresh(findPreference, context)
    }

    fun refresh(findPreference: (String) -> Preference?, context: Context) {
        refreshStatus(findPreference, context)
        (findPreference("p_doh_provider_url") as? ListPreference)?.apply {
            value = UserPreferences.dohProviderUrl
            summary = entry
        }
        val subdl = UserPreferences.subdlApiKey
        findPreference("SUBDL_API_KEY")?.summary = maskedKeySummary(context, subdl)
        val bypass = UserPreferences.bypassWsAdvertisedHost
        findPreference("BYPASS_WS_ADVERTISED_HOST")?.summary = if (bypass.isBlank()) {
            context.getString(R.string.settings_bypass_advertised_host_auto)
        } else {
            bypass
        }
        findPreference("NETWORK_TMDB_STATUS")?.summary =
            IntegrationStatus.label(context, IntegrationStatus.tmdb())
        findPreference("NETWORK_INTEGRATIONS_STATUS")?.summary =
            IntegrationStatus.hubOverview(context)
        findPreference("NETWORK_PROVIDER_STATUS")?.summary =
            ConnectionDiagnostics.providerStatusSummary(context)
        findPreference("NETWORK_VALIDATED")?.summary =
            ConnectionDiagnostics.validatedLabel(context)
        ConnectionDiagnostics.lastReport()?.let { applyLastProbe(findPreference, it) }
    }

    private fun refreshStatus(
        findPreference: (String) -> Preference?,
        context: Context,
    ) {
        findPreference("NETWORK_STATUS")?.summary =
            ConnectionDiagnostics.statusSummary(context)
        val snap = ConnectionDiagnostics.networkSnapshot(context)
        findPreference("NETWORK_DOH_STATUS")?.summary = if (snap.dohEnabled) {
            context.getString(R.string.connection_doh_on, snap.dohProvider)
        } else {
            context.getString(R.string.connection_doh_off)
        }
        findPreference("NETWORK_VALIDATED")?.summary =
            ConnectionDiagnostics.validatedLabel(context)
        findPreference("NETWORK_PROVIDER_STATUS")?.summary =
            ConnectionDiagnostics.providerStatusSummary(context)
    }

    private fun applyLastProbe(
        findPreference: (String) -> Preference?,
        report: ConnectionDiagnostics.ProbeReport,
    ) {
        val summary = report.latencyMs?.let { ms ->
            "${report.asSummary(5)} · ${ms}ms"
        } ?: report.asSummary(6)
        findPreference("NETWORK_LAST_PROBE")?.summary = summary
    }

    private fun showProbeResult(context: Context, titleRes: Int, message: String) {
        ExpDialogChrome.showInfo(context, titleRes, message, ::alertBuilder)
    }

    private fun copyReport(context: Context, report: ConnectionDiagnostics.ProbeReport) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(
            ClipData.newPlainText("BetterStreamflix diagnostics", report.asClipboardText()),
        )
        ExpDialogChrome.notify(context, R.string.connection_report_copied)
    }

    private fun maskedKeySummary(context: Context, value: String): String =
        if (value.isBlank()) {
            context.getString(R.string.settings_subdl_api_key_summary)
        } else {
            "••••••••"
        }

    private fun haptic(fragment: Fragment) {
        runCatching { ExpMotion.hapticTap(fragment.requireView()) }
    }
}
