package com.dskja.betterstreamflix.download.ui

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.download.DownloadConnectivityMonitor
import com.dskja.betterstreamflix.download.DownloadController
import com.dskja.betterstreamflix.download.DownloadEnqueueOutcome
import com.dskja.betterstreamflix.download.DownloadErrorCode
import com.dskja.betterstreamflix.download.DownloadPrepareResult
import com.dskja.betterstreamflix.download.DownloadQualityPreset
import com.dskja.betterstreamflix.download.DownloadStorage
import com.dskja.betterstreamflix.download.DownloadTrackOption
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object DownloadOptionsController {
    fun enqueueMovie(fragment: Fragment, movie: Movie) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val loading = showPreparing(fragment.requireActivity())
            try {
                handleOutcome(fragment, withContext(Dispatchers.IO) {
                    DownloadController.prepareMovie(fragment.requireContext(), movie)
                })
            } finally {
                loading.dismissQuietly()
            }
        }
    }

    fun enqueueEpisode(fragment: Fragment, episode: Episode) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val loading = showPreparing(fragment.requireActivity())
            try {
                handleOutcome(fragment, withContext(Dispatchers.IO) {
                    DownloadController.prepareEpisode(fragment.requireContext(), episode)
                })
            } finally {
                loading.dismissQuietly()
            }
        }
    }

    fun enqueueEpisode(context: Context, activity: Activity, episode: Episode, onNavigateDownloads: () -> Unit = {}) {
        activity.lifecycleScopeOrMain().launch {
            val loading = showPreparing(activity)
            try {
                val outcome = withContext(Dispatchers.IO) {
                    DownloadController.prepareEpisode(context, episode)
                }
                handleOutcomeActivity(activity, outcome, onNavigateDownloads)
            } finally {
                loading.dismissQuietly()
            }
        }
    }

    fun enqueueMovie(context: Context, activity: Activity, movie: Movie, onNavigateDownloads: () -> Unit = {}) {
        activity.lifecycleScopeOrMain().launch {
            val loading = showPreparing(activity)
            try {
                val outcome = withContext(Dispatchers.IO) {
                    DownloadController.prepareMovie(context, movie)
                }
                handleOutcomeActivity(activity, outcome, onNavigateDownloads)
            } finally {
                loading.dismissQuietly()
            }
        }
    }

    fun enqueueFromPlayer(
        fragment: Fragment,
        videoType: Video.Type,
        server: Video.Server,
        video: Video,
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val loading = showPreparing(fragment.requireActivity())
            try {
                handleOutcome(fragment, withContext(Dispatchers.IO) {
                    DownloadController.prepareFromResolved(
                        fragment.requireContext(),
                        videoType,
                        server,
                        video,
                    )
                })
            } finally {
                loading.dismissQuietly()
            }
        }
    }

    fun offerTvShowDownload(
        fragment: Fragment,
        tvShow: TvShow,
        episodeToWatch: Episode?,
    ) {
        val context = fragment.requireContext()
        val seasons = tvShow.seasons.filter { it.episodes.isNotEmpty() }
        data class Choice(val label: String, val run: () -> Unit)
        val choices = buildList {
            episodeToWatch?.let { episode ->
                val seasonNo = episode.season?.number
                    ?: tvShow.seasons.firstOrNull { season ->
                        season.episodes.any { it.id == episode.id }
                    }?.number
                    ?: 1
                add(
                    Choice(
                        context.getString(
                            R.string.detail_download_episode_short,
                            seasonNo,
                            episode.number,
                        ),
                    ) { enqueueEpisode(fragment, episode) },
                )
            }
            seasons.forEach { season ->
                add(
                    Choice(
                        context.getString(
                            R.string.detail_download_season_count,
                            season.number,
                            season.episodes.size,
                        ),
                    ) { enqueueSeason(fragment, tvShow, season.number, season.episodes) },
                )
            }
        }
        when {
            choices.isEmpty() -> ExpDialogChrome.notify(
                context,
                R.string.detail_download_season,
                R.string.season_download,
            )
            choices.size == 1 -> choices.first().run()
            else -> {
                val dialog = (
                    if (ExperimentalMobileDesign.enabled()) {
                        MaterialAlertDialogBuilder(context)
                    } else {
                        AlertDialog.Builder(context)
                    }
                    )
                    .setTitle(R.string.option_show_download)
                    .setItems(choices.map { it.label }.toTypedArray()) { _, which ->
                        choices.getOrNull(which)?.run?.invoke()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                dialog.setOnShowListener { ExpDialogChrome.polishButtons(dialog) }
                dialog.show()
            }
        }
    }

    fun enqueueSeason(
        fragment: Fragment,
        tvShow: TvShow,
        seasonNumber: Int,
        episodes: List<Episode>,
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            maybeRequestNotifications(fragment.requireActivity())
            val loading = showPreparing(fragment.requireActivity())
            try {
                val outcome = withContext(Dispatchers.IO) {
                    DownloadController.enqueueSeason(
                        context = fragment.requireContext(),
                        tvShow = tvShow,
                        seasonNumber = seasonNumber,
                        episodes = episodes,
                    ) { prepared ->
                        val serverIdx = prepared.selectedServerIndex.coerceIn(
                            0,
                            prepared.servers.lastIndex.coerceAtLeast(0),
                        )
                        val trackIdx = when (UserPreferences.downloadQualityPreset) {
                            DownloadQualityPreset.DATA_SAVER ->
                                prepared.trackOptions.lastIndex.coerceAtLeast(0)
                            else -> 0
                        }.coerceIn(0, prepared.trackOptions.lastIndex.coerceAtLeast(0))
                        serverIdx to trackIdx
                    }
                }
                handleOutcome(fragment, outcome)
            } finally {
                loading.dismissQuietly()
            }
        }
    }

    private suspend fun handleOutcome(fragment: Fragment, outcome: DownloadEnqueueOutcome) {
        handleOutcomeActivity(fragment.requireActivity(), outcome) {
            runCatching {
                androidx.navigation.fragment.NavHostFragment.findNavController(fragment)
                    .navigate(R.id.downloads)
            }
        }
    }

    private suspend fun handleOutcomeActivity(
        activity: Activity,
        outcome: DownloadEnqueueOutcome,
        onNavigateDownloads: () -> Unit,
    ) {
        when (outcome) {
            is DownloadEnqueueOutcome.NeedsOptions -> {
                if (UserPreferences.downloadWifiOnly &&
                    DownloadConnectivityMonitor.isMetered(activity)
                ) {
                    val proceed = confirmCellular(activity)
                    if (!proceed) {
                        notifyUser(
                            activity,
                            activity.getString(R.string.download_error_wifi),
                            asInfo = true,
                        )
                        return
                    }
                }
                maybeRequestNotifications(activity)
                showOptionsDialog(activity, outcome.prepared)
            }
            is DownloadEnqueueOutcome.Started -> {
                maybeRequestNotifications(activity)
                notifyUser(activity, activity.getString(R.string.downloads_started))
            }
            is DownloadEnqueueOutcome.AlreadyActive -> {
                notifyUser(activity, activity.getString(R.string.downloads_already_active))
                onNavigateDownloads()
            }
            is DownloadEnqueueOutcome.AlreadyCompleted -> {
                notifyUser(activity, activity.getString(R.string.downloads_already_completed))
                onNavigateDownloads()
            }
            is DownloadEnqueueOutcome.Failed -> {
                notifyUser(
                    activity,
                    localizedError(activity, outcome.code, outcome.message),
                    asInfo = true,
                )
            }
        }
    }

    private fun showOptionsDialog(activity: Activity, prepared: DownloadPrepareResult) {
        val view = LayoutInflater.from(activity).inflate(
            R.layout.dialog_download_options,
            null,
        )
        val titleView = view.findViewById<TextView>(R.id.tv_download_options_title)
        val subtitleView = view.findViewById<TextView>(R.id.tv_download_options_subtitle)
        val storageView = view.findViewById<TextView>(R.id.tv_download_options_storage)
        val serverSpinner = view.findViewById<Spinner>(R.id.sp_download_server)
        val qualitySpinner = view.findViewById<Spinner>(R.id.sp_download_quality)
        val loading = view.findViewById<ProgressBar>(R.id.pb_download_options_loading)

        titleView.text = prepared.title
        subtitleView.text = prepared.subtitle
        storageView.text = activity.getString(
            R.string.download_options_storage,
            DownloadStorage.formatBytes(DownloadStorage.freeBytes(activity)),
        )
        if (ExperimentalMobileDesign.enabled()) {
            view.setBackgroundResource(ExperimentalMobileDesign.bottomSheetBackground())
            storageView.setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
            val density = activity.resources.displayMetrics.density
            storageView.setPadding(
                (12 * density).toInt(),
                (6 * density).toInt(),
                (12 * density).toInt(),
                (6 * density).toInt(),
            )
            ExpMotion.revealHeader(titleView, subtitleView, storageView)
            ExpMotion.popIn(storageView)
        }

        var currentPrepared = prepared
        var trackOptions = prepared.trackOptions.toMutableList()
        var serverIndex = prepared.selectedServerIndex.coerceIn(0, prepared.servers.lastIndex.coerceAtLeast(0))
        var qualityIndex = defaultQualityIndex(trackOptions)
        var tracksJob: Job? = null

        val serverNames = prepared.servers.map { it.server.name }.ifEmpty { listOf("Auto") }
        val itemLayout = android.R.layout.simple_spinner_item
        val dropdownLayout = android.R.layout.simple_spinner_dropdown_item
        serverSpinner.adapter = ArrayAdapter(activity, itemLayout, serverNames).also {
            it.setDropDownViewResource(dropdownLayout)
        }
        serverSpinner.setSelection(serverIndex)
        if (ExperimentalMobileDesign.enabled()) {
            serverSpinner.setPopupBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
            qualitySpinner.setPopupBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
        }

        fun bindQualitySpinner(options: List<DownloadTrackOption>, preferredIndex: Int) {
            val labels = options.map { it.label }.ifEmpty { listOf("Auto") }
            qualitySpinner.adapter = ArrayAdapter(activity, itemLayout, labels).also {
                it.setDropDownViewResource(dropdownLayout)
            }
            qualityIndex = preferredIndex.coerceIn(0, labels.lastIndex.coerceAtLeast(0))
            qualitySpinner.setSelection(qualityIndex)
        }
        bindQualitySpinner(trackOptions, qualityIndex)

        qualitySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                qualityIndex = position
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        var optionsDialog: androidx.appcompat.app.AlertDialog? = null
        serverSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == serverIndex) return
                serverIndex = position
                val candidate = currentPrepared.servers.getOrNull(position) ?: return
                tracksJob?.cancel()
                tracksJob = activity.lifecycleScopeOrMain().launch {
                    if (ExperimentalMobileDesign.enabled()) {
                        ExpMotion.fadeInAndShow(loading)
                    } else {
                        loading.visibility = View.VISIBLE
                    }
                    qualitySpinner.isEnabled = false
                    optionsDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
                    try {
                        val options = withContext(Dispatchers.IO) {
                            DownloadController.prepareTrackOptions(activity, candidate.video)
                        }
                        trackOptions.clear()
                        trackOptions.addAll(options)
                        currentPrepared = currentPrepared.copy(trackOptions = options, selectedServerIndex = position)
                        bindQualitySpinner(options, defaultQualityIndex(options))
                    } finally {
                        qualitySpinner.isEnabled = true
                        optionsDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                        if (ExperimentalMobileDesign.enabled()) {
                            ExpMotion.fadeOutAndHide(loading)
                        } else {
                            loading.visibility = View.GONE
                        }
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val optionsBuilder = if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(activity)
        } else {
            AlertDialog.Builder(activity)
        }
        optionsBuilder
            .setView(view)
            .setPositiveButton(R.string.download_options_start) { _, _ ->
                if (ExperimentalMobileDesign.enabled()) {
                    ExpMotion.hapticTap(view)
                }
                tracksJob?.cancel()
                activity.lifecycleScopeOrMain().launch {
                    val result = withContext(Dispatchers.IO) {
                        DownloadController.confirmEnqueue(
                            activity,
                            currentPrepared,
                            serverIndex,
                            qualityIndex,
                            trackOptions.getOrNull(qualityIndex)?.label ?: "Auto",
                        )
                    }
                    handleOutcomeActivity(activity, result) {}
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                if (ExperimentalMobileDesign.enabled()) {
                    ExpMotion.hapticTap(view)
                }
                tracksJob?.cancel()
            }
            .setOnCancelListener { tracksJob?.cancel() }
            .create()
            .also { dialog ->
                optionsDialog = dialog
                dialog.setOnShowListener {
                    if (ExperimentalMobileDesign.enabled()) {
                        ExperimentalMobileDesign.applyReducedGlass(view)
                        ExpMotion.revealHeader(titleView, subtitleView)
                        ExpMotion.pulseAccentRule(view.findViewById(R.id.v_download_options_rule))
                        ExpMotion.popIn(view)
                        com.dskja.betterstreamflix.utils.ExpDialogChrome.polishShown(dialog)
                        with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                            serverSpinner.applyExpPress()
                            qualitySpinner.applyExpPress()
                        }
                        serverSpinner.setBackgroundResource(ExperimentalMobileDesign.spinnerBackground())
                        qualitySpinner.setBackgroundResource(ExperimentalMobileDesign.spinnerBackground())
                    }
                }
            }
            .show()
    }

    private fun defaultQualityIndex(options: List<DownloadTrackOption>): Int {
        if (options.isEmpty()) return 0
        return when (UserPreferences.downloadQualityPreset) {
            DownloadQualityPreset.DATA_SAVER -> options.lastIndex
            DownloadQualityPreset.BEST,
            DownloadQualityPreset.ASK,
            -> 0
        }
    }

    private suspend fun confirmCellular(activity: Activity): Boolean {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            activity.runOnUiThread {
                val builder = if (ExperimentalMobileDesign.enabled()) {
                    MaterialAlertDialogBuilder(activity)
                } else {
                    AlertDialog.Builder(activity)
                }
                val glass = if (ExperimentalMobileDesign.enabled()) {
                    ExpDialogChrome.buildGlassMessage(
                        activity,
                        activity.getString(R.string.download_options_cellular_confirm_message),
                    )
                } else {
                    null
                }
                builder.setTitle(R.string.download_options_cellular_confirm_title)
                if (glass != null) builder.setView(glass.root)
                else builder.setMessage(R.string.download_options_cellular_confirm_message)
                builder
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        if (cont.isActive) cont.resume(true) {}
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        if (cont.isActive) cont.resume(false) {}
                    }
                    .setOnCancelListener {
                        if (cont.isActive) cont.resume(false) {}
                    }
                    .create()
                    .also { dialog ->
                        dialog.setOnShowListener {
                            if (glass != null) {
                                ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                            } else {
                                ExpDialogChrome.polishButtons(dialog)
                            }
                        }
                        dialog.show()
                    }
            }
        }
    }

    fun localizedError(context: Context, code: DownloadErrorCode, fallback: String): String {
        val res = when (code) {
            DownloadErrorCode.IPTV -> R.string.download_error_iptv
            DownloadErrorCode.DRM -> R.string.download_error_drm
            DownloadErrorCode.NO_SERVERS -> R.string.download_error_no_servers
            DownloadErrorCode.CLOUDFLARE -> R.string.download_error_cloudflare
            DownloadErrorCode.NETWORK -> R.string.download_error_network
            DownloadErrorCode.NOSPACE -> R.string.download_error_nospace
            DownloadErrorCode.WIFI_REQUIRED -> R.string.download_error_wifi
            DownloadErrorCode.EXPIRED -> R.string.download_error_expired
            DownloadErrorCode.FILE_MISSING -> R.string.download_error_file_missing
            DownloadErrorCode.UNSUPPORTED -> R.string.download_error_unsupported
            DownloadErrorCode.EMPTY_RESPONSE -> R.string.download_error_empty_response
            DownloadErrorCode.UNKNOWN -> null
        }
        // Also map raw JSON EOF messages that slipped through as UNKNOWN
        if (res == null) {
            val low = fallback.lowercase()
            if (low.contains("end of input") || low.contains("character 0")) {
                return context.getString(R.string.download_error_empty_response)
            }
        }
        return res?.let { context.getString(it) } ?: fallback.ifBlank {
            context.getString(R.string.downloads_failed_generic)
        }
    }

    private fun maybeRequestNotifications(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        ActivityCompat.requestPermissions(activity, arrayOf(permission), 4209)
    }

    private fun showPreparing(activity: Activity): AlertDialog {
        val builder = if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(activity)
        } else {
            AlertDialog.Builder(activity)
        }
        val preparing = activity.getString(R.string.download_preparing)
        val glass = if (ExperimentalMobileDesign.enabled()) {
            ExpDialogChrome.buildGlassMessage(activity, preparing)
        } else {
            null
        }
        if (glass != null) builder.setView(glass.root)
        else builder.setMessage(preparing)
        return builder
            .setCancelable(false)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    if (glass != null) ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                    else ExpDialogChrome.polishButtons(dialog)
                }
                runCatching { dialog.show() }
            }
    }

    private fun AlertDialog.dismissQuietly() {
        runCatching { if (isShowing) dismiss() }
    }

    private fun notifyUser(context: Context, message: String, asInfo: Boolean = false) {
        if (ExperimentalMobileDesign.enabled()) {
            if (asInfo) {
                ExpDialogChrome.showInfo(
                    context,
                    R.string.downloads_title,
                    message,
                ) { ctx ->
                    MaterialAlertDialogBuilder(ctx)
                }
            } else {
                ExpDialogChrome.notify(context, message, R.string.downloads_title)
            }
        } else {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun Activity.lifecycleScopeOrMain() =
        (this as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope
            ?: throw IllegalStateException("Activity must be a LifecycleOwner")
}
