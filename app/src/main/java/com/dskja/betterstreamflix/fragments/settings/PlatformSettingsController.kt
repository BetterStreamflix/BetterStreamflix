package com.dskja.betterstreamflix.fragments.settings

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import androidx.preference.SwitchPreferenceCompat
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.platform.IntegrationProbes
import com.dskja.betterstreamflix.platform.IntegrationStatus
import com.dskja.betterstreamflix.platform.debrid.DebridProviderId
import com.dskja.betterstreamflix.platform.jellyfin.JellyfinApi
import com.dskja.betterstreamflix.platform.playerbackend.ExternalMpvBackend
import com.dskja.betterstreamflix.platform.playerbackend.PlayerBackendKind
import com.dskja.betterstreamflix.platform.playerbackend.PlayerBackendSelector
import com.dskja.betterstreamflix.platform.plugins.PluginManager
import com.dskja.betterstreamflix.platform.simkl.SimklClient
import com.dskja.betterstreamflix.platform.subtitles.OpenSubtitlesV1Client
import com.dskja.betterstreamflix.platform.trakt.TraktClient
import com.dskja.betterstreamflix.platform.trakt.TraktConfig
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object PlatformSettingsController {
    private fun notifyUser(
        context: android.content.Context,
        message: CharSequence,
        titleRes: Int = R.string.platform_settings_title,
    ) {
        ExpDialogChrome.notify(context, message, titleRes, ::alertBuilder)
    }

    private fun notifyUser(
        context: android.content.Context,
        messageRes: Int,
        titleRes: Int = R.string.platform_settings_title,
    ) {
        notifyUser(context, context.getString(messageRes), titleRes)
    }

    private fun alertBuilder(context: android.content.Context) =
        if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(context)
        } else {
            AlertDialog.Builder(context)
        }

    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
    ) {
        val context = fragment.requireContext()

        fun bindSwitch(key: String, get: () -> Boolean, set: (Boolean) -> Unit, after: (() -> Unit)? = null) {
            val pref = findPreference(key)
            when (pref) {
                is SwitchPreferenceCompat -> {
                    pref.isChecked = get()
                    pref.setOnPreferenceChangeListener { _, v ->
                        val checked = v as Boolean
                        set(checked)
                        // Persist into UserPreferences, not the default Preference store.
                        // Still update the widget so the switch does not visually snap back.
                        pref.isChecked = checked
                        after?.invoke()
                        false
                    }
                }
                is SwitchPreference -> {
                    pref.isChecked = get()
                    pref.setOnPreferenceChangeListener { _, v ->
                        val checked = v as Boolean
                        set(checked)
                        pref.isChecked = checked
                        after?.invoke()
                        false
                    }
                }
            }
        }

        fun isHttpUrl(value: String): Boolean =
            value.isBlank() || value.startsWith("http://") || value.startsWith("https://")

        fun bindText(
            key: String,
            get: () -> String,
            set: (String) -> Unit,
            mask: Boolean = false,
            validateUrl: Boolean = false,
            after: (() -> Unit)? = null,
        ) {
            val pref = findPreference(key) as? EditTextPreference ?: return
            val value = get()
            pref.text = value
            pref.summary = when {
                value.isBlank() -> pref.context.getString(defaultSummaryRes(key))
                mask -> "••••••••"
                else -> value
            }
            pref.setOnPreferenceChangeListener { preference, newValue ->
                var typed = newValue.toString().trim().trimEnd('/')
                if (validateUrl && !isHttpUrl(typed)) {
                    notifyUser(context, R.string.platform_url_invalid)
                    return@setOnPreferenceChangeListener false
                }
                set(typed)
                preference.summary = when {
                    typed.isBlank() -> preference.context.getString(defaultSummaryRes(key))
                    mask -> "••••••••"
                    else -> typed
                }
                notifyUser(context, R.string.platform_settings_saved)
                runCatching { PluginManager.reload(context) }
                after?.invoke()
                false
            }
        }

        fun refreshIntegrationSummaries() {
            refreshSummaries(context, findPreference)
        }

        bindSwitch(
            key = "TRAKT_ENABLED",
            get = { UserPreferences.traktEnabled },
            set = { UserPreferences.traktEnabled = it },
            after = { refreshIntegrationSummaries() },
        )
        fun openSupportHub() {
            runCatching {
                androidx.navigation.fragment.NavHostFragment.findNavController(fragment)
                    .navigate(R.id.support)
            }.onFailure {
                notifyUser(context, R.string.support_unable_to_open_link)
            }
        }

        fun showTraktUnavailableDialog() {
            val glass = if (ExperimentalMobileDesign.enabled()) {
                ExpDialogChrome.buildGlassMessage(
                    context,
                    context.getString(R.string.platform_trakt_unavailable_dialog_message),
                )
            } else {
                null
            }
            val builder = alertBuilder(context)
                .setTitle(R.string.platform_trakt_unavailable_dialog_title)
            if (glass != null) builder.setView(glass.root)
            else builder.setMessage(R.string.platform_trakt_unavailable_dialog_message)
            builder
                .setPositiveButton(R.string.platform_trakt_unavailable_dialog_support) { _, _ ->
                    openSupportHub()
                }
                .setNegativeButton(android.R.string.ok, null)
                .create()
                .also { dialog ->
                    dialog.setOnShowListener {
                        if (glass != null) ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                        else ExpDialogChrome.polishButtons(dialog)
                    }
                    dialog.show()
                }
        }

        fun refreshTraktLoginSummary() {
            val available = TraktConfig.hasAppCredentials()
            findPreference("trakt_status_notice")?.isVisible = !available
            findPreference("trakt_support_cta")?.isVisible = !available
            findPreference("TRAKT_ENABLED")?.isEnabled = available
            findPreference("trakt_oauth_login")?.apply {
                isEnabled = available
                summary = when {
                    !available -> context.getString(R.string.platform_trakt_oauth_login_unavailable_summary)
                    TraktConfig.isSignedIn() -> context.getString(R.string.platform_trakt_oauth_signed_in_summary)
                    else -> context.getString(R.string.platform_trakt_oauth_login_summary)
                }
            }
            findPreference("trakt_oauth_logout")?.isEnabled = available && TraktConfig.isSignedIn()
            findPreference("trakt_device_auth_start")?.isEnabled = available
            findPreference("trakt_device_auth_help")?.isEnabled = available
        }
        refreshTraktLoginSummary()

        findPreference("trakt_status_notice")?.setOnPreferenceClickListener {
            haptic(fragment)
            showTraktUnavailableDialog()
            true
        }
        findPreference("trakt_support_cta")?.setOnPreferenceClickListener {
            haptic(fragment)
            openSupportHub()
            true
        }

        bindText(
            key = "JELLYFIN_BASE_URL",
            get = { UserPreferences.jellyfinBaseUrl },
            set = { UserPreferences.jellyfinBaseUrl = it },
            validateUrl = true,
            after = { refreshIntegrationSummaries() },
        )
        bindText(
            key = "JELLYFIN_USER_ID",
            get = { UserPreferences.jellyfinUserId },
            set = { UserPreferences.jellyfinUserId = it },
            after = { refreshIntegrationSummaries() },
        )
        bindText(
            key = "JELLYFIN_ACCESS_TOKEN",
            get = { UserPreferences.jellyfinAccessToken },
            set = { UserPreferences.jellyfinAccessToken = it },
            mask = true,
            after = { refreshIntegrationSummaries() },
        )
        bindText(
            key = "PLEX_BASE_URL",
            get = { UserPreferences.plexBaseUrl },
            set = { UserPreferences.plexBaseUrl = it },
            validateUrl = true,
            after = { refreshIntegrationSummaries() },
        )
        bindText(
            key = "PLEX_TOKEN",
            get = { UserPreferences.plexToken },
            set = { UserPreferences.plexToken = it },
            mask = true,
            after = { refreshIntegrationSummaries() },
        )
        bindSwitch(
            key = "DEBRID_ENABLED",
            get = { UserPreferences.debridEnabled },
            set = { UserPreferences.debridEnabled = it },
            after = {
                refreshDebridKeyVisibility(findPreference)
                refreshIntegrationSummaries()
            },
        )
        bindText(
            key = "REAL_DEBRID_TOKEN",
            get = { UserPreferences.realDebridToken },
            set = { UserPreferences.realDebridToken = it },
            mask = true,
            after = { refreshIntegrationSummaries() },
        )
        bindText(
            key = "PREMIUMIZE_API_KEY",
            get = { UserPreferences.premiumizeApiKey },
            set = { UserPreferences.premiumizeApiKey = it },
            mask = true,
            after = { refreshIntegrationSummaries() },
        )
        bindText(
            key = "ALLDEBRID_API_KEY",
            get = { UserPreferences.allDebridApiKey },
            set = { UserPreferences.allDebridApiKey = it },
            mask = true,
            after = { refreshIntegrationSummaries() },
        )
        bindText(
            key = "TORBOX_API_KEY",
            get = { UserPreferences.torBoxApiKey },
            set = { UserPreferences.torBoxApiKey = it },
            mask = true,
            after = { refreshIntegrationSummaries() },
        )
        bindSwitch(
            key = "SIMKL_ENABLED",
            get = { UserPreferences.simklEnabled },
            set = { UserPreferences.simklEnabled = it },
            after = { refreshIntegrationSummaries() },
        )
        bindText(
            key = "SIMKL_CLIENT_ID",
            get = { UserPreferences.simklClientId },
            set = { UserPreferences.simklClientId = it },
            after = { refreshIntegrationSummaries() },
        )
        bindText(
            key = "SIMKL_ACCESS_TOKEN",
            get = { UserPreferences.simklAccessToken },
            set = { UserPreferences.simklAccessToken = it },
            mask = true,
            after = { refreshIntegrationSummaries() },
        )
        bindText(
            key = "OPENSUBTITLES_API_KEY",
            get = { UserPreferences.openSubtitlesApiKey },
            set = { UserPreferences.openSubtitlesApiKey = it },
            mask = true,
            after = { refreshIntegrationSummaries() },
        )
        bindText(
            key = "OPENSUBTITLES_LANGUAGES",
            get = { UserPreferences.openSubtitlesLanguages },
            set = { UserPreferences.openSubtitlesLanguages = it },
        )
        bindSwitch(
            key = "CAST_QUEUE_NEXT_EPISODE",
            get = { UserPreferences.castQueueNextEpisode },
            set = { UserPreferences.castQueueNextEpisode = it },
        )

        (findPreference("PLAYER_BACKEND") as? ListPreference)?.apply {
            value = UserPreferences.playerBackend
            summary = entry
            setOnPreferenceChangeListener { preference, newValue ->
                UserPreferences.playerBackend = newValue.toString()
                val list = preference as ListPreference
                val idx = list.entryValues.indexOf(newValue.toString()).coerceAtLeast(0)
                list.summary = list.entries.getOrNull(idx)
                refreshIntegrationSummaries()
                true
            }
        }

        (findPreference("DEBRID_PROVIDER") as? ListPreference)?.apply {
            value = UserPreferences.debridProvider
            summary = entry
            setOnPreferenceChangeListener { preference, newValue ->
                UserPreferences.debridProvider = newValue.toString()
                val list = preference as ListPreference
                val idx = list.entryValues.indexOf(newValue.toString()).coerceAtLeast(0)
                list.summary = list.entries.getOrNull(idx)
                refreshDebridKeyVisibility(findPreference)
                refreshIntegrationSummaries()
                true
            }
        }
        refreshDebridKeyVisibility(findPreference)

        (findPreference("SELF_HOST_PROGRESS_INTERVAL") as? ListPreference)?.apply {
            value = UserPreferences.selfHostProgressIntervalMs.toString()
            summary = entry
            setOnPreferenceChangeListener { preference, newValue ->
                UserPreferences.selfHostProgressIntervalMs =
                    newValue.toString().toLongOrNull() ?: 15_000L
                val list = preference as ListPreference
                val idx = list.entryValues.indexOf(newValue.toString()).coerceAtLeast(0)
                list.summary = list.entries.getOrNull(idx)
                true
            }
        }

        findPreference("trakt_device_auth_help")?.setOnPreferenceClickListener {
            haptic(fragment)
            if (!TraktConfig.hasAppCredentials()) {
                showTraktUnavailableDialog()
                return@setOnPreferenceClickListener true
            }
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(TraktConfig.authorizeHelpUrl(context))),
            )
            true
        }

        findPreference("trakt_oauth_login")?.setOnPreferenceClickListener {
            haptic(fragment)
            if (!TraktConfig.hasAppCredentials()) {
                showTraktUnavailableDialog()
                return@setOnPreferenceClickListener true
            }
            val intent = com.dskja.betterstreamflix.platform.trakt.TraktOAuth.authorizeIntent(context)
            if (intent == null) {
                showTraktUnavailableDialog()
                return@setOnPreferenceClickListener true
            }
            runCatching { context.startActivity(intent) }
                .onFailure {
                    notifyUser(context, context.getString(R.string.platform_trakt_oauth_failed, it.message ?: "browser"))
                }
            true
        }

        findPreference("trakt_oauth_logout")?.setOnPreferenceClickListener {
            haptic(fragment)
            if (!TraktConfig.hasAppCredentials()) {
                showTraktUnavailableDialog()
                return@setOnPreferenceClickListener true
            }
            TraktClient.logout()
            refreshTraktLoginSummary()
            refreshIntegrationSummaries()
            notifyUser(context, R.string.platform_trakt_oauth_logged_out)
            true
        }

        findPreference("trakt_device_auth_start")?.setOnPreferenceClickListener {
            haptic(fragment)
            if (!TraktConfig.hasAppCredentials()) {
                showTraktUnavailableDialog()
                return@setOnPreferenceClickListener true
            }
            scope.launch {
                val code = TraktClient.requestDeviceCode()
                if (code == null) {
                    notifyUser(context, R.string.platform_trakt_device_auth_failed)
                    return@launch
                }
                ExpDialogChrome.showInfo(
                    context,
                    R.string.platform_trakt_device_auth_title,
                    context.getString(
                        R.string.platform_trakt_device_auth_code_message,
                        code.userCode,
                        code.verificationUrl,
                    ),
                    ::alertBuilder,
                )
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUrl)),
                    )
                }
                val ok = TraktClient.pollDeviceToken(
                    deviceCode = code.deviceCode,
                    intervalSeconds = code.intervalSeconds,
                    expiresInSeconds = code.expiresInSeconds,
                )
                notifyUser(
                    context,
                    if (ok) R.string.platform_trakt_device_auth_success
                    else R.string.platform_trakt_device_auth_failed,
                )
                if (ok) {
                    refreshTraktLoginSummary()
                    refreshIntegrationSummaries()
                }
            }
            true
        }

        findPreference("jellyfin_login_submit")?.setOnPreferenceClickListener {
            haptic(fragment)
            val userPref = findPreference("jellyfin_login_user") as? EditTextPreference
            val passPref = findPreference("jellyfin_login_password") as? EditTextPreference
            val user = userPref?.text.orEmpty()
            val pass = passPref?.text.orEmpty()
            if (UserPreferences.jellyfinBaseUrl.isBlank() || user.isBlank() || pass.isBlank()) {
                notifyUser(context, R.string.platform_jellyfin_login_missing)
                return@setOnPreferenceClickListener true
            }
            scope.launch {
                val ok = JellyfinApi().authenticateByName(user, pass)
                notifyUser(
                    context,
                    if (ok) R.string.platform_jellyfin_login_success
                    else R.string.platform_jellyfin_login_failed,
                )
                if (ok) {
                    passPref?.text = ""
                    bindText(
                        key = "JELLYFIN_USER_ID",
                        get = { UserPreferences.jellyfinUserId },
                        set = { UserPreferences.jellyfinUserId = it },
                        after = { refreshIntegrationSummaries() },
                    )
                    bindText(
                        key = "JELLYFIN_ACCESS_TOKEN",
                        get = { UserPreferences.jellyfinAccessToken },
                        set = { UserPreferences.jellyfinAccessToken = it },
                        mask = true,
                        after = { refreshIntegrationSummaries() },
                    )
                    runCatching { PluginManager.reload(context) }
                    refreshIntegrationSummaries()
                }
            }
            true
        }

        findPreference("jellyfin_quick_connect")?.setOnPreferenceClickListener {
            haptic(fragment)
            if (UserPreferences.jellyfinBaseUrl.isBlank()) {
                notifyUser(context, R.string.platform_jellyfin_qc_missing_url)
                return@setOnPreferenceClickListener true
            }
            scope.launch {
                val api = JellyfinApi()
                val started = api.initiateQuickConnect()
                if (started == null) {
                    notifyUser(context, R.string.platform_jellyfin_qc_failed)
                    return@launch
                }
                ExpDialogChrome.showInfo(
                    context,
                    R.string.platform_jellyfin_qc_title,
                    context.getString(R.string.platform_jellyfin_qc_code_message, started.code),
                    ::alertBuilder,
                )
                val ok = api.pollQuickConnect(started.secret)
                notifyUser(
                    context,
                    if (ok) R.string.platform_jellyfin_qc_success
                    else R.string.platform_jellyfin_qc_failed,
                )
                if (ok) {
                    bindText(
                        key = "JELLYFIN_USER_ID",
                        get = { UserPreferences.jellyfinUserId },
                        set = { UserPreferences.jellyfinUserId = it },
                        after = { refreshIntegrationSummaries() },
                    )
                    bindText(
                        key = "JELLYFIN_ACCESS_TOKEN",
                        get = { UserPreferences.jellyfinAccessToken },
                        set = { UserPreferences.jellyfinAccessToken = it },
                        mask = true,
                        after = { refreshIntegrationSummaries() },
                    )
                    runCatching { PluginManager.reload(context) }
                    refreshIntegrationSummaries()
                }
            }
            true
        }

        findPreference("plex_token_help")?.setOnPreferenceClickListener {
            haptic(fragment)
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://support.plex.tv/articles/204059436-finding-an-authentication-token-x-plex-token/")),
                )
            }
            true
        }

        findPreference("simkl_token_help")?.setOnPreferenceClickListener {
            haptic(fragment)
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://simkl.docs.apiary.io/")),
                )
            }
            true
        }

        findPreference("simkl_logout")?.setOnPreferenceClickListener {
            haptic(fragment)
            SimklClient.clearTokens()
            bindText(
                key = "SIMKL_ACCESS_TOKEN",
                get = { UserPreferences.simklAccessToken },
                set = { UserPreferences.simklAccessToken = it },
                mask = true,
                after = { refreshIntegrationSummaries() },
            )
            refreshIntegrationSummaries()
            notifyUser(context, R.string.platform_simkl_logged_out)
            true
        }

        findPreference("opensubtitles_login_submit")?.setOnPreferenceClickListener {
            haptic(fragment)
            val user = (findPreference("opensubtitles_login_user") as? EditTextPreference)?.text.orEmpty()
            val pass = (findPreference("opensubtitles_login_password") as? EditTextPreference)?.text.orEmpty()
            if (UserPreferences.openSubtitlesApiKey.isBlank() || user.isBlank() || pass.isBlank()) {
                notifyUser(context, R.string.platform_opensubtitles_login_missing)
                return@setOnPreferenceClickListener true
            }
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    OpenSubtitlesV1Client.login(user, pass)
                }
                when (result) {
                    is OpenSubtitlesV1Client.Result.Ok -> {
                        (findPreference("opensubtitles_login_password") as? EditTextPreference)?.text = ""
                        refreshIntegrationSummaries()
                        notifyUser(context, R.string.platform_opensubtitles_login_success)
                    }
                    is OpenSubtitlesV1Client.Result.Err -> {
                        notifyUser(context, context.getString(R.string.platform_opensubtitles_login_failed, result.reason))
                    }
                }
            }
            true
        }

        findPreference("opensubtitles_logout")?.setOnPreferenceClickListener {
            haptic(fragment)
            OpenSubtitlesV1Client.clearSession()
            refreshIntegrationSummaries()
            notifyUser(context, R.string.platform_opensubtitles_logged_out)
            true
        }

        fun bindProbe(key: String, probe: suspend () -> IntegrationProbes.ProbeResult) {
            findPreference(key)?.setOnPreferenceClickListener { pref ->
                haptic(fragment)
                pref.isEnabled = false
                pref.summary = context.getString(R.string.platform_test_running)
                scope.launch {
                    val result = withContext(Dispatchers.IO) { probe() }
                    pref.isEnabled = true
                    pref.summary = result.message
                    refreshIntegrationSummaries()
                    notifyUser(
                        context,
                        if (result.ok) R.string.platform_test_ok else R.string.platform_test_fail,
                    )
                }
                true
            }
        }

        bindProbe("platform_test_jellyfin") { IntegrationProbes.jellyfin() }
        bindProbe("platform_test_plex") { IntegrationProbes.plex() }
        bindProbe("platform_test_debrid") { IntegrationProbes.debrid() }
        bindProbe("platform_test_simkl") { IntegrationProbes.simkl() }
        bindProbe("platform_test_opensubtitles") { IntegrationProbes.openSubtitles() }

        findPreference("platform_test_all")?.setOnPreferenceClickListener { pref ->
            haptic(fragment)
            pref.isEnabled = false
            pref.summary = context.getString(R.string.platform_test_running)
            scope.launch {
                val report = withContext(Dispatchers.IO) {
                    com.dskja.betterstreamflix.platform.ConnectionDiagnostics.probeAllIntegrations()
                }
                pref.isEnabled = true
                pref.summary = report.title
                refreshIntegrationSummaries()
                ExpDialogChrome.showInfo(
                    context,
                    report.title,
                    report.lines.joinToString("\n"),
                    ::alertBuilder,
                )
            }
            true
        }

        PluginSettingsController.bind(fragment, scope, findPreference)
        refreshIntegrationSummaries()
    }

    /** Call from Settings onResume so OAuth returns refresh Trakt / status rows. */
    fun refresh(fragment: Fragment, findPreference: (String) -> Preference?) {
        refreshSummaries(fragment.requireContext(), findPreference)
        val available = TraktConfig.hasAppCredentials()
        findPreference("trakt_status_notice")?.isVisible = !available
        findPreference("trakt_support_cta")?.isVisible = !available
        findPreference("TRAKT_ENABLED")?.isEnabled = available
        findPreference("trakt_oauth_login")?.apply {
            isEnabled = available
            summary = when {
                !available -> fragment.getString(R.string.platform_trakt_oauth_login_unavailable_summary)
                TraktConfig.isSignedIn() -> fragment.getString(R.string.platform_trakt_oauth_signed_in_summary)
                else -> fragment.getString(R.string.platform_trakt_oauth_login_summary)
            }
        }
        findPreference("trakt_oauth_logout")?.isEnabled = available && TraktConfig.isSignedIn()
        findPreference("trakt_device_auth_start")?.isEnabled = available
        findPreference("trakt_device_auth_help")?.isEnabled = available
        refreshDebridKeyVisibility(findPreference)
    }

    private fun refreshDebridKeyVisibility(findPreference: (String) -> Preference?) {
        val provider = DebridProviderId.fromId(UserPreferences.debridProvider)
        findPreference("REAL_DEBRID_TOKEN")?.isVisible = provider == DebridProviderId.REAL_DEBRID
        findPreference("PREMIUMIZE_API_KEY")?.isVisible = provider == DebridProviderId.PREMIUMIZE
        findPreference("ALLDEBRID_API_KEY")?.isVisible = provider == DebridProviderId.ALLDEBRID
        findPreference("TORBOX_API_KEY")?.isVisible = provider == DebridProviderId.TORBOX
    }

    private fun refreshSummaries(
        context: android.content.Context,
        findPreference: (String) -> Preference?,
    ) {
        fun setStatus(key: String, snapshot: IntegrationStatus.Snapshot) {
            findPreference(key)?.summary = IntegrationStatus.label(context, snapshot)
        }

        val trakt = IntegrationStatus.trakt()
        val jellyfin = IntegrationStatus.jellyfin()
        val plex = IntegrationStatus.plex()
        val debrid = IntegrationStatus.debrid()
        val simkl = IntegrationStatus.simkl()
        val os = IntegrationStatus.openSubtitles()
        val player = IntegrationStatus.player(context)
        val plugins = IntegrationStatus.plugins()

        setStatus("platform_jellyfin_status", jellyfin)
        setStatus("platform_plex_status", plex)
        setStatus("platform_debrid_status", debrid)
        setStatus("platform_simkl_status", simkl)
        setStatus("platform_opensubtitles_status", os)
        setStatus("platform_player_status", player)

        findPreference("screen_platform_trakt")?.summary = IntegrationStatus.label(context, trakt)
        findPreference("screen_platform_jellyfin")?.summary = IntegrationStatus.label(context, jellyfin)
        findPreference("screen_platform_plex")?.summary = IntegrationStatus.label(context, plex)
        findPreference("screen_platform_debrid")?.summary = IntegrationStatus.label(context, debrid)
        findPreference("screen_platform_simkl")?.summary = IntegrationStatus.label(context, simkl)
        findPreference("screen_platform_subtitles")?.summary = IntegrationStatus.label(context, os)
        findPreference("screen_platform_player")?.summary = IntegrationStatus.label(context, player)
        findPreference("screen_platform_plugins")?.summary = IntegrationStatus.label(context, plugins)
        findPreference("screen_platform")?.summary = hubOverview(context)

        findPreference("platform_mpv_status")?.summary = when (PlayerBackendSelector.preferredKind()) {
            PlayerBackendKind.EXO -> context.getString(R.string.platform_mpv_status_summary)
            PlayerBackendKind.EXTERNAL_MPV -> {
                val pkg = ExternalMpvBackend.preferredInstalledPackage(context)
                if (pkg != null) context.getString(R.string.platform_mpv_installed, pkg)
                else context.getString(R.string.platform_mpv_missing)
            }
        }

        findPreference("opensubtitles_logout")?.isEnabled = OpenSubtitlesV1Client.signedIn()
        findPreference("simkl_logout")?.isEnabled =
            UserPreferences.simklAccessToken.isNotBlank()
    }

    private fun hubOverview(context: android.content.Context): String =
        IntegrationStatus.hubOverview(context)

    private fun defaultSummaryRes(key: String): Int = when (key) {
        "TRAKT_CLIENT_ID" -> R.string.platform_trakt_client_id_summary
        "TRAKT_CLIENT_SECRET" -> R.string.platform_trakt_client_secret_summary
        "TRAKT_ACCESS_TOKEN" -> R.string.platform_trakt_token_summary
        "JELLYFIN_BASE_URL" -> R.string.platform_jellyfin_url_summary
        "JELLYFIN_USER_ID" -> R.string.platform_jellyfin_user_id_summary
        "JELLYFIN_ACCESS_TOKEN" -> R.string.platform_jellyfin_token_summary
        "PLEX_BASE_URL" -> R.string.platform_plex_url_summary
        "PLEX_TOKEN" -> R.string.platform_plex_token_summary
        "REAL_DEBRID_TOKEN" -> R.string.platform_rd_token_summary
        "PREMIUMIZE_API_KEY" -> R.string.platform_premiumize_key_summary
        "ALLDEBRID_API_KEY" -> R.string.platform_alldebrid_key_summary
        "TORBOX_API_KEY" -> R.string.platform_torbox_key_summary
        "SIMKL_CLIENT_ID" -> R.string.platform_simkl_client_id_summary
        "SIMKL_ACCESS_TOKEN" -> R.string.platform_simkl_token_summary
        "OPENSUBTITLES_API_KEY" -> R.string.platform_opensubtitles_key_summary
        "OPENSUBTITLES_LANGUAGES" -> R.string.platform_opensubtitles_langs_summary
        else -> R.string.platform_settings_summary
    }

    private fun haptic(fragment: Fragment) {
        if (ExperimentalMobileDesign.enabled()) {
            runCatching { ExpMotion.hapticTap(fragment.requireView()) }
        }
    }
}
