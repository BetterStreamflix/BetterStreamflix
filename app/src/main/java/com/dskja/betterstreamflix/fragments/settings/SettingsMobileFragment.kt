package com.dskja.betterstreamflix.fragments.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.Group
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.navigation.fragment.findNavController
import com.dskja.betterstreamflix.BuildConfig
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.activities.main.MainMobileActivity
import com.dskja.betterstreamflix.activities.tools.QrScannerActivity
import com.dskja.betterstreamflix.activities.tools.ProviderWatchlistScrapeActivity
import com.dskja.betterstreamflix.sync.CastHandoffSettingsController
import com.dskja.betterstreamflix.sync.CastRoomSettingsController
import com.dskja.betterstreamflix.backup.BackupRestoreManager
import com.dskja.betterstreamflix.backup.ProviderBackupContext
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.download.DownloadQualityPreset
import com.dskja.betterstreamflix.download.DownloadRepository
import com.dskja.betterstreamflix.download.DownloadStorage
import com.dskja.betterstreamflix.download.DownloadStorageLocation
import com.dskja.betterstreamflix.download.DownloadTreeAccess
import com.dskja.betterstreamflix.download.StreamflixDownloadManager
import com.dskja.betterstreamflix.player.PlaybackVideoQuality
import com.dskja.betterstreamflix.providers.AnimeOnlineNinjaProvider
import com.dskja.betterstreamflix.providers.FrenchStreamProvider
import com.dskja.betterstreamflix.providers.GuardaFlixProvider
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.providers.ProviderConfigUrl
import com.dskja.betterstreamflix.providers.ProviderPortalUrl
import com.dskja.betterstreamflix.providers.MStreamProvider
import com.dskja.betterstreamflix.providers.SerienStreamProvider
import com.dskja.betterstreamflix.providers.StreamingCommunityProvider
import com.dskja.betterstreamflix.providers.TmdbProvider
import com.dskja.betterstreamflix.player.SerienStreamBypassHelper
import com.dskja.betterstreamflix.utils.AccessibilityPreferences
import com.dskja.betterstreamflix.utils.AppLanguageManager
import com.dskja.betterstreamflix.utils.CatalogSortMode
import com.dskja.betterstreamflix.utils.SearchSortMode
import com.dskja.betterstreamflix.utils.CrashReporter
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ProviderChangeNotifier
import com.dskja.betterstreamflix.ui.UserDataNotifier
import com.dskja.betterstreamflix.utils.ThemeManager
import com.dskja.betterstreamflix.utils.UserDataCache
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsMobileFragment : PreferenceFragmentCompat() {
    private fun alertBuilder() =
        if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(requireContext())
        } else {
            AlertDialog.Builder(requireContext())
        }

    private data class SettingsScreenState(
        val rootKey: String?,
        val title: String?,
    )

    private val DEFAULT_DOMAIN_VALUE = "streamingunity.win"
    private val DEFAULT_SERIENSTREAM_DOMAIN_VALUE = "186.2.175.5"
    private val DEFAULT_MOFLIX_DOMAIN_VALUE = "moflix-stream.xyz"
    private val DEFAULT_CUEVANA_DOMAIN_VALUE = "cuevana3.gs"
    private val DEFAULT_POSEIDON_DOMAIN_VALUE = "www.poseidonhd2.co"
    private val PREFS_ERROR_VALUE = "PREFS_NOT_INIT_ERROR"
    private var currentScreenState = SettingsScreenState(rootKey = null, title = null)
    private val screenBackStack = ArrayDeque<SettingsScreenState>()
    private lateinit var settingsBackCallback: OnBackPressedCallback

    private lateinit var backupRestoreManager: BackupRestoreManager
    private var backupLoadingDialog: AlertDialog? = null
    private var settingsHubController: SettingsHubController? = null

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                performBackupExport(it)
            }
        }
    }

    private val exportDbBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                performDatabaseBackupExport(it)
            }
        }
    }

    private val importDbBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                performDatabaseBackupImport(it)
            }
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                performBackupImport(it)
            }
        }
    }

    private val pickDownloadFolderLauncher = registerForActivityResult(
        DownloadTreeAccess.PersistableOpenDocumentTree()
    ) { uri: Uri? ->
        DownloadsSettingsController.onDownloadFolderPicked(this, { key -> findPreference(key) }, uri)
    }

    private val scanResolverQrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@registerForActivityResult
        }

        val rawValue = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_VALUE).orEmpty()
        val target = com.dskja.betterstreamflix.providers.SerienStreamResolveLink.parse(
            rawValue,
            UserPreferences.bypassWsAdvertisedHost,
        )
        val uri = target?.toDeepLink()?.let(Uri::parse)

        if (uri == null) {
            showSettingsInfo(
                getString(R.string.settings_scan_resolver_invalid_qr),
                R.string.settings_scan_resolver_qr_title,
            )
            return@registerForActivityResult
        }

        val intent = Intent(requireContext(), MainMobileActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = uri
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private val scanCastRoomQrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val rawValue = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_VALUE)
        activity?.let { CastRoomSettingsController.onScanResult(it, rawValue) }
    }

    private val scanRemoteFindQrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val rawValue = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_VALUE)
        activity?.let {
            com.dskja.betterstreamflix.remotefind.RemoteFindSettingsController.onScanResult(it, rawValue)
        }
    }

    private var pendingProfileExportJson: String? = null

    private val exportProfileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val json = pendingProfileExportJson
        pendingProfileExportJson = null
        if (uri == null || json == null) return@registerForActivityResult
        val ok = com.dskja.betterstreamflix.profiletransfer.ProfileTransferUi
            .writeJsonToUri(requireContext(), uri, json)
        showSettingsInfo(
            getString(
                if (ok) R.string.profile_transfer_export_saved
                else R.string.profile_transfer_export_failed_generic,
            ),
            R.string.profile_transfer_export_title,
        )
    }

    private val importProfileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        activity?.let {
            com.dskja.betterstreamflix.profiletransfer.ProfileTransferUi.importFromFileUri(it, uri)
        }
    }

    private val scanProfileQrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val rawValue = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_VALUE)
        activity?.let {
            com.dskja.betterstreamflix.profiletransfer.ProfileTransferUi.handleIncomingRaw(it, rawValue)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        currentScreenState = SettingsScreenState(rootKey = rootKey, title = null)
        renderCurrentScreen()

        val allProvidersToBackup = Provider.providers.keys.toMutableList().apply {
            listOf("it", "en", "es", "de", "fr").forEach { lang ->
                add(TmdbProvider(lang))
            }
        }

        backupRestoreManager = BackupRestoreManager(
            requireContext(),
            allProvidersToBackup.map { provider ->
                ProviderBackupContext(
                    name = provider.name,
                    provider = provider,
                )
            }
        )

        displaySettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (screenBackStack.isEmpty()) return
                currentScreenState = screenBackStack.removeLast()
                settingsBackCallback.isEnabled = screenBackStack.isNotEmpty()
                renderCurrentScreen()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, settingsBackCallback)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        SettingsListStyler.attach(view, isTv = false)
        ensureSettingsHub(view)
        consumeSettingsDeepLink()
        // Reserve a small top lane so the floating search chip (top-right)
        // never overlaps the first preference row.
        listView?.let { list ->
            val topSpace = (64 * resources.displayMetrics.density).toInt()
            if (list.paddingTop < topSpace) {
                list.clipToPadding = false
                list.setPadding(list.paddingLeft, topSpace, list.paddingRight, list.paddingBottom)
            }
        }
    }

    private fun consumeSettingsDeepLink() {
        val key = SettingsDeepLink.consumePendingScreenKey() ?: return
        val title = findPreference<PreferenceScreen>(key)?.title?.toString()
            ?: when (key) {
                "screen_serienstream_auth" -> getString(R.string.serienstream_auth_category_title)
                "screen_aniworld_auth" -> getString(R.string.aniworld_auth_category_title)
                "screen_guardaflix_auth" -> getString(R.string.settings_category_guardaflix)
                "screen_notifications" -> getString(R.string.settings_screen_notifications)
                "screen_parent_dashboard" -> getString(R.string.parent_dashboard_title)
                else -> getString(R.string.settings_screen_downloads)
            }
        // Deep links (e.g. provider picker gear) must open even when the root
        // preference is hidden for the currently selected catalog provider.
        findPreference<Preference>(key)?.isVisible = true
        view?.post { openNestedSettingsScreen(key, title) }
    }

    override fun onDestroyView() {
        settingsHubController?.detach()
        settingsHubController = null
        super.onDestroyView()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference is PreferenceScreen && !preference.key.isNullOrBlank()) {
            openNestedSettingsScreen(
                key = preference.key!!,
                title = preference.title?.toString()
                    ?: getString(R.string.player_settings_title),
            )
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun ensureSettingsHub(view: View) {
        // Hub / Lumina shell is intentionally retained in-tree but retired at runtime.
        if (!ExperimentalMobileDesign.enabled()) {
            settingsHubController?.detach()
            settingsHubController = null
            return
        }
        val controller = settingsHubController ?: SettingsHubController(
            fragment = this,
            currentRootKey = { currentScreenState.rootKey },
            onOpenPreferenceScreen = { key, title -> openNestedSettingsScreen(key, title) },
            onOpenSupport = {
                runCatching { findNavController().navigate(R.id.support) }
            },
            onOpenAbout = {
                runCatching { findNavController().navigate(R.id.settings_about) }
            },
        ).also { settingsHubController = it }
        controller.attach(view)
    }

    private fun openNestedSettingsScreen(key: String, title: String) {
        screenBackStack.addLast(currentScreenState)
        currentScreenState = SettingsScreenState(rootKey = key, title = title)
        settingsBackCallback.isEnabled = screenBackStack.isNotEmpty()
        renderCurrentScreen()
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference.key == "PARENTAL_CONTROL_PIN" ||
            preference.key == "PARENTAL_CONTROL_ADMIN_PIN" ||
            preference.key == "PROFILE_PIN"
        ) {
            return
        }
        if (childFragmentManager.isStateSaved) return
        super.onDisplayPreferenceDialog(preference)
        if (ExperimentalMobileDesign.enabled()) {
            view?.post {
                val dialog = (childFragmentManager
                    .findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG")
                    as? androidx.fragment.app.DialogFragment)?.dialog
                if (dialog != null) {
                    // Dialog is already shown — OnShowListener would never fire.
                    ExpDialogChrome.polishShown(dialog)
                    dialog.window?.setBackgroundDrawableResource(
                        ExperimentalMobileDesign.dialogBackground(),
                    )
                    dialog.window?.decorView?.let { decor ->
                        ExperimentalMobileDesign.applyReducedGlass(decor)
                        ExpMotion.enterScreen(decor)
                        fun styleFields(group: android.view.ViewGroup) {
                            for (i in 0 until group.childCount) {
                                val child = group.getChildAt(i)
                                when (child) {
                                    is android.widget.EditText ->
                                        child.setBackgroundResource(
                                            ExperimentalMobileDesign.searchFieldBackground(),
                                        )
                                    is android.widget.CheckedTextView ->
                                        child.setBackgroundResource(
                                            if (child.isChecked) {
                                                ExperimentalMobileDesign.chipBackground()
                                            } else {
                                                ExperimentalMobileDesign.optionItemBackground()
                                            },
                                        )
                                    is android.view.ViewGroup -> styleFields(child)
                                }
                            }
                        }
                        if (decor is android.view.ViewGroup) styleFields(decor)
                    }
                }
            }
        }
    }

    private fun applyScreenTitle() {
        activity?.title = currentScreenState.title ?: getString(R.string.player_settings_title)
    }

    private fun injectNestedBackPreference() {
        val screen = preferenceScreen ?: return
        findPreference<Preference>("SETTINGS_NESTED_BACK")?.let { screen.removePreference(it) }
        val nestedKey = currentScreenState.rootKey ?: return
        if (nestedKey == "screen_platform") return
        val screenTitle = currentScreenState.title
            ?: getString(R.string.player_settings_title)
        val back = object : Preference(requireContext()) {
            override fun onBindViewHolder(holder: androidx.preference.PreferenceViewHolder) {
                super.onBindViewHolder(holder)
                holder.itemView.findViewById<android.widget.TextView>(R.id.tv_settings_nested_title)
                    ?.text = screenTitle
                holder.itemView.findViewById<android.view.View>(R.id.btn_settings_nested_back)
                    ?.setOnClickListener { performClick() }
            }
        }.apply {
            key = "SETTINGS_NESTED_BACK"
            layoutResource = R.layout.header_settings_nested_back
            isSelectable = true
            order = Int.MIN_VALUE / 2
            setOnPreferenceClickListener {
                if (screenBackStack.isEmpty()) return@setOnPreferenceClickListener true
                currentScreenState = screenBackStack.removeLast()
                settingsBackCallback.isEnabled = screenBackStack.isNotEmpty()
                renderCurrentScreen()
                true
            }
        }
        screen.addPreference(back)
    }

    private fun renderCurrentScreen() {
        val requestedKey = currentScreenState.rootKey
        try {
            setPreferencesFromResource(R.xml.settings_mobile, requestedKey)
        } catch (e: Exception) {
            Log.e("SettingsMobile", "Failed to inflate settings screen key=$requestedKey", e)
            if (requestedKey == null) throw e
            // Stale/missing nested key — recover to root instead of crashing Settings.
            currentScreenState = SettingsScreenState(rootKey = null, title = null)
            screenBackStack.clear()
            if (::settingsBackCallback.isInitialized) {
                settingsBackCallback.isEnabled = false
            }
            setPreferencesFromResource(R.xml.settings_mobile, null)
        }
        // BETTERSTREAMFLIX-K: nested screens must not keep cross-screen dependencies.
        SettingsPreferenceSanitizer.clearBrokenDependencies(preferenceScreen)
        injectNestedBackPreference()
        if (::backupRestoreManager.isInitialized) {
            displaySettings()
        }
        applyScreenTitle()
        view?.let { ensureSettingsHub(it) }
        settingsHubController?.updateVisibility()
        if (ExperimentalMobileDesign.enabled()) {
            view?.let { root ->
                if (root.getTag(R.id.exp_enter_animated_tag) != true) {
                    root.setTag(R.id.exp_enter_animated_tag, true)
                    ExpMotion.enterScreen(root)
                }
            }
            if (currentScreenState.rootKey != null) {
                listView?.let { list ->
                    ExpMotion.startAnimation(list, R.anim.support_fade_slide_up)
                    if (list.getTag(R.id.exp_enter_animated_tag) != currentScreenState.rootKey) {
                        list.setTag(R.id.exp_enter_animated_tag, currentScreenState.rootKey)
                        ExpMotion.staggerFirstFill(list)
                    }
                }
            }
        }
    }

    private fun displaySettings() {
        ProfileScopedPreferenceStore.attach(this)
        updateOverviewLabels()
        updateProviderVisibilityState()
        SupabaseSettingsController.bind(this, lifecycleScope) { key ->
            findPreference(key)
        }
        com.dskja.betterstreamflix.sync.WatchPartySettingsController.bind(
            fragment = this,
            findPreference = { key -> findPreference(key) },
        )
        CastRoomSettingsController.bind(
            fragment = this,
            scope = lifecycleScope,
            findPreference = { key -> findPreference(key) },
            scanLauncher = { intent ->
                runCatching { scanCastRoomQrLauncher.launch(intent) }
                    .onFailure {
                        showSettingsInfo(
                            getString(R.string.cast_room_scan_unavailable),
                            R.string.cast_room_join_scan_title,
                        )
                    }
            },
        )
        com.dskja.betterstreamflix.sync.SessionHandoffSettingsController.bind(
            fragment = this,
            findPreference = { key -> findPreference(key) },
        )
        com.dskja.betterstreamflix.remotefind.RemoteFindSettingsController.bind(
            fragment = this,
            findPreference = { key -> findPreference(key) },
            scanLauncher = { intent ->
                runCatching { scanRemoteFindQrLauncher.launch(intent) }
                    .onFailure {
                        showSettingsInfo(
                            getString(R.string.remote_find_scan_unavailable),
                            R.string.remote_find_join_scan_title,
                        )
                    }
            },
        )
        CastHandoffSettingsController.bind(
            fragment = this,
            findPreference = { key -> findPreference(key) },
        )
        PlatformSettingsController.bind(this, lifecycleScope) { key ->
            findPreference(key)
        }
        // Parent Dashboard is only meaningful while a kids profile exists.
        val hasKidsProfile = com.dskja.betterstreamflix.profiles.ProfileManager
            .profiles().any { it.isKids }
        findPreference<PreferenceScreen>("screen_parent_dashboard")?.isVisible = hasKidsProfile
        if (hasKidsProfile) {
            com.dskja.betterstreamflix.kids.ParentDashboardController.bind(
                fragment = this,
                scope = lifecycleScope,
                findPreference = { key -> findPreference(key) },
                onOpenDashboard = {
                    openNestedSettingsScreen(
                        "screen_parent_dashboard",
                        getString(R.string.parent_dashboard_title),
                    )
                },
            )
        }
        ProfilesSettingsController.bind(
            fragment = this,
            scope = lifecycleScope,
            findPreference = { key -> findPreference(key) },
            onProfileSwitched = {
                requireActivity().apply {
                    finish()
                    startActivity(Intent(this, MainMobileActivity::class.java))
                }
            },
            onExportProfile = {
                com.dskja.betterstreamflix.profiletransfer.ProfileTransferUi.exportFromSettings(this) { fileName, json ->
                    pendingProfileExportJson = json
                    runCatching { exportProfileLauncher.launch(fileName) }
                        .onFailure {
                            pendingProfileExportJson = null
                            showSettingsInfo(
                                getString(R.string.profile_transfer_export_failed_generic),
                                R.string.profile_transfer_export_title,
                            )
                        }
                }
            },
            onImportProfile = {
                com.dskja.betterstreamflix.profiletransfer.ProfileTransferUi.importMenu(
                    fragment = this,
                    openFile = {
                        runCatching {
                            importProfileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        }
                    },
                    scanQr = {
                        runCatching {
                            scanProfileQrLauncher.launch(
                                Intent(requireContext(), QrScannerActivity::class.java),
                            )
                        }.onFailure {
                            showSettingsInfo(
                                getString(R.string.profile_transfer_scan_failed),
                                R.string.profile_transfer_import_title,
                            )
                        }
                    },
                )
            },
            onOpenGlobalIntegrations = {
                openNestedSettingsScreen(
                    "screen_platform",
                    getString(R.string.platform_settings_title),
                )
            },
        )
        ConnectionServicesController.bind(
            fragment = this,
            scope = lifecycleScope,
            findPreference = { key -> findPreference(key) },
            openScreen = { key ->
                val title = findPreference<Preference>(key)?.title?.toString()
                    ?: getString(R.string.settings_category_network_title)
                openNestedSettingsScreen(key, title)
            },
            onScanResolverQr = {
                runCatching {
                    scanResolverQrLauncher.launch(Intent(requireContext(), QrScannerActivity::class.java))
                }.onFailure {
                    showSettingsInfo(
                        getString(R.string.settings_scan_resolver_failed),
                        R.string.settings_scan_resolver_qr_title,
                    )
                }
            },
            onDohChanged = {
                if (UserPreferences.currentProvider is StreamingCommunityProvider) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        (UserPreferences.currentProvider as StreamingCommunityProvider).rebuildService()
                        requireActivity().apply {
                            finish()
                            startActivity(Intent(this, this::class.java))
                        }
                    }
                } else {
                    showSettingsInfo(getString(R.string.doh_provider_updated))
                }
            },
        )

        findPreference<EditTextPreference>("provider_streamingcommunity_domain")?.apply {
            val currentValue = UserPreferences.streamingcommunityDomain
            summary = currentValue
            if (currentValue == DEFAULT_DOMAIN_VALUE || currentValue == PREFS_ERROR_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val typed = (newValue as String).trim()
                val BLOCKED = listOf("streamingcommunityz.green", "streamingunity.club", "streamingunity.bike", "streamingcommunityz.buzz")
                val effectiveDomain = if (BLOCKED.any { typed.contains(it) }) DEFAULT_DOMAIN_VALUE else typed
                UserPreferences.streamingcommunityDomain = effectiveDomain
                preference.setSummaryText(effectiveDomain)
                if (effectiveDomain != typed) {
                    findPreference<EditTextPreference>("provider_streamingcommunity_domain")?.text = null
                    showSettingsInfo(getString(R.string.settings_streamingcommunity_domain_blocked))
                }
                if (UserPreferences.currentProvider is StreamingCommunityProvider) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        (UserPreferences.currentProvider as StreamingCommunityProvider).rebuildService()
                        requireActivity().apply {
                            finish()
                            startActivity(Intent(this, this::class.java))
                        }
                    }
                }
                true
            }
        }

        findPreference<Preference>("provider_streamingcommunity_domain_reset")?.setOnPreferenceClickListener {
            UserPreferences.streamingcommunityDomain = DEFAULT_DOMAIN_VALUE
            findPreference<EditTextPreference>("provider_streamingcommunity_domain")?.apply {
                summary = DEFAULT_DOMAIN_VALUE
                text = null
            }
            showSettingsInfo(getString(R.string.settings_streamingcommunity_domain_reset_done))
            if (UserPreferences.currentProvider is StreamingCommunityProvider) {
                viewLifecycleOwner.lifecycleScope.launch {
                    (UserPreferences.currentProvider as StreamingCommunityProvider).rebuildService()
                    requireActivity().apply {
                        finish()
                        startActivity(Intent(this, this::class.java))
                    }
                }
            }
            true
        }

        findPreference<EditTextPreference>("provider_serienstream_domain")?.apply {
            val currentValue = UserPreferences.serienstreamDomain
            summary = getString(R.string.settings_serienstream_domain_current, currentValue)
            if (currentValue == DEFAULT_SERIENSTREAM_DOMAIN_VALUE || currentValue == PREFS_ERROR_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val typed = (newValue as String).trim()
                val effectiveDomain = typed.ifBlank { DEFAULT_SERIENSTREAM_DOMAIN_VALUE }
                UserPreferences.serienstreamDomain = effectiveDomain
                preference.setSummaryText(getString(R.string.settings_serienstream_domain_current, effectiveDomain))
                if (UserPreferences.currentProvider is SerienStreamProvider) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        SerienStreamProvider.reloadService()
                        requireActivity().apply {
                            finish()
                            startActivity(Intent(this, this::class.java))
                        }
                    }
                }
                true
            }
        }

        findPreference<Preference>("provider_serienstream_domain_reset")?.setOnPreferenceClickListener {
            UserPreferences.serienstreamDomain = DEFAULT_SERIENSTREAM_DOMAIN_VALUE
            findPreference<EditTextPreference>("provider_serienstream_domain")?.apply {
                summary = getString(R.string.settings_serienstream_domain_current, DEFAULT_SERIENSTREAM_DOMAIN_VALUE)
                text = null
            }
            showSettingsInfo(getString(R.string.settings_serienstream_domain_reset_done))
            if (UserPreferences.currentProvider is SerienStreamProvider) {
                viewLifecycleOwner.lifecycleScope.launch {
                    SerienStreamProvider.reloadService()
                    requireActivity().apply {
                        finish()
                        startActivity(Intent(this, this::class.java))
                    }
                }
            }
            true
        }

        findPreference<EditTextPreference>("provider_moflix_domain")?.apply {
            val currentValue = UserPreferences.moflixDomain
            summary = currentValue
            if (currentValue == DEFAULT_MOFLIX_DOMAIN_VALUE || currentValue == PREFS_ERROR_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val typed = (newValue as String).trim()
                val effectiveDomain = typed.ifBlank { DEFAULT_MOFLIX_DOMAIN_VALUE }
                UserPreferences.moflixDomain = effectiveDomain
                preference.setSummaryText(effectiveDomain)
                true
            }
        }

        findPreference<Preference>("provider_moflix_domain_reset")?.setOnPreferenceClickListener {
            UserPreferences.moflixDomain = DEFAULT_MOFLIX_DOMAIN_VALUE
            findPreference<EditTextPreference>("provider_moflix_domain")?.apply {
                summary = DEFAULT_MOFLIX_DOMAIN_VALUE
                text = null
            }
            showSettingsInfo(getString(R.string.settings_moflix_domain_reset_done))
            true
        }

        findPreference<EditTextPreference>("provider_cuevana_domain")?.apply {
            val currentValue = UserPreferences.cuevanaDomain
            summary = currentValue
            if (currentValue == DEFAULT_CUEVANA_DOMAIN_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val newDomainFromDialog = newValue as String
                UserPreferences.cuevanaDomain = newDomainFromDialog
                preference.setSummaryText(UserPreferences.cuevanaDomain)
                if (UserPreferences.currentProvider?.name == "Cuevana 3") {
                    requireActivity().apply {
                        finish()
                        startActivity(Intent(this, this::class.java))
                    }
                }
                true
            }
        }

        findPreference<EditTextPreference>("provider_poseidon_domain")?.apply {
            val currentValue = UserPreferences.poseidonDomain
            summary = currentValue
            if (currentValue == DEFAULT_POSEIDON_DOMAIN_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val newDomainFromDialog = newValue as String
                UserPreferences.poseidonDomain = newDomainFromDialog
                preference.setSummaryText(UserPreferences.poseidonDomain)
                if (UserPreferences.currentProvider?.name == "Poseidonhd2") {
                    requireActivity().apply {
                        finish()
                        startActivity(Intent(this, this::class.java))
                    }
                }
                true
            }
        }

        bindAnimeOnlineNinjaPreferredServer()
        GuardaFlixAuthSettingsController.bind(this, lifecycleScope) { key ->
            findPreference(key)
        }
        SerienStreamAuthSettingsController.bind(this, lifecycleScope) { key ->
            findPreference(key)
        }
        AniWorldAuthSettingsController.bind(this, lifecycleScope) { key ->
            findPreference(key)
        }
        TmdbSettingsController.bind(this, lifecycleScope) { key ->
            findPreference(key)
        }
        NotificationsSettingsController.bind(this) { key -> findPreference(key) }
        findPreference<Preference>("NOTIFICATIONS_OPEN")?.setOnPreferenceClickListener {
            openNestedSettingsScreen(
                "screen_notifications",
                getString(R.string.settings_screen_notifications),
            )
            true
        }

        findPreference<Preference>("p_serienstream_account_open")?.setOnPreferenceClickListener {
            openNestedSettingsScreen(
                "screen_serienstream_auth",
                getString(R.string.serienstream_auth_category_title),
            )
            true
        }
        findPreference<Preference>("p_aniworld_account_open")?.setOnPreferenceClickListener {
            openNestedSettingsScreen(
                "screen_aniworld_auth",
                getString(R.string.aniworld_auth_category_title),
            )
            true
        }

        findPreference<EditTextPreference>("TMDB_API_KEY")?.apply {
            summary = if (UserPreferences.tmdbApiKey.isEmpty()) getString(R.string.settings_tmdb_api_key_summary) else UserPreferences.tmdbApiKey
            text = UserPreferences.tmdbApiKey
            setOnPreferenceChangeListener { _, newValue ->
                val newKey = (newValue as String).trim()
                UserPreferences.tmdbApiKey = newKey
                summary = if (newKey.isEmpty()) getString(R.string.settings_tmdb_api_key_summary) else newKey
                val message = if (newKey.isEmpty()) {
                    getString(R.string.settings_tmdb_api_key_reset)
                } else {
                    getString(R.string.settings_tmdb_api_key_success)
                }
                showSettingsInfo(message)
                TmdbSettingsController.bind(this@SettingsMobileFragment, lifecycleScope) { key ->
                    findPreference(key)
                }
                true
            }
        }

        findPreference<Preference>("p_settings_support")?.apply {
            val titleStr = getString(R.string.support_settings_entry_title)
            val spannableTitle = SpannableString(titleStr)
            spannableTitle.setSpan(
                ForegroundColorSpan(
                    com.google.android.material.color.MaterialColors.getColor(
                        requireContext(),
                        androidx.appcompat.R.attr.colorPrimary,
                        requireContext().getColor(R.color.m3_primary),
                    ),
                ),
                0,
                titleStr.length,
                0,
            )
            title = spannableTitle
            setOnPreferenceClickListener {
                runCatching {
                    findNavController().navigate(R.id.support)
                }
                true
            }
        }

        bindExperimentalDesignGate()

        findPreference<Preference>("p_settings_about")?.apply {
            val palette = ThemeManager.palette(UserPreferences.selectedTheme)
            val titleStr = getString(R.string.settings_about)
            val spannableTitle = SpannableString(titleStr)
            spannableTitle.setSpan(ForegroundColorSpan(palette.tvHeaderPrimary), 0, titleStr.length, 0)
            title = spannableTitle
            
            val summaryStr = getString(R.string.settings_about_version_name, BuildConfig.VERSION_NAME)
            val spannableSummary = SpannableString(summaryStr)
            spannableSummary.setSpan(ForegroundColorSpan(palette.tvHeaderSecondary), 0, summaryStr.length, 0)
            summary = spannableSummary
            
            isSelectable = true
            setOnPreferenceClickListener {
                runCatching {
                    findNavController().navigate(R.id.settings_about)
                }
                true
            }
        }

        findPreference<Preference>("p_settings_help")?.setOnPreferenceClickListener {
            com.dskja.betterstreamflix.support.SupportLinkOpener.open(
                requireContext(),
                com.dskja.betterstreamflix.support.SupportUrls.GITHUB_REPOSITORY_URL,
            )
            true
        }

        findPreference<Preference>("p_settings_telegram")?.setOnPreferenceClickListener {
            com.dskja.betterstreamflix.support.SupportLinkOpener.openTelegram(requireContext())
            true
        }

        findPreference<Preference>("p_settings_telegram_gate_reset")?.let { pref ->
            pref.isVisible = BuildConfig.DEBUG
            pref.setOnPreferenceClickListener {
                com.dskja.betterstreamflix.telegram.TelegramJoinGatePolicy.resetForDebug()
                com.dskja.betterstreamflix.telegram.TelegramJoinGateController.resetLaunchState()
                android.widget.Toast.makeText(
                    requireContext(),
                    R.string.settings_telegram_gate_reset_done,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                val host = activity
                if (host is androidx.fragment.app.FragmentActivity) {
                    com.dskja.betterstreamflix.telegram.TelegramJoinGateOverlay.show(host)
                }
                true
            }
        }

        findPreference<Preference>("p_settings_buy_me_a_coffee")?.setOnPreferenceClickListener {
            com.dskja.betterstreamflix.support.SupportLinkOpener.openProvider(
                requireContext(),
                com.dskja.betterstreamflix.support.SupportProvider.BUY_ME_A_COFFEE,
            )
            true
        }

        findPreference<Preference>("p_settings_patreon")?.setOnPreferenceClickListener {
            com.dskja.betterstreamflix.support.SupportLinkOpener.openProvider(
                requireContext(),
                com.dskja.betterstreamflix.support.SupportProvider.PATREON,
            )
            true
        }

        findPreference<Preference>("p_settings_discord")?.setOnPreferenceClickListener {
            com.dskja.betterstreamflix.support.SupportLinkOpener.openProvider(
                requireContext(),
                com.dskja.betterstreamflix.support.SupportProvider.DISCORD,
            )
            true
        }

        findPreference<SwitchPreference>("AUTOPLAY")?.isChecked = UserPreferences.autoplay
        findPreference<SwitchPreference>("AUTOPLAY")?.setOnPreferenceChangeListener { _, newValue ->
            UserPreferences.autoplay = newValue as Boolean
            true
        }

        DownloadsSettingsController.bind(
            this,
            lifecycleScope,
            findPreference = { key -> findPreference(key) },
            onPickDownloadFolder = {
                // Some Leanback / OEM builds have no OPEN_DOCUMENT_TREE handler
                // (BETTERSTREAMFLIX-41 ActivityNotFoundException).
                runCatching { pickDownloadFolderLauncher.launch(null) }
                    .onFailure {
                        showSettingsInfo(
                            getString(R.string.settings_download_storage_folder_unavailable),
                            R.string.settings_download_storage_pick_folder,
                        )
                    }
            },
        )

        findPreference<SwitchPreference>("FORCE_EXTRA_BUFFERING")?.apply {
            isChecked = UserPreferences.forceExtraBuffering
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.forceExtraBuffering = newValue as Boolean
                true
            }
        }

        findPreference<SwitchPreference>("DATA_SAVER_MODE")?.apply {
            isChecked = UserPreferences.dataSaverMode
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.dataSaverMode = newValue as Boolean
                true
            }
        }

        findPreference<ListPreference>("PLAYBACK_VIDEO_QUALITY")?.apply {
            value = UserPreferences.playbackVideoQuality.name
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.playbackVideoQuality =
                    PlaybackVideoQuality.fromKey(newValue as String)
                true
            }
        }

        findPreference<SeekBarPreference>("AUTOPLAY_BUFFER")?.apply {
            value = UserPreferences.autoplayBuffer.toInt().coerceIn(0, 30)
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.autoplayBuffer = (newValue as Int).toLong()
                true
            }
        }

        findPreference<SwitchPreference>("PLAYER_GESTURES")?.isChecked = UserPreferences.playerGestures
        findPreference<SwitchPreference>("PLAYER_GESTURES")?.setOnPreferenceChangeListener { _, newValue ->
            UserPreferences.playerGestures = newValue as Boolean
            true
        }

        findPreference<SwitchPreference>("KEEP_SCREEN_ON_WHEN_PAUSED")?.isChecked = UserPreferences.keepScreenOnWhenPaused
        findPreference<SwitchPreference>("KEEP_SCREEN_ON_WHEN_PAUSED")?.setOnPreferenceChangeListener { _, newValue ->
            UserPreferences.keepScreenOnWhenPaused = newValue as Boolean
            true
        }

        findPreference<SwitchPreference>("ALLOW_PHONE_PORTRAIT_PLAYBACK")?.isChecked =
            UserPreferences.allowPhonePortraitPlayback
        findPreference<SwitchPreference>("ALLOW_PHONE_PORTRAIT_PLAYBACK")?.setOnPreferenceChangeListener { _, newValue ->
            UserPreferences.allowPhonePortraitPlayback = newValue as Boolean
            true
        }

        findPreference<SwitchPreference>("UPDATE_CHECK_ENABLED")?.isChecked = UserPreferences.updateCheckEnabled
        findPreference<SwitchPreference>("UPDATE_CHECK_ENABLED")?.setOnPreferenceChangeListener { _, newValue ->
            UserPreferences.updateCheckEnabled = newValue as Boolean
            true
        }

        findPreference<SwitchPreference>("SERVER_AUTO_SUBTITLES_DISABLED")?.apply {
            isChecked = UserPreferences.serverAutoSubtitlesDisabled
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.serverAutoSubtitlesDisabled = newValue as Boolean
                true
            }
        }

        val HasConfigProvider = UserPreferences.currentProvider is ProviderConfigUrl
        findPreference<PreferenceCategory>("pc_provider_settings")?.apply {
            isVisible = HasConfigProvider
        }

        if (HasConfigProvider) {
            val provider = UserPreferences.currentProvider
            val configProvider = provider as? ProviderConfigUrl
            val portalProvider = provider as? ProviderPortalUrl
            var autoUpdateVal = false

            findPreference<SwitchPreference>("provider_autoupdate")?.apply {
                isVisible = portalProvider != null
                if (isVisible) {
                    autoUpdateVal = UserPreferences
                        .getProviderCache(
                            provider ?: return@apply, UserPreferences
                                .PROVIDER_AUTOUPDATE
                        ) != "false"
                    isChecked = autoUpdateVal
                    setOnPreferenceChangeListener { _, newValue ->
                        val newState = newValue as Boolean
                        UserPreferences.setProviderCache(
                            null,
                            UserPreferences.PROVIDER_AUTOUPDATE,
                            newState.toString()
                        )
                        findPreference<EditTextPreference>("provider_url")?.isEnabled = newState == false
                        true
                    }
                }
            }

            findPreference<EditTextPreference>("provider_url")?.apply {
                isVisible = configProvider != null
                isEnabled = autoUpdateVal == false
                if (isVisible && provider != null && configProvider != null) {
                    summary = UserPreferences
                        .getProviderCache(
                            provider, UserPreferences
                                .PROVIDER_URL
                        )
                        .ifBlank { provider.defaultBaseUrl }
                    setOnBindEditTextListener { editText ->
                        editText.inputType = InputType.TYPE_CLASS_TEXT
                        editText.imeOptions = EditorInfo.IME_ACTION_DONE
                        editText.hint = configProvider.defaultBaseUrl
                        editText.setText(summary)
                        if (ExperimentalMobileDesign.enabled()) {
                            val pad = (20 * resources.displayMetrics.density).toInt()
                            editText.setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
                            editText.setPadding(pad, pad, pad, pad)
                            editText.setTextAppearance(R.style.TextAppearance_CinemaInk_Body)
                        }
                    }
                    setOnPreferenceChangeListener { _, newValue ->
                        val toSave = (newValue as String)
                            .ifBlank { configProvider.defaultBaseUrl }
                            .trim()
                            .removeSuffix("/") + "/"
                        UserPreferences.setProviderCache(
                            null,
                            UserPreferences.PROVIDER_URL,
                            toSave
                        )
                        summary = toSave
                        viewLifecycleOwner.lifecycleScope.launch {
                            configProvider.onChangeUrl()
                            ProviderChangeNotifier.notifyProviderChanged()
                        }
                        true
                    }
                }
            }

            findPreference<EditTextPreference>("provider_portal_url")?.apply {
                isVisible = portalProvider != null
                if (isVisible && provider != null && portalProvider != null) {
                    summary = UserPreferences
                        .getProviderCache(
                            provider, UserPreferences
                                .PROVIDER_PORTAL_URL
                        )
                        .ifBlank { portalProvider.defaultPortalUrl }
                    setOnBindEditTextListener { editText ->
                        editText.inputType = InputType.TYPE_CLASS_TEXT
                        editText.imeOptions = EditorInfo.IME_ACTION_DONE
                        editText.hint = portalProvider.defaultPortalUrl
                        editText.setText(summary)
                        if (ExperimentalMobileDesign.enabled()) {
                            val pad = (20 * resources.displayMetrics.density).toInt()
                            editText.setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
                            editText.setPadding(pad, pad, pad, pad)
                            editText.setTextAppearance(R.style.TextAppearance_CinemaInk_Body)
                        }
                    }
                    setOnPreferenceChangeListener { _, newValue ->
                        val toSave = (newValue as String)
                            .ifBlank { portalProvider.defaultPortalUrl }
                            .trim()
                            .removeSuffix("/") + "/"
                        summary = toSave
                        UserPreferences.setProviderCache(
                            null,
                            UserPreferences.PROVIDER_PORTAL_URL,
                            toSave
                        )
                        true
                    }
                }
            }

            findPreference<Preference>("provider_autoupdate_now")?.apply {
                isVisible = portalProvider != null || configProvider != null
                setOnPreferenceClickListener {
                    val cfg = configProvider ?: return@setOnPreferenceClickListener true
                    val provider = UserPreferences.currentProvider
                    viewLifecycleOwner.lifecycleScope.launch {
                        var summary = runCatching { cfg.onChangeUrl(true) }.getOrDefault(cfg.defaultBaseUrl)
                        // Follow redirects and persist into PROVIDER_URL (domain-config port).
                        if (provider != null) {
                            runCatching {
                                val result = com.dskja.betterstreamflix.utils.DomainRedirectChecker
                                    .check(summary.ifBlank { cfg.defaultBaseUrl })
                                if (result.changed) {
                                    val newUrl = result.finalUrl.trimEnd('/') + "/"
                                    UserPreferences.setProviderCache(
                                        provider,
                                        UserPreferences.PROVIDER_URL,
                                        newUrl,
                                    )
                                    summary = cfg.onChangeUrl(true)
                                }
                            }
                        }
                        findPreference<EditTextPreference>("provider_url")?.setSummaryText(summary)
                    }
                    true
                }
            }
        }

        findPreference<SwitchPreference>("pc_frenchstream_new_interface")?.apply {
            val frenchProvider = UserPreferences.currentProvider as? FrenchStreamProvider
            isVisible = frenchProvider != null
            if (frenchProvider != null) {
                val useNewInterface = UserPreferences
                    .getProviderCache(
                        frenchProvider, UserPreferences
                            .PROVIDER_NEW_INTERFACE
                    ) != "false"
                isChecked = useNewInterface
                setOnPreferenceChangeListener { _, newValue ->
                    val newState = newValue as Boolean
                    UserPreferences.setProviderCache(
                        null,
                        UserPreferences.PROVIDER_NEW_INTERFACE,
                        newState.toString()
                    )
                    true
                }
            }
        }

        // Single locked look — stored theme collapses onto Dark; no picker.
        if (UserPreferences.selectedTheme != ThemeManager.CINEMA_INK) {
            UserPreferences.selectedTheme = ThemeManager.CINEMA_INK
        }
        UserPreferences.oledMode = false

        findPreference<SwitchPreference>("STICK_ENERGY_MODE")?.apply {
            // SE-014: hide Stick Energy on phone unless leanback / Fire TV.
            val showEnergy = com.dskja.betterstreamflix.utils.DeviceCapabilities.isLeanbackDevice(requireContext()) ||
                com.dskja.betterstreamflix.utils.DeviceCapabilities.isAmazonFireTv(requireContext())
            isVisible = showEnergy
            if (!showEnergy) return@apply
            isPersistent = false
            isChecked = com.dskja.betterstreamflix.ui.StickEnergyMode.isEnabled(requireContext())
            summary = getString(R.string.settings_stick_energy_mode_summary)
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.stickEnergyMode = newValue as Boolean
                requireActivity().apply {
                    finish()
                    startActivity(Intent(this, MainMobileActivity::class.java))
                }
                true
            }
        }

        fun refreshCinemaRoomPreview() {
            (findPreference<Preference>("CINEMA_ROOM_PREVIEW")
                as? CinemaRoomPreviewPreference)?.refresh()
        }

        findPreference<SwitchPreferenceCompat>("CINEMA_ROOM")?.apply {
            isChecked = UserPreferences.cinemaRoomEnabled
            summary = getString(R.string.settings_cinema_room_mobile_summary)
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                UserPreferences.cinemaRoomEnabled = enabled
                if (enabled) {
                    com.dskja.betterstreamflix.ui.CinemaRoomController.onCinemaRoomEnabled()
                }
                findPreference<SwitchPreferenceCompat>("TV_AMBIENT_MODE")?.isEnabled = enabled
                findPreference<SwitchPreferenceCompat>("CINEMA_AMBIENT_LIGHT")?.isEnabled = enabled
                findPreference<ListPreference>("CINEMA_AMBIENT_IDLE_MS")?.isEnabled =
                    enabled && UserPreferences.tvAmbientModeEnabled
                refreshCinemaRoomPreview()
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("TV_AMBIENT_MODE")?.apply {
            isChecked = UserPreferences.tvAmbientModeEnabled
            isEnabled = UserPreferences.cinemaRoomEnabled
            summary = getString(R.string.settings_tv_ambient_mode_mobile_summary)
            setOnPreferenceChangeListener { _, newValue ->
                val ambient = newValue as Boolean
                UserPreferences.tvAmbientModeEnabled = ambient
                findPreference<ListPreference>("CINEMA_AMBIENT_IDLE_MS")?.isEnabled =
                    ambient && UserPreferences.cinemaRoomEnabled
                refreshCinemaRoomPreview()
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("CINEMA_AMBIENT_LIGHT")?.apply {
            isChecked = UserPreferences.cinemaAmbientLightHints
            isEnabled = UserPreferences.cinemaRoomEnabled
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.cinemaAmbientLightHints = newValue as Boolean
                refreshCinemaRoomPreview()
                true
            }
        }

        findPreference<ListPreference>("CINEMA_AMBIENT_IDLE_MS")?.apply {
            isEnabled = UserPreferences.cinemaRoomEnabled && UserPreferences.tvAmbientModeEnabled
            value = UserPreferences.cinemaAmbientIdleMs.toString()
            setSummaryProviderOnce(Preference.SummaryProvider<ListPreference> { pref ->
                pref.entry?.toString().orEmpty()
            })
            setOnPreferenceChangeListener { preference, newValue ->
                val ms = (newValue as? String)?.toLongOrNull() ?: return@setOnPreferenceChangeListener false
                UserPreferences.cinemaAmbientIdleMs = ms
                (preference as? ListPreference)?.value = ms.toString()
                refreshCinemaRoomPreview()
                true
            }
        }

        findPreference<SwitchPreference>("LARGE_TEXT")?.apply {
            isChecked = UserPreferences.largeText
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.largeText = newValue as Boolean
                requireActivity().apply {
                    finish()
                    startActivity(Intent(this, MainMobileActivity::class.java))
                }
                true
            }
        }

        findPreference<SwitchPreference>("HIGH_CONTRAST")?.apply {
            isChecked = UserPreferences.highContrast
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                UserPreferences.highContrast = enabled
                // ST-009: align Subtitle Studio captions with accessibility High contrast.
                AccessibilityPreferences.syncSubtitleStudioForHighContrast(enabled)
                requireActivity().apply {
                    finish()
                    startActivity(Intent(this, MainMobileActivity::class.java))
                }
                true
            }
        }

        SubtitleStudioSettingsController.bind(this)

        findPreference<ListPreference>("APP_LANGUAGE")?.apply {
            entries = AppLanguageManager.buildLanguageEntries(requireContext())
            entryValues = AppLanguageManager.buildLanguageValues(requireContext())
            value = AppLanguageManager.getSelectedLanguage(requireContext())
            setSummaryProviderOnce(Preference.SummaryProvider<ListPreference> { pref ->
                pref.entries.getOrNull(pref.findIndexOfValue(pref.value))
                    ?: getString(R.string.settings_app_language_system)
            })
            setOnPreferenceChangeListener { preference, newValue ->
                val newLanguage = newValue as String
                AppLanguageManager.setSelectedLanguage(newLanguage)
                if (preference is ListPreference) {
                    preference.value = newLanguage
                }
                requireActivity().apply {
                    finish()
                    startActivity(
                        Intent(this, MainMobileActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
                true
            }
        }

        findPreference<SwitchPreference>("IMMERSIVE_MODE")?.apply {
            isChecked = UserPreferences.immersiveMode
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.immersiveMode = newValue as Boolean
                (activity as? MainMobileActivity)?.updateImmersiveMode()
                true
            }
        }

        findPreference<ListPreference>("CATALOG_SORT_MODE")?.apply {
            value = UserPreferences.catalogSortMode.name
            setSummaryProviderOnce(ListPreference.SimpleSummaryProvider.getInstance())
            setOnPreferenceChangeListener { preference, newValue ->
                val mode = CatalogSortMode.fromKey(newValue as String)
                UserPreferences.catalogSortMode = mode
                if (preference is ListPreference) {
                    preference.value = mode.name
                }
                ProviderChangeNotifier.notifyProviderChanged()
                true
            }
        }

        findPreference<ListPreference>("SEARCH_SORT_MODE")?.apply {
            value = UserPreferences.searchSortMode.name
            setSummaryProviderOnce(ListPreference.SimpleSummaryProvider.getInstance())
            setOnPreferenceChangeListener { preference, newValue ->
                val mode = SearchSortMode.fromKey(newValue as String)
                UserPreferences.searchSortMode = mode
                if (preference is ListPreference) {
                    preference.value = mode.name
                }
                true
            }
        }

        findPreference<SwitchPreference>("CAST_ENABLED")?.apply {
            isChecked = UserPreferences.castEnabled
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                UserPreferences.castEnabled = enabled
                // Align with TV: warm CastContext when the user enables Cast (CAST-P3-2).
                if (enabled) {
                    runCatching {
                        com.dskja.betterstreamflix.cast.CastPlaybackHub.ensureCastContext(requireContext())
                    }
                }
                true
            }
        }

        findPreference<SwitchPreference>("CAST_SUBTITLES_ENABLED")?.apply {
            isChecked = UserPreferences.castSubtitlesEnabled
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.castSubtitlesEnabled = newValue as Boolean
                true
            }
        }
        findPreference<SwitchPreference>("CAST_KEEP_SCREEN_AWAKE")?.apply {
            isChecked = UserPreferences.castKeepScreenAwake
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.castKeepScreenAwake = newValue as Boolean
                true
            }
        }

        findPreference<Preference>("WATCHLIST_IMPORT_SERIENSTREAM")?.setOnPreferenceClickListener {
            startActivity(
                Intent(requireContext(), ProviderWatchlistScrapeActivity::class.java).putExtra(
                    ProviderWatchlistScrapeActivity.EXTRA_SOURCE,
                    ProviderWatchlistScrapeActivity.SOURCE_SERIENSTREAM,
                )
            )
            true
        }

        findPreference<Preference>("WATCHLIST_IMPORT_ANIWORLD")?.setOnPreferenceClickListener {
            startActivity(
                Intent(requireContext(), ProviderWatchlistScrapeActivity::class.java).putExtra(
                    ProviderWatchlistScrapeActivity.EXTRA_SOURCE,
                    ProviderWatchlistScrapeActivity.SOURCE_ANIWORLD,
                )
            )
            true
        }

        findPreference<SwitchPreferenceCompat>("ENABLE_TMDB")?.apply {
            isChecked = UserPreferences.enableTmdb
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                val applyChange = {
                    UserPreferences.enableTmdb = enabled
                    updateParentalControlPreferenceState()
                    ProviderChangeNotifier.notifyProviderChanged()
                    val message = if (enabled) {
                        getString(R.string.settings_enable_tmdb_enabled)
                    } else {
                        getString(R.string.settings_enable_tmdb_disabled)
                    }
                    showSettingsInfo(message)
                }

                if (!enabled && UserPreferences.parentalControlPin.isNotBlank()) {
                    changeParentalSettingWithPinCheck(onVerified = applyChange)
                    false
                } else {
                    applyChange()
                    true
                }
            }
        }

        findPreference<SwitchPreferenceCompat>("SHOW_QUARANTINED_PROVIDERS")?.apply {
            isChecked = UserPreferences.showQuarantinedProviders
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.showQuarantinedProviders = newValue as Boolean
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("SHOW_VAVOO_VOD_PROVIDERS")?.apply {
            isChecked = UserPreferences.showVavooVodProviders
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.showVavooVodProviders = newValue as Boolean
                true
            }
        }

        findPreference<Preference>("VIEW_CRASH_LOG")?.setOnPreferenceClickListener {
            com.dskja.betterstreamflix.ui.CrashLogDialog.show(requireContext())
            true
        }

        findPreference<Preference>("SEND_SENTRY_FEEDBACK")?.setOnPreferenceClickListener {
            val ctx = requireContext()
            val density = resources.displayMetrics.density
            val input = android.widget.EditText(ctx).apply {
                hint = getString(R.string.settings_send_sentry_feedback_hint)
                minLines = 3
                if (ExperimentalMobileDesign.enabled()) {
                    setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
                    setPadding(48, 36, 48, 36)
                    setTextAppearance(R.style.TextAppearance_CinemaInk_Body)
                } else {
                    setPadding(48, 32, 48, 32)
                }
            }
            val glass = if (ExperimentalMobileDesign.enabled()) {
                ExpDialogChrome.buildGlassMessage(
                    ctx,
                    getString(R.string.settings_send_sentry_feedback_hint),
                ).also { g ->
                    g.root.addView(
                        input,
                        android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).also { it.topMargin = (12 * density).toInt() },
                    )
                }
            } else {
                null
            }
            val builder = alertBuilder()
                .setTitle(R.string.settings_send_sentry_feedback_title)
            if (glass != null) builder.setView(glass.root)
            else builder.setView(input)
            builder
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val text = input.text?.toString().orEmpty()
                    if (text.isBlank()) {
                        showSettingsInfo(getString(R.string.settings_send_sentry_feedback_empty))
                    } else {
                        com.dskja.betterstreamflix.utils.SentryBootstrap.captureFeedback(
                            message = text,
                            email = com.dskja.betterstreamflix.sync.CloudSyncManager.currentUserEmail(),
                        )
                        showSettingsInfo(getString(R.string.settings_send_sentry_feedback_sent))
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    dialog.setOnShowListener {
                        if (glass != null) {
                            ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                            with(com.dskja.betterstreamflix.utils.ExpPressEffects) { input.applyExpPress() }
                            ExpMotion.popIn(input)
                        } else {
                            ExpDialogChrome.polishButtons(dialog)
                        }
                    }
                    dialog.show()
                }
            true
        }

        findPreference<ListPreference>("LIBRARY_SCOPE")?.apply {
            value = UserPreferences.libraryScope.key
            summary = entry
            setOnPreferenceChangeListener { preference, newValue ->
                val scope = UserPreferences.LibraryScope.fromKey(newValue as String)
                UserPreferences.libraryScope = scope
                (preference as ListPreference).value = scope.key
                preference.setSummaryText(preference.entry)
                UserDataNotifier.notifyChanged()
                ProviderChangeNotifier.notifyProviderChanged()
                showSettingsInfo(getString(R.string.settings_library_scope_updated))
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("SHOW_CONTINUE_WATCHING")?.apply {
            isChecked = UserPreferences.showContinueWatching
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.showContinueWatching = newValue as Boolean
                // Library shelf visibility only — avoid full provider getHome().
                UserDataNotifier.notifyChanged()
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("SHOW_RECENTLY_WATCHED")?.apply {
            isChecked = UserPreferences.showRecentlyWatched
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.showRecentlyWatched = newValue as Boolean
                UserDataNotifier.notifyChanged()
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("SHOW_NEXT_EPISODES")?.apply {
            isChecked = UserPreferences.showNextEpisodes
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.showNextEpisodes = newValue as Boolean
                UserDataNotifier.notifyChanged()
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("HOME_WATCHLIST_DIGEST_ENABLED")?.apply {
            isChecked = UserPreferences.homeWatchlistDigestEnabled
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                UserPreferences.homeWatchlistDigestEnabled = enabled
                if (enabled) {
                    // Re-enable clears dismiss + show-cap / timestamps so the weekly card can return.
                    com.dskja.betterstreamflix.watchlist.WatchlistDigestPolicy.resetForReEnable()
                }
                UserDataNotifier.notifyChanged()
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("SHOW_SMART_COLLECTIONS")?.apply {
            isChecked = UserPreferences.showSmartCollections
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.showSmartCollections = newValue as Boolean
                UserDataNotifier.notifyChanged()
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("SHOW_WATCHLIST_WEEK")?.apply {
            isChecked = UserPreferences.showWatchlistWeek
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.showWatchlistWeek = newValue as Boolean
                UserDataNotifier.notifyChanged()
                true
            }
        }

        findPreference<Preference>("HOME_REMIX_OPEN")?.setOnPreferenceClickListener {
            com.dskja.betterstreamflix.home.HomeRemixUi.show(this) {
                // Soft refresh already fired inside show; bump again so Home clears bind signature.
                UserDataNotifier.notifyChanged()
            }
            true
        }

        findPreference<Preference>("HOME_SHELF_PINS")?.apply {
            summary = com.dskja.betterstreamflix.home.HomeShelfPinUi.pinsSummary(requireContext())
            setOnPreferenceClickListener {
                com.dskja.betterstreamflix.home.HomeShelfPinUi.showPicker(requireContext()) {
                    summary = com.dskja.betterstreamflix.home.HomeShelfPinUi.pinsSummary(requireContext())
                }
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("CHANNEL_PIN_STRIP_ENABLED")?.apply {
            isChecked = UserPreferences.channelPinStripEnabled
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.channelPinStripEnabled = newValue as Boolean
                UserDataNotifier.notifyChanged()
                true
            }
        }
        findPreference<SwitchPreferenceCompat>("CHANNEL_PIN_STRIP_LIVE_ONLY")?.apply {
            isChecked = UserPreferences.channelPinStripLiveOnly
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.channelPinStripLiveOnly = newValue as Boolean
                UserDataNotifier.notifyChanged()
                true
            }
        }

        findPreference<Preference>("PROFILE_YEAR_REVIEW")?.setOnPreferenceClickListener {
            com.dskja.betterstreamflix.yearreview.YearReviewUi.show(this)
            true
        }

        findPreference<Preference>("TASTE_CLEAR_HIDDEN")?.setOnPreferenceClickListener {
            com.dskja.betterstreamflix.taste.TasteSignalsUi.showClearHidden(requireContext())
            true
        }

        findPreference<Preference>("TASTE_CLEAR_ALL")?.setOnPreferenceClickListener {
            com.dskja.betterstreamflix.taste.TasteSignalsUi.showClearAll(requireContext())
            true
        }

        findPreference<Preference>("CLEAR_RECENTLY_WATCHED")?.setOnPreferenceClickListener {
            val glass = if (ExperimentalMobileDesign.enabled()) {
                ExpDialogChrome.buildGlassMessage(
                    requireContext(),
                    getString(R.string.settings_clear_recently_watched_confirm_message),
                )
            } else {
                null
            }
            val builder = alertBuilder().setTitle(R.string.settings_clear_recently_watched_title)
            if (glass != null) builder.setView(glass.root)
            else builder.setMessage(R.string.settings_clear_recently_watched_confirm_message)
            builder
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            com.dskja.betterstreamflix.utils.CrossProviderLibrary
                                .clearRecentlyWatched(requireContext())
                        }
                        UserDataNotifier.notifyChanged()
                        ProviderChangeNotifier.notifyProviderChanged()
                        showSettingsInfo(getString(R.string.settings_clear_recently_watched_toast))
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    dialog.setOnShowListener {
                        if (glass != null) ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                        else ExpDialogChrome.polishButtons(dialog)
                    }
                    dialog.show()
                }
            true
        }

        setupParentalControlPreferences()

        findPreference<Preference>("key_backup_export_mobile")?.setOnPreferenceClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "streamflix_mobile_backup_$timestamp.json"
            exportBackupLauncher.launch(fileName)
            true
        }

        findPreference<Preference>("key_backup_import_mobile")?.setOnPreferenceClickListener {
            importBackupLauncher.launch(arrayOf("application/json"))
            true
        }

        findPreference<Preference>("preferred_player_reset")?.setOnPreferenceClickListener {
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .remove("preferred_smarttube_package")
                .apply()
            showSettingsInfo(getString(R.string.settings_trailer_player_reset))
            true
        }

        findPreference<Preference>("key_backup_refresh_cache_mobile")?.setOnPreferenceClickListener {
            val glass = if (ExperimentalMobileDesign.enabled()) {
                ExpDialogChrome.buildGlassMessage(
                    requireContext(),
                    getString(R.string.settings_refresh_cache_message),
                )
            } else {
                null
            }
            val builder = alertBuilder().setTitle(R.string.settings_refresh_cache_confirm)
            if (glass != null) builder.setView(glass.root)
            else builder.setMessage(R.string.settings_refresh_cache_message)
            builder
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val refreshed = backupRestoreManager.refreshCachesFromDatabase()
                        showSettingsInfo(
                            getString(
                                if (refreshed) R.string.settings_refresh_cache_success
                                else R.string.settings_refresh_cache_success,
                            ),
                        )
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    dialog.setOnShowListener {
                        if (glass != null) ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                        else ExpDialogChrome.polishButtons(dialog)
                    }
                    dialog.show()
                }
            true
        }

        findPreference<Preference>("key_backup_export_db_mobile")?.setOnPreferenceClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "streamflix_mobile_db_backup_$timestamp.zip"
            exportDbBackupLauncher.launch(fileName)
            true
        }

        findPreference<Preference>("key_backup_import_db_mobile")?.setOnPreferenceClickListener {
            importDbBackupLauncher.launch(arrayOf("application/zip"))
            true
        }
    }

    private fun updateOverviewLabels() {
        val providerName = UserPreferences.currentProvider?.name

        findPreference<PreferenceScreen>("screen_provider")?.apply {
            title = getString(R.string.settings_provider_connection_title)
            summary = providerName?.let {
                getString(R.string.settings_screen_provider_summary_with_name, it)
            } ?: getString(R.string.settings_screen_provider_summary)
        }

        findPreference<PreferenceCategory>("pc_provider_settings")?.title = providerName?.let {
            getString(R.string.settings_provider_connection_category_title, it)
        } ?: getString(R.string.settings_category_provider_title)

        findPreference<PreferenceCategory>("pc_provider_empty_state")?.title = providerName?.let {
            getString(R.string.settings_provider_connection_category_title, it)
        } ?: getString(R.string.settings_provider_connection_title)
    }

    private fun updateProviderVisibilityState() {
        val provider = UserPreferences.currentProvider
        val isStreamingCommunity = provider is StreamingCommunityProvider
        val isSerienStream = ProviderSettingsVisibility.showSerienStreamSettings(provider)
        val isAniWorld = ProviderSettingsVisibility.showAniWorldSettings(provider)
        val isMoflix = provider is MStreamProvider
        val isCuevana = provider?.name == "Cuevana 3"
        val isPoseidon = provider?.name == "Poseidonhd2"
        val isAnimeOnlineNinja = provider is AnimeOnlineNinjaProvider
        val isGuardaFlix = provider is GuardaFlixProvider
        val hasConfigProvider = provider is ProviderConfigUrl
        val hasSpecificOptions = isStreamingCommunity || isSerienStream || isAniWorld ||
            isMoflix || isCuevana || isPoseidon || isAnimeOnlineNinja || isGuardaFlix

        findPreference<PreferenceCategory>("pc_streamingcommunity_settings")?.isVisible = isStreamingCommunity
        findPreference<PreferenceCategory>("pc_serienstream_settings")?.isVisible = isSerienStream
        findPreference<PreferenceCategory>("pc_aniworld_settings")?.isVisible = isAniWorld
        findPreference<PreferenceCategory>("pc_moflix_settings")?.isVisible = isMoflix
        findPreference<PreferenceCategory>("pc_cuevana_settings")?.isVisible = isCuevana
        findPreference<PreferenceCategory>("pc_poseidon_settings")?.isVisible = isPoseidon
        findPreference<PreferenceCategory>("pc_animeonlineninja_settings")?.isVisible = isAnimeOnlineNinja
        findPreference<PreferenceCategory>("pc_guardaflix_settings")?.isVisible = isGuardaFlix
        findPreference<PreferenceScreen>("screen_guardaflix_auth")?.isVisible = isGuardaFlix
        findPreference<Preference>("open_guardaflix_auth")?.apply {
            isVisible = isGuardaFlix
            setOnPreferenceClickListener {
                openNestedSettingsScreen(
                    "screen_guardaflix_auth",
                    getString(R.string.settings_category_guardaflix),
                )
                true
            }
        }
        findPreference<PreferenceCategory>("pc_provider_empty_state")?.isVisible = !hasConfigProvider && !hasSpecificOptions

        findPreference<Preference>("screen_serienstream_auth")?.isVisible = isSerienStream
        findPreference<Preference>("screen_aniworld_auth")?.isVisible = isAniWorld
        findPreference<Preference>("screen_watchlist_import")?.isVisible =
            ProviderSettingsVisibility.showWatchlistImport(provider)
    }

    private fun bindAnimeOnlineNinjaPreferredServer() {
        val preference = findPreference<ListPreference>("provider_animeonlineninja_preferred_server") ?: return
        val currentValue = UserPreferences.getProviderCache(
            AnimeOnlineNinjaProvider,
            UserPreferences.PROVIDER_PREFERRED_SERVER
        )
        preference.value = currentValue
        preference.setSummaryText(
            preference.entries
                ?.getOrNull(preference.findIndexOfValue(currentValue))
                ?: getString(R.string.settings_provider_animeonlineninja_preferred_server_summary),
        )
        preference.setOnPreferenceChangeListener { pref, newValue ->
            val value = (newValue as String).trim()
            UserPreferences.setProviderCache(
                AnimeOnlineNinjaProvider,
                UserPreferences.PROVIDER_PREFERRED_SERVER,
                value
            )
            if (pref is ListPreference) {
                pref.setSummaryText(
                    pref.entries?.getOrNull(pref.findIndexOfValue(value))
                        ?: getString(R.string.settings_provider_animeonlineninja_preferred_server_summary),
                )
            }
            true
        }
    }

    private fun setupParentalControlPreferences() {
        val pinPreference = findPreference<EditTextPreference>("PARENTAL_CONTROL_PIN")
        val adminPinPreference = findPreference<EditTextPreference>("PARENTAL_CONTROL_ADMIN_PIN")
        val removePinPreference = findPreference<Preference>("PARENTAL_CONTROL_REMOVE_PIN")
        val removeAdminPinPreference = findPreference<Preference>("PARENTAL_CONTROL_REMOVE_ADMIN_PIN")
        val maxAgePreference = findPreference<ListPreference>("PARENTAL_CONTROL_MAX_AGE")
        val unlockPreference = findPreference<Preference>("PARENTAL_CONTROL_UNLOCK")

        fun bindPinEditText(editText: android.widget.EditText) {
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            editText.imeOptions = EditorInfo.IME_ACTION_DONE
            editText.hint = getString(R.string.settings_parental_pin_hint)
            editText.setText("")
        }

        pinPreference?.setOnBindEditTextListener(::bindPinEditText)
        adminPinPreference?.setOnBindEditTextListener(::bindPinEditText)

        pinPreference?.setOnPreferenceClickListener {
            showParentalPinEditor(maxAgePreference)
            true
        }

        adminPinPreference?.setOnPreferenceClickListener {
            showAdminPinEditor()
            true
        }

        removePinPreference?.setOnPreferenceClickListener {
            changeParentalSettingWithPinCheck {
                if (!UserPreferences.canClearParentalPinWhileKidsActive()) {
                    showSettingsInfo(getString(R.string.settings_parental_pin_clear_kids_blocked))
                    return@changeParentalSettingWithPinCheck
                }
                UserPreferences.parentalControlPin = ""
                UserPreferences.parentalControlMaxAge = null
                maxAgePreference?.value = ""
                UserPreferences.unlockParentalControls()
                showSettingsInfo(getString(R.string.settings_parental_pin_removed))
                ProviderChangeNotifier.notifyProviderChanged()
                updateParentalControlPreferenceState()
            }
            true
        }

        removeAdminPinPreference?.setOnPreferenceClickListener {
            changeAdminSettingWithPinCheck {
                UserPreferences.parentalControlAdminPin = ""
                showSettingsInfo(getString(R.string.settings_parental_admin_pin_removed))
                updateParentalControlPreferenceState()
            }
            true
        }

        maxAgePreference?.setOnPreferenceChangeListener { _, newValue ->
            if (!UserPreferences.enableTmdb) return@setOnPreferenceChangeListener false
            if (UserPreferences.parentalControlPin.isBlank()) {
                showSettingsInfo(getString(R.string.settings_parental_set_pin_first))
                return@setOnPreferenceChangeListener false
            }

            val newMaxAgeValue = newValue as String
            val newMaxAge = newMaxAgeValue.toIntOrNull()

            changeParentalSettingWithPinCheck {
                UserPreferences.parentalControlMaxAge = newMaxAge
                // Kids filter reads profile.maxAgeRating first — keep it in sync on Mobile.
                if (com.dskja.betterstreamflix.profiles.ProfileManager.isKidsActive() && newMaxAge != null) {
                    com.dskja.betterstreamflix.profiles.ProfileManager.updateMaxAgeRating(
                        com.dskja.betterstreamflix.profiles.ProfileManager.activeProfileId,
                        newMaxAge,
                    )
                }
                maxAgePreference.value = newMaxAgeValue
                showSettingsInfo(getString(R.string.settings_parental_max_age_saved))
                ProviderChangeNotifier.notifyProviderChanged()
                updateParentalControlPreferenceState()
            }

            false
        }

        unlockPreference?.setOnPreferenceClickListener {
            if (UserPreferences.parentalControlAdminPin.isBlank()) {
                showSettingsInfo(getString(R.string.settings_parental_set_admin_pin_first))
            } else {
                promptForAdminPin {
                    UserPreferences.unlockParentalControls()
                    showSettingsInfo(getString(R.string.settings_parental_unlocked))
                    updateParentalControlPreferenceState()
                }
            }
            true
        }

        updateParentalControlPreferenceState()
    }

    private fun updateParentalControlPreferenceState() {
        val tmdbEnabled = UserPreferences.enableTmdb
        val pinPreference = findPreference<EditTextPreference>("PARENTAL_CONTROL_PIN")
        val adminPinPreference = findPreference<EditTextPreference>("PARENTAL_CONTROL_ADMIN_PIN")
        val removePinPreference = findPreference<Preference>("PARENTAL_CONTROL_REMOVE_PIN")
        val removeAdminPinPreference = findPreference<Preference>("PARENTAL_CONTROL_REMOVE_ADMIN_PIN")
        val maxAgePreference = findPreference<ListPreference>("PARENTAL_CONTROL_MAX_AGE")
        val unlockPreference = findPreference<Preference>("PARENTAL_CONTROL_UNLOCK")
        val isLocked = UserPreferences.isParentalControlTemporarilyLocked || UserPreferences.parentalControlHardLocked

        pinPreference?.apply {
            isEnabled = tmdbEnabled && !isLocked
            text = ""
            summary = when {
                !tmdbEnabled -> getString(R.string.settings_parental_requires_tmdb)
                UserPreferences.parentalControlHardLocked -> getString(R.string.settings_parental_locked_hard)
                UserPreferences.isParentalControlTemporarilyLocked -> getString(
                    R.string.settings_parental_locked_temporary,
                    lockRemainingMinutes()
                )
                UserPreferences.parentalControlPin.isBlank() -> getString(R.string.settings_parental_pin_not_set)
                else -> getString(R.string.settings_parental_pin_set)
            }
        }

        adminPinPreference?.apply {
            isEnabled = tmdbEnabled
            text = ""
            summary = when {
                !tmdbEnabled -> getString(R.string.settings_parental_requires_tmdb)
                UserPreferences.parentalControlAdminPin.isBlank() -> getString(R.string.settings_parental_admin_pin_not_set)
                else -> getString(R.string.settings_parental_admin_pin_set)
            }
        }

        removePinPreference?.apply {
            isVisible = tmdbEnabled && UserPreferences.parentalControlPin.isNotBlank()
            isEnabled = !isLocked
        }

        removeAdminPinPreference?.apply {
            isVisible = tmdbEnabled && UserPreferences.parentalControlAdminPin.isNotBlank()
            isEnabled = true
        }

        maxAgePreference?.apply {
            isEnabled = tmdbEnabled && !isLocked
            value = UserPreferences.parentalControlMaxAge?.toString().orEmpty()
            summary = when {
                !tmdbEnabled -> getString(R.string.settings_parental_requires_tmdb)
                UserPreferences.parentalControlHardLocked -> getString(R.string.settings_parental_locked_hard)
                UserPreferences.isParentalControlTemporarilyLocked -> getString(
                    R.string.settings_parental_locked_temporary,
                    lockRemainingMinutes()
                )
                UserPreferences.parentalControlPin.isBlank() -> getString(R.string.settings_parental_set_pin_first)
                UserPreferences.parentalControlMaxAge == null -> getString(R.string.settings_parental_max_age_disabled)
                else -> "${UserPreferences.parentalControlMaxAge}+"
            }
        }

        unlockPreference?.apply {
            isVisible = isLocked
            isEnabled = tmdbEnabled && UserPreferences.parentalControlAdminPin.isNotBlank()
            summary = when {
                UserPreferences.parentalControlAdminPin.isBlank() -> getString(R.string.settings_parental_set_admin_pin_first)
                UserPreferences.parentalControlHardLocked -> getString(R.string.settings_parental_locked_hard)
                UserPreferences.isParentalControlTemporarilyLocked -> getString(
                    R.string.settings_parental_locked_temporary,
                    lockRemainingMinutes()
                )
                else -> getString(R.string.settings_parental_unlock_summary)
            }
        }
    }

    private fun showSettingsInfo(message: CharSequence, titleRes: Int = R.string.settings_parental_pin_title) {
        if (!isAdded) return
        if (ExperimentalMobileDesign.enabled()) {
            ExpDialogChrome.showInfo(requireContext(), titleRes, message, { alertBuilder() })
        } else {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun changeParentalSettingWithPinCheck(onVerified: () -> Unit) {
        when {
            UserPreferences.parentalControlHardLocked -> {
                showSettingsInfo(
                    getString(R.string.settings_parental_locked_hard),
                    R.string.settings_parental_pin_title,
                )
                updateParentalControlPreferenceState()
                return
            }
            UserPreferences.isParentalControlTemporarilyLocked -> {
                showSettingsInfo(
                    getString(R.string.settings_parental_locked_temporary, lockRemainingMinutes()),
                    R.string.settings_parental_pin_title,
                )
                updateParentalControlPreferenceState()
                return
            }
        }

        if (UserPreferences.parentalControlPin.isBlank()) {
            onVerified()
            return
        }

        promptForPin(
            titleRes = R.string.settings_parental_enter_current_pin_title,
            messageRes = R.string.settings_parental_enter_current_pin_message,
            onSubmit = { enteredPin ->
                if (UserPreferences.verifyParentalControlPin(enteredPin)) {
                    UserPreferences.registerParentalPinSuccess()
                    onVerified()
                    null
                } else {
                    UserPreferences.registerParentalPinFailure()
                    updateParentalControlPreferenceState()
                    when {
                        UserPreferences.parentalControlHardLocked -> R.string.settings_parental_locked_hard
                        UserPreferences.isParentalControlTemporarilyLocked -> R.string.settings_parental_locked_temporary
                        else -> R.string.settings_parental_invalid_pin
                    }.let { failureMessageRes ->
                        if (failureMessageRes == R.string.settings_parental_locked_temporary) {
                            getString(failureMessageRes, lockRemainingMinutes())
                        } else {
                            getString(failureMessageRes)
                        }
                    }
                }
            }
        )
    }

    private fun changeAdminSettingWithPinCheck(onVerified: () -> Unit) {
        if (UserPreferences.parentalControlAdminPin.isBlank()) {
            onVerified()
            return
        }

        promptForAdminPin(onVerified)
    }

    private fun promptForAdminPin(onVerified: () -> Unit) {
        if (UserPreferences.parentalControlAdminPin.isBlank()) {
            showSettingsInfo(getString(R.string.settings_parental_set_admin_pin_first))
            return
        }

        promptForPin(
            titleRes = R.string.settings_parental_enter_admin_pin_title,
            messageRes = R.string.settings_parental_enter_admin_pin_message,
            onSubmit = { enteredPin ->
                if (UserPreferences.verifyParentalControlAdminPin(enteredPin)) {
                    UserPreferences.unlockParentalControls()
                    onVerified()
                    null
                } else {
                    getString(R.string.settings_parental_invalid_admin_pin)
                }
            }
        )
    }

    private fun showParentalPinEditor(maxAgePreference: ListPreference?) {
        if (!UserPreferences.enableTmdb) {
            showSettingsInfo(getString(R.string.settings_parental_requires_tmdb))
            return
        }

        changeParentalSettingWithPinCheck {
            promptForPinValue(
                titleRes = R.string.settings_parental_pin_title,
                messageRes = if (UserPreferences.parentalControlPin.isBlank()) {
                    R.string.settings_parental_set_new_pin_message
                } else {
                    R.string.settings_parental_change_pin_message
                },
                allowBlank = UserPreferences.parentalControlPin.isNotBlank(),
                onSubmit = { newPin ->
                    when {
                        newPin.isBlank() -> {
                            if (!UserPreferences.canClearParentalPinWhileKidsActive()) {
                                return@promptForPinValue getString(
                                    R.string.settings_parental_pin_clear_kids_blocked,
                                )
                            }
                            UserPreferences.parentalControlPin = ""
                            UserPreferences.parentalControlMaxAge = null
                            maxAgePreference?.value = ""
                            UserPreferences.unlockParentalControls()
                            showSettingsInfo(getString(R.string.settings_parental_pin_removed))
                            ProviderChangeNotifier.notifyProviderChanged()
                            updateParentalControlPreferenceState()
                            null
                        }
                        newPin.length < 4 -> getString(R.string.settings_parental_pin_too_short)
                        else -> {
                            // Setter hashes raw PINs before storage.
                            UserPreferences.parentalControlPin = newPin
                            showSettingsInfo(getString(R.string.settings_parental_pin_saved))
                            ProviderChangeNotifier.notifyProviderChanged()
                            updateParentalControlPreferenceState()
                            null
                        }
                    }
                }
            )
        }
    }

    private fun showAdminPinEditor() {
        changeAdminSettingWithPinCheck {
            promptForPinValue(
                titleRes = R.string.settings_parental_admin_pin_title,
                messageRes = if (UserPreferences.parentalControlAdminPin.isBlank()) {
                    R.string.settings_parental_set_new_admin_pin_message
                } else {
                    R.string.settings_parental_change_admin_pin_message
                },
                allowBlank = UserPreferences.parentalControlAdminPin.isNotBlank(),
                onSubmit = { newPin ->
                    when {
                        newPin.isBlank() -> {
                            UserPreferences.parentalControlAdminPin = ""
                            showSettingsInfo(getString(R.string.settings_parental_admin_pin_removed))
                            updateParentalControlPreferenceState()
                            null
                        }
                        newPin.length < 4 -> getString(R.string.settings_parental_pin_too_short)
                        else -> {
                            UserPreferences.parentalControlAdminPin = newPin
                            showSettingsInfo(getString(R.string.settings_parental_admin_pin_saved))
                            updateParentalControlPreferenceState()
                            null
                        }
                    }
                }
            )
        }
    }

    private fun promptForPin(
        titleRes: Int,
        messageRes: Int,
        onSubmit: (String) -> String?,
    ) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = getString(R.string.settings_parental_pin_hint)
            if (ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
                setPadding(48, 36, 48, 36)
                setTextAppearance(R.style.TextAppearance_CinemaInk_Body)
            }
        }

        val dialog = if (ExperimentalMobileDesign.enabled()) {
            val glass = ExpDialogChrome.buildGlassMessage(requireContext(), getString(messageRes))
            val density = resources.displayMetrics.density
            glass.root.addView(
                input,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = (12 * density).toInt() },
            )
            alertBuilder()
                .setTitle(titleRes)
                .setView(glass.root)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    dialog.setOnShowListener {
                        ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                        with(com.dskja.betterstreamflix.utils.ExpPressEffects) { input.applyExpPress() }
                        ExpMotion.popIn(input)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            input.error = null
                            val errorMessage = onSubmit(input.text?.toString()?.trim().orEmpty())
                            if (errorMessage == null) {
                                dialog.dismiss()
                            } else {
                                input.setText("")
                                input.error = errorMessage
                                ExpMotion.shake(input)
                                input.requestFocus()
                            }
                        }
                    }
                }
        } else {
            alertBuilder()
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            input.error = null
                            val errorMessage = onSubmit(input.text?.toString()?.trim().orEmpty())
                            if (errorMessage == null) {
                                dialog.dismiss()
                            } else {
                                input.setText("")
                                input.error = errorMessage
                                input.requestFocus()
                            }
                        }
                    }
                }
        }

        dialog.show()
    }

    private fun promptForPinValue(
        titleRes: Int,
        messageRes: Int,
        allowBlank: Boolean,
        onSubmit: (String) -> String?,
    ) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = getString(R.string.settings_parental_pin_hint)
            if (ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
                setPadding(48, 36, 48, 36)
                setTextAppearance(R.style.TextAppearance_CinemaInk_Body)
            }
        }

        fun wirePositive(dialog: AlertDialog) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                input.error = null
                val newValue = input.text?.toString()?.trim().orEmpty()
                if (newValue.isBlank() && !allowBlank) {
                    input.setText("")
                    input.error = getString(R.string.settings_parental_pin_too_short)
                    input.requestFocus()
                    return@setOnClickListener
                }
                val errorMessage = onSubmit(newValue)
                if (errorMessage == null) {
                    dialog.dismiss()
                } else {
                    input.setText("")
                    input.error = errorMessage
                    ExpMotion.shake(input)
                    input.requestFocus()
                }
            }
        }

        val dialog = if (ExperimentalMobileDesign.enabled()) {
            val glass = ExpDialogChrome.buildGlassMessage(requireContext(), getString(messageRes))
            val density = resources.displayMetrics.density
            glass.root.addView(
                input,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = (12 * density).toInt() },
            )
            alertBuilder()
                .setTitle(titleRes)
                .setView(glass.root)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { d ->
                    d.setOnShowListener {
                        ExpDialogChrome.polishGlassMessageShown(d, glass)
                        with(com.dskja.betterstreamflix.utils.ExpPressEffects) { input.applyExpPress() }
                        ExpMotion.popIn(input)
                        wirePositive(d)
                    }
                }
        } else {
            alertBuilder()
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { d ->
                    d.setOnShowListener { wirePositive(d) }
                }
        }

        dialog.show()
    }

    private fun lockRemainingMinutes(): Int {
        val millis = UserPreferences.parentalControlLockRemainingMillis
        return ((millis + 60_000L - 1L) / 60_000L).toInt().coerceAtLeast(1)
    }

    private fun showBackupResult(message: String, offerRestart: Boolean = false) {
        if (!isAdded) return
        if (!ExperimentalMobileDesign.enabled()) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            return
        }
        val glass = ExpDialogChrome.buildGlassMessage(requireContext(), message)
        val builder = alertBuilder().setView(glass.root)
        if (offerRestart) {
            builder.setPositiveButton(R.string.backup_restart_action) { _, _ ->
                requireActivity().apply {
                    finish()
                    startActivity(Intent(this, MainMobileActivity::class.java))
                }
            }
            builder.setNegativeButton(android.R.string.ok, null)
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
        }
        builder.create().also { dialog ->
            dialog.setOnShowListener {
                ExpDialogChrome.polishGlassMessageShown(dialog, glass)
            }
            dialog.show()
        }
    }

    private suspend fun performBackupExport(uri: Uri) {
        withBackupLoading(R.string.backup_export_title) {
            val jsonData = withContext(Dispatchers.IO) {
                backupRestoreManager.exportUserData()
            }
            if (jsonData != null) {
                try {
                    requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.writer().use { it.write(jsonData) }
                        showBackupResult(getString(R.string.backup_export_success))
                    }
                } catch (e: IOException) {
                    showBackupResult(getString(R.string.backup_export_error_write))
                    Log.e("BackupExportMobile", "Error writing backup file", e)
                }
            } else {
                showBackupResult(getString(R.string.backup_data_not_generated))
            }
        }
    }

    private suspend fun performBackupImport(uri: Uri) {
        withBackupLoading(R.string.backup_import_title) {
            try {
                val jsonData = withContext(Dispatchers.IO) {
                    val stringBuilder = StringBuilder()
                    requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { stringBuilder.append(it) }
                        }
                    }
                    stringBuilder.toString()
                }
                if (jsonData.isNotBlank()) {
                    val success = withContext(Dispatchers.IO) {
                        backupRestoreManager.importUserData(jsonData)
                    }
                    if (success) {
                        showBackupResult(getString(R.string.backup_import_success), offerRestart = true)
                    } else {
                        showBackupResult(getString(R.string.backup_import_error))
                    }
                } else {
                    showBackupResult(getString(R.string.backup_import_empty_file))
                }
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                showBackupResult(getString(R.string.backup_import_read_error))
                Log.e("BackupImportMobile", "Error reading/processing backup file", e)
            }
        }
    }

    private suspend fun performDatabaseBackupExport(uri: Uri) {
        withBackupLoading(R.string.backup_db_export_title) {
            val zipData = withContext(Dispatchers.IO) {
                backupRestoreManager.exportDatabaseZip()
            }
            if (zipData != null) {
                try {
                    requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(zipData)
                        showBackupResult(getString(R.string.backup_db_export_success))
                    }
                } catch (e: IOException) {
                    showBackupResult(getString(R.string.backup_export_error_write))
                    Log.e("BackupExportMobile", "Error writing database backup file", e)
                }
            } else {
                showBackupResult(getString(R.string.backup_data_not_generated))
            }
        }
    }

    private suspend fun performDatabaseBackupImport(uri: Uri) {
        withBackupLoading(R.string.backup_db_import_title) {
            try {
                val zipBytes = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (zipBytes == null || zipBytes.isEmpty()) {
                    showBackupResult(getString(R.string.backup_import_empty_file))
                    return@withBackupLoading
                }
                val success = withContext(Dispatchers.IO) {
                    backupRestoreManager.importDatabaseZip(zipBytes)
                }
                showBackupResult(
                    if (success) getString(R.string.backup_db_import_success)
                    else getString(R.string.backup_import_error),
                    offerRestart = success,
                )
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                showBackupResult(getString(R.string.backup_import_read_error))
                Log.e("BackupImportMobile", "Error reading/processing database backup file", e)
            }
        }
    }

    private suspend fun <T> withBackupLoading(titleRes: Int, block: suspend () -> T): T {
        showBackupLoadingDialog(titleRes)
        return try {
            block()
        } finally {
            hideBackupLoadingDialog()
        }
    }

    private fun showBackupLoadingDialog(titleRes: Int) {
        if (!isAdded) return
        if (backupLoadingDialog?.isShowing == true) {
            backupLoadingDialog?.setTitle(titleRes)
            return
        }

        val contentView = LayoutInflater.from(requireContext()).inflate(
            R.layout.layout_is_loading_mobile,
            null
        )
        contentView.findViewById<android.widget.TextView>(R.id.tv_is_loading_error)?.visibility = View.GONE
        contentView.findViewById<Group>(R.id.g_is_loading_retry)?.visibility = View.GONE

        backupLoadingDialog = alertBuilder()
            .setTitle(titleRes)
            .setView(contentView)
            .setCancelable(false)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                if (ExperimentalMobileDesign.enabled()) {
                    ExperimentalMobileDesign.applyReducedGlass(contentView)
                    window?.setBackgroundDrawableResource(ExperimentalMobileDesign.dialogBackground())
                    ExpMotion.enterScreen(contentView)
                    ExpDialogChrome.polishShown(this)
                    contentView.findViewById<View>(R.id.pb_is_loading)?.let { ExpMotion.popIn(it) }
                }
                show()
            }
    }

    private fun hideBackupLoadingDialog() {
        backupLoadingDialog?.dismiss()
        backupLoadingDialog = null
    }

    override fun onResume() {
        super.onResume()
        applyScreenTitle()
        updateOverviewLabels()
        updateProviderVisibilityState()
        SubtitleStudioSettingsController.refresh(this)
        PlatformSettingsController.refresh(this) { key -> findPreference(key) }
        com.dskja.betterstreamflix.kids.ParentDashboardController.refreshOnResume(
            fragment = this,
            findPreference = { key -> findPreference(key) },
        )
        ProfilesSettingsController.refresh(
            findPreference = { key -> findPreference(key) },
            context = requireContext(),
        )
        ConnectionServicesController.refresh(
            findPreference = { key -> findPreference(key) },
            context = requireContext(),
        )
        SerienStreamAuthSettingsController.refresh { key -> findPreference(key) }
        AniWorldAuthSettingsController.refresh { key -> findPreference(key) }
        com.dskja.betterstreamflix.sync.WatchPartySettingsController.refresh(
            fragment = this,
            findPreference = { key -> findPreference(key) },
        )
        settingsHubController?.updateVisibility()

        findPreference<EditTextPreference>("provider_streamingcommunity_domain")?.apply {
            val currentValue = UserPreferences.streamingcommunityDomain
            summary = currentValue
            if (currentValue == DEFAULT_DOMAIN_VALUE || currentValue == PREFS_ERROR_VALUE) {
                text = null
            } else {
                text = currentValue
            }
        }

        findPreference<EditTextPreference>("TMDB_API_KEY")?.apply {
            summary = if (UserPreferences.tmdbApiKey.isEmpty()) getString(R.string.settings_tmdb_api_key_summary) else UserPreferences.tmdbApiKey
            text = UserPreferences.tmdbApiKey
        }

        findPreference<ListPreference>("APP_LANGUAGE")?.value =
            AppLanguageManager.getSelectedLanguage(requireContext())

        findPreference<SwitchPreference>("AUTOPLAY")?.isChecked = UserPreferences.autoplay
        findPreference<SwitchPreference>("FORCE_EXTRA_BUFFERING")?.isChecked = UserPreferences.forceExtraBuffering
        findPreference<SwitchPreference>("DATA_SAVER_MODE")?.isChecked = UserPreferences.dataSaverMode
        findPreference<ListPreference>("PLAYBACK_VIDEO_QUALITY")?.value =
            UserPreferences.playbackVideoQuality.name
        findPreference<SwitchPreference>("PLAYER_GESTURES")?.isChecked = UserPreferences.playerGestures
        findPreference<SwitchPreference>("KEEP_SCREEN_ON_WHEN_PAUSED")?.isChecked = UserPreferences.keepScreenOnWhenPaused
        findPreference<SwitchPreference>("ALLOW_PHONE_PORTRAIT_PLAYBACK")?.isChecked =
            UserPreferences.allowPhonePortraitPlayback
        findPreference<SwitchPreferenceCompat>("ENABLE_TMDB")?.isChecked = UserPreferences.enableTmdb
        DownloadsSettingsController.refresh(
            findPreference = { key -> findPreference(key) },
            context = requireContext(),
            scope = lifecycleScope,
        )
        updateParentalControlPreferenceState()
    }

    private fun bindExperimentalDesignGate() {
        // Lumina shell removed — Dark is the only look.
    }
}
