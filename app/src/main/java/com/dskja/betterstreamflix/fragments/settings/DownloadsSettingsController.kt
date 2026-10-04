package com.dskja.betterstreamflix.fragments.settings

import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.download.DownloadController
import com.dskja.betterstreamflix.download.DownloadItemState
import com.dskja.betterstreamflix.download.DownloadQualityPreset
import com.dskja.betterstreamflix.download.DownloadRepository
import com.dskja.betterstreamflix.download.DownloadStats
import com.dskja.betterstreamflix.download.DownloadStorage
import com.dskja.betterstreamflix.download.DownloadStorageLocation
import com.dskja.betterstreamflix.download.DownloadTreeAccess
import com.dskja.betterstreamflix.download.ExternalDownloadHandoff
import com.dskja.betterstreamflix.download.StreamflixDownloadManager
import com.dskja.betterstreamflix.platform.playerbackend.ExternalStreamHandoff
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared Downloads preference wiring for Mobile + TV Settings.
 */
object DownloadsSettingsController {

    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
        onPickDownloadFolder: (() -> Unit)? = null,
    ) {
        val context = fragment.requireContext()

        findPreference("DOWNLOAD_STORAGE_LOCATION")?.let { pref ->
            val list = pref as? ListPreference ?: return@let
            list.value = UserPreferences.downloadStorageLocation.name
            list.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            list.setOnPreferenceChangeListener { _, newValue ->
                val location = DownloadStorageLocation.fromKey(newValue as String)
                when (location) {
                    DownloadStorageLocation.REMOVABLE -> {
                        if (!DownloadStorage.hasRemovableStorage(context)) {
                            ExpDialogChrome.notify(
                                context,
                                R.string.settings_download_storage_removable_unavailable,
                            )
                            return@setOnPreferenceChangeListener false
                        }
                    }
                    DownloadStorageLocation.CUSTOM_FOLDER -> {
                        if (!DownloadTreeAccess.hasTree()) {
                            onPickDownloadFolder?.invoke()
                            return@setOnPreferenceChangeListener false
                        }
                    }
                    else -> Unit
                }
                if (location != UserPreferences.downloadStorageLocation) {
                    UserPreferences.downloadStorageLocation = location
                    StreamflixDownloadManager.relocate(context)
                    refreshSummaries(findPreference, context)
                    ExpDialogChrome.notify(context, R.string.settings_download_storage_changed)
                }
                true
            }
        }

        findPreference("DOWNLOAD_STORAGE_PICK_FOLDER")?.setOnPreferenceClickListener {
            onPickDownloadFolder?.invoke()
            true
        }

        findPreference("DOWNLOAD_STORAGE_CLEAR_FOLDER")?.setOnPreferenceClickListener {
            DownloadTreeAccess.clearTree(context)
            StreamflixDownloadManager.relocate(context)
            refreshSummaries(findPreference, context)
            ExpDialogChrome.notify(context, R.string.settings_download_storage_folder_cleared)
            true
        }

        findPreference("EXTERNAL_PLAYER_DEFAULT")?.setOnPreferenceClickListener {
            val current = UserPreferences.externalPlayerPackage
            val players = ExternalStreamHandoff.listInstalledPlayers(context)
            if (players.isEmpty()) {
                ExpDialogChrome.notify(context, R.string.external_player_none_found)
                return@setOnPreferenceClickListener true
            }
            val labels = players.map {
                it.loadLabel(context.packageManager).toString() + "\n" + it.activityInfo.packageName
            }.toTypedArray()
            val packages = players.map { it.activityInfo.packageName }
            val checked = packages.indexOf(current).coerceAtLeast(-1)
            val builder = if (ExperimentalMobileDesign.enabled()) {
                MaterialAlertDialogBuilder(context)
            } else {
                AlertDialog.Builder(context)
            }
            builder
                .setTitle(R.string.settings_external_player_title)
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    ExternalStreamHandoff.rememberChosenPackage(packages.getOrNull(which))
                    dialog.dismiss()
                    refreshSummaries(findPreference, context)
                    ExpDialogChrome.notify(context, R.string.external_player_remembered)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    dialog.setOnShowListener { ExpDialogChrome.polishButtons(dialog) }
                    dialog.show()
                }
            true
        }

        findPreference("EXTERNAL_PLAYER_CLEAR")?.setOnPreferenceClickListener {
            ExternalStreamHandoff.clearPreferredPackage()
            refreshSummaries(findPreference, context)
            ExpDialogChrome.notify(context, R.string.external_player_clear_default)
            true
        }

        findPreference("EXTERNAL_DOWNLOADER_DEFAULT")?.setOnPreferenceClickListener {
            val installed = ExternalDownloadHandoff.installedCandidates(context)
            if (installed.isEmpty()) {
                ExpDialogChrome.notify(context, R.string.external_download_none_found)
                return@setOnPreferenceClickListener true
            }
            val current = UserPreferences.externalDownloaderPackage
            val checked = installed.indexOf(current).coerceAtLeast(-1)
            val builder = if (ExperimentalMobileDesign.enabled()) {
                MaterialAlertDialogBuilder(context)
            } else {
                AlertDialog.Builder(context)
            }
            builder
                .setTitle(R.string.settings_external_downloader_title)
                .setSingleChoiceItems(installed.toTypedArray(), checked) { dialog, which ->
                    ExternalDownloadHandoff.rememberChosenPackage(installed.getOrNull(which))
                    dialog.dismiss()
                    refreshSummaries(findPreference, context)
                    ExpDialogChrome.notify(context, R.string.external_download_remembered)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    dialog.setOnShowListener { ExpDialogChrome.polishButtons(dialog) }
                    dialog.show()
                }
            true
        }

        findPreference("EXTERNAL_DOWNLOADER_CLEAR")?.setOnPreferenceClickListener {
            ExternalDownloadHandoff.clearPreferredPackage()
            refreshSummaries(findPreference, context)
            ExpDialogChrome.notify(context, R.string.settings_external_downloader_clear)
            true
        }

        findPreference("DOWNLOAD_WIFI_ONLY")?.let { pref ->
            val sw = pref as? SwitchPreference ?: return@let
            sw.isChecked = UserPreferences.downloadWifiOnly
            sw.setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.downloadWifiOnly = newValue as Boolean
                true
            }
        }

        findPreference("DOWNLOAD_QUALITY_PRESET")?.let { pref ->
            val list = pref as? ListPreference ?: return@let
            list.value = UserPreferences.downloadQualityPreset.name
            list.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            list.setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.downloadQualityPreset =
                    DownloadQualityPreset.fromKey(newValue as String)
                true
            }
        }

        findPreference("DOWNLOAD_MAX_CONCURRENT")?.let { pref ->
            val list = pref as? ListPreference ?: return@let
            list.value = UserPreferences.downloadMaxConcurrent.toString()
            list.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            list.setOnPreferenceChangeListener { _, newValue ->
                val max = (newValue as String).toIntOrNull()?.coerceIn(1, 4) ?: 2
                UserPreferences.downloadMaxConcurrent = max
                StreamflixDownloadManager.setMaxParallel(context, max)
                true
            }
        }

        findPreference("DOWNLOAD_NOTIFY_COMPLETE")?.let { pref ->
            val sw = pref as? SwitchPreference ?: return@let
            sw.isChecked = UserPreferences.downloadNotifyComplete
            sw.setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.downloadNotifyComplete = newValue as Boolean
                true
            }
        }

        findPreference("DOWNLOAD_FILTER_CURRENT_PROVIDER")?.let { pref ->
            val sw = pref as? SwitchPreference ?: return@let
            sw.isChecked = UserPreferences.downloadFilterCurrentProvider
            sw.setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.downloadFilterCurrentProvider = newValue as Boolean
                true
            }
        }

        findPreference("DOWNLOAD_SOFT_LIMIT_GB")?.let { pref ->
            val edit = pref as? EditTextPreference ?: return@let
            edit.text = UserPreferences.downloadSoftLimitGb.toString()
            edit.summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                val gb = DownloadStats.clampSoftLimitGb(p.text)
                if (gb <= 0) {
                    context.getString(R.string.settings_download_soft_limit_unlimited)
                } else {
                    context.getString(R.string.settings_download_soft_limit_value, gb)
                }
            }
            edit.setOnPreferenceChangeListener { _, newValue ->
                val clamped = DownloadStats.clampSoftLimitGb(newValue as? String)
                UserPreferences.downloadSoftLimitGb = clamped
                edit.text = clamped.toString()
                refreshSummaries(findPreference, context)
                true
            }
        }

        findPreference("DOWNLOAD_SMART_ENABLED")?.let { pref ->
            val sw = pref as? SwitchPreference ?: return@let
            sw.isChecked = UserPreferences.downloadSmartEnabled
            sw.setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.downloadSmartEnabled = newValue as Boolean
                true
            }
        }

        findPreference("DOWNLOAD_AUTO_DELETE_WATCHED")?.let { pref ->
            val sw = pref as? SwitchPreference ?: return@let
            sw.isChecked = UserPreferences.downloadAutoDeleteWatched
            sw.setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.downloadAutoDeleteWatched = newValue as Boolean
                true
            }
        }

        findPreference("DOWNLOAD_CLEAR_COMPLETED")?.setOnPreferenceClickListener {
            confirmClear(
                context,
                R.string.settings_download_clear_completed,
                R.string.settings_download_clear_completed_confirm,
            ) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        DownloadRepository.get(context).clearCompleted()
                    }
                    refreshSummaries(findPreference, context)
                    ExpDialogChrome.notify(context, R.string.settings_download_cleared_completed)
                }
            }
            true
        }

        findPreference("DOWNLOAD_CLEAR_FAILED")?.setOnPreferenceClickListener {
            confirmClear(
                context,
                R.string.settings_download_clear_failed,
                R.string.settings_download_clear_failed_confirm,
            ) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        DownloadRepository.get(context).clearFailed()
                    }
                    refreshSummaries(findPreference, context)
                    ExpDialogChrome.notify(context, R.string.settings_download_cleared_failed)
                }
            }
            true
        }

        findPreference("DOWNLOAD_RETRY_FAILED")?.setOnPreferenceClickListener {
            scope.launch {
                val count = withContext(Dispatchers.IO) {
                    DownloadController.retryAllFailed(context)
                }
                refreshSummaries(findPreference, context)
                ExpDialogChrome.notify(
                    context,
                    context.getString(R.string.settings_download_retry_failed_toast, count),
                )
            }
            true
        }

        findPreference("DOWNLOAD_CLEAR_ALL")?.setOnPreferenceClickListener {
            confirmClear(
                context,
                R.string.settings_download_clear_all,
                R.string.settings_download_clear_all_confirm,
            ) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        DownloadRepository.get(context).clearAll()
                    }
                    refreshSummaries(findPreference, context)
                    ExpDialogChrome.notify(context, R.string.settings_download_cleared_all)
                }
            }
            true
        }

        refresh(findPreference, context, scope)
    }

    fun onDownloadFolderPicked(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
        uri: Uri?,
    ) {
        val context = fragment.requireContext()
        if (uri == null) return
        val ok = DownloadTreeAccess.persistTree(context, uri)
        if (ok) {
            StreamflixDownloadManager.relocate(context)
            ExpDialogChrome.notify(context, R.string.settings_download_storage_folder_set)
        } else {
            ExpDialogChrome.notify(context, R.string.settings_download_storage_folder_failed)
        }
        refreshSummaries(findPreference, context)
    }

    private fun confirmClear(
        context: android.content.Context,
        titleRes: Int,
        messageRes: Int,
        onConfirm: () -> Unit,
    ) {
        val builder = if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(context)
        } else {
            AlertDialog.Builder(context)
        }
        val glass = if (ExperimentalMobileDesign.enabled()) {
            ExpDialogChrome.buildGlassMessage(context, context.getString(messageRes))
        } else {
            null
        }
        builder.setTitle(titleRes)
        if (glass != null) builder.setView(glass.root)
        else builder.setMessage(messageRes)
        builder
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    if (glass != null) ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                    else ExpDialogChrome.polishButtons(dialog)
                }
                dialog.show()
            }
    }

    fun refresh(
        findPreference: (String) -> Preference?,
        context: android.content.Context,
        scope: LifecycleCoroutineScope,
    ) {
        (findPreference("DOWNLOAD_WIFI_ONLY") as? SwitchPreference)?.isChecked =
            UserPreferences.downloadWifiOnly
        (findPreference("DOWNLOAD_SMART_ENABLED") as? SwitchPreference)?.isChecked =
            UserPreferences.downloadSmartEnabled
        (findPreference("DOWNLOAD_AUTO_DELETE_WATCHED") as? SwitchPreference)?.isChecked =
            UserPreferences.downloadAutoDeleteWatched
        (findPreference("DOWNLOAD_NOTIFY_COMPLETE") as? SwitchPreference)?.isChecked =
            UserPreferences.downloadNotifyComplete
        (findPreference("DOWNLOAD_FILTER_CURRENT_PROVIDER") as? SwitchPreference)?.isChecked =
            UserPreferences.downloadFilterCurrentProvider
        (findPreference("DOWNLOAD_QUALITY_PRESET") as? ListPreference)?.value =
            UserPreferences.downloadQualityPreset.name
        (findPreference("DOWNLOAD_MAX_CONCURRENT") as? ListPreference)?.value =
            UserPreferences.downloadMaxConcurrent.toString()
        (findPreference("DOWNLOAD_SOFT_LIMIT_GB") as? EditTextPreference)?.text =
            UserPreferences.downloadSoftLimitGb.toString()
        (findPreference("DOWNLOAD_STORAGE_LOCATION") as? ListPreference)?.value =
            UserPreferences.downloadStorageLocation.name

        refreshSummaries(findPreference, context)
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                DownloadRepository.get(context).getAllOnce()
            }
            findPreference("DOWNLOAD_QUEUE_STATUS")?.summary =
                DownloadStats.queueSummary(context, DownloadStats.fromItems(items))
            val failed = items.count { it.state == DownloadItemState.FAILED.name }
            findPreference("DOWNLOAD_RETRY_FAILED")?.isEnabled = failed > 0
            findPreference("DOWNLOAD_CLEAR_FAILED")?.isEnabled = failed > 0
        }
    }

    private fun refreshSummaries(
        findPreference: (String) -> Preference?,
        context: android.content.Context,
    ) {
        findPreference("DOWNLOAD_STORAGE_PATH")?.summary =
            DownloadStorage.absolutePathSummary(context)
        findPreference("DOWNLOAD_STORAGE_USED")?.summary =
            DownloadStats.storageSummary(context)

        val treeName = DownloadTreeAccess.displayName(context)
        findPreference("DOWNLOAD_STORAGE_PICK_FOLDER")?.summary =
            if (!treeName.isNullOrBlank()) {
                context.getString(R.string.settings_download_storage_pick_folder_set, treeName)
            } else {
                context.getString(R.string.settings_download_storage_pick_folder_summary)
            }
        findPreference("DOWNLOAD_STORAGE_CLEAR_FOLDER")?.isEnabled = DownloadTreeAccess.hasTree()

        var playerPkg = UserPreferences.externalPlayerPackage
        if (playerPkg.isNotBlank() && ExternalStreamHandoff.isDownloaderPackage(playerPkg)) {
            ExternalStreamHandoff.clearPreferredPackage()
            playerPkg = ""
        }
        findPreference("EXTERNAL_PLAYER_DEFAULT")?.summary =
            if (playerPkg.isNotBlank()) {
                context.getString(R.string.external_player_default_summary, playerPkg)
            } else {
                context.getString(R.string.settings_external_player_summary)
            }
        findPreference("EXTERNAL_PLAYER_CLEAR")?.isEnabled = playerPkg.isNotBlank()

        val dlPkg = UserPreferences.externalDownloaderPackage
        findPreference("EXTERNAL_DOWNLOADER_DEFAULT")?.summary =
            if (dlPkg.isNotBlank()) {
                context.getString(R.string.settings_external_downloader_value, dlPkg)
            } else {
                context.getString(R.string.settings_external_downloader_summary)
            }
        findPreference("EXTERNAL_DOWNLOADER_CLEAR")?.isEnabled = dlPkg.isNotBlank()
    }
}
