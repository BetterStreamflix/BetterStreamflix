package com.betterstreamflix.app.download.ui

import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.betterstreamflix.app.R
import com.betterstreamflix.app.download.DownloadHealth
import com.betterstreamflix.app.download.DownloadItemState
import com.betterstreamflix.app.download.DownloadPackUi
import com.betterstreamflix.app.download.DownloadRepository
import com.betterstreamflix.app.download.DownloadStorage
import com.betterstreamflix.app.download.OfflineExpiryPolicy
import com.betterstreamflix.app.fragments.settings.SettingsDeepLink
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Binds Offline Studio Care + Controls panels and the status rail on Downloads
 * (Mobile + TV). Writes the same [com.betterstreamflix.app.utils.UserPreferences]
 * keys as Settings; never touches the download engine directly.
 */
class OfflineStudioController(
    private val fragment: Fragment,
    private val root: View,
    private val viewModel: DownloadsViewModel,
    private val isTv: Boolean,
    private val onScanPackQr: () -> Unit,
    private val styleModeChip: (TextView, Boolean) -> Unit,
) {
    private val statusRail: TextView? = root.findViewById(R.id.tv_offline_studio_status_rail)
    private val carePanel: View? = root.findViewById(R.id.sv_offline_studio_care)
    private val controlsPanel: View? = root.findViewById(R.id.sv_offline_studio_controls)

    private val chipLibrary: TextView? = root.findViewById(R.id.chip_mode_library)
    private val chipQueue: TextView? = root.findViewById(R.id.chip_mode_queue)
    private val chipCare: TextView? = root.findViewById(R.id.chip_mode_care)
    private val chipControls: TextView? = root.findViewById(R.id.chip_mode_controls)

    private val laneBody: TextView? = root.findViewById(R.id.tv_offline_studio_lane_body)
    private val healthCard: View? = root.findViewById(R.id.card_offline_studio_health)
    private val healthBody: TextView? = root.findViewById(R.id.tv_offline_studio_health_body)
    private val healthCta: View? = root.findViewById(R.id.btn_offline_studio_health_redownload)
    private val expiryCard: View? = root.findViewById(R.id.card_offline_studio_expiry)
    private val expiryBody: TextView? = root.findViewById(R.id.tv_offline_studio_expiry_body)
    private val expiryKeep: View? = root.findViewById(R.id.btn_offline_studio_expiry_keep)
    private val expiryDelete: View? = root.findViewById(R.id.btn_offline_studio_expiry_delete)
    private val careEmpty: TextView? = root.findViewById(R.id.tv_offline_studio_care_empty)

    // CompoundButton: TV layouts use CheckBox (Leanback-safe); mobile keeps MaterialSwitch.
    private val smartSwitch: CompoundButton? = root.findViewById(R.id.switch_offline_studio_smart)
    private val autoDeleteSwitch: CompoundButton? = root.findViewById(R.id.switch_offline_studio_auto_delete)
    private val wifiSwitch: CompoundButton? = root.findViewById(R.id.switch_offline_studio_wifi)
    private val quotaSummary: TextView? = root.findViewById(R.id.tv_offline_studio_quota_summary)
    private val lookaheadValue: TextView? = root.findViewById(R.id.tv_offline_studio_lookahead_value)
    private val quotaValue: TextView? = root.findViewById(R.id.tv_offline_studio_quota_value)
    private val softValue: TextView? = root.findViewById(R.id.tv_offline_studio_soft_value)
    private val concurrentValue: TextView? = root.findViewById(R.id.tv_offline_studio_concurrent_value)
    private val storageBody: TextView? = root.findViewById(R.id.tv_offline_studio_storage_body)
    private val wifiWindowBody: TextView? = root.findViewById(R.id.tv_offline_studio_wifi_window_body)
    private val packsBody: TextView? = root.findViewById(R.id.tv_offline_studio_packs_body)
    private val packShare: View? = root.findViewById(R.id.btn_offline_studio_pack_share)
    private val packImport: View? = root.findViewById(R.id.btn_offline_studio_pack_import)
    private val openSettings: View? = root.findViewById(R.id.btn_offline_studio_open_settings)

    private var bindingControls = false
    private var lastExpiryIds: List<String> = emptyList()
    private var pendingScrollToPacks = false

    fun attach() {
        // Library + Queue only — Care/Controls duplicate Settings and clutter Downloads.
        chipCare?.visibility = View.GONE
        chipControls?.visibility = View.GONE
        carePanel?.visibility = View.GONE
        controlsPanel?.visibility = View.GONE
        when (viewModel.currentViewMode()) {
            DownloadsViewMode.CARE, DownloadsViewMode.CONTROLS ->
                viewModel.setViewMode(DownloadsViewMode.LIBRARY)
            else -> Unit
        }
        chipLibrary?.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.setViewMode(DownloadsViewMode.LIBRARY)
        }
        chipQueue?.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.setViewMode(DownloadsViewMode.QUEUE)
        }
        if (isTv) {
            listOfNotNull(chipLibrary, chipQueue).forEach {
                it.isFocusable = true
                it.isFocusableInTouchMode = true
            }
            // SS-B10-13: toggles need explicit Leanback focusability.
            listOfNotNull(smartSwitch, autoDeleteSwitch, wifiSwitch).forEach { sw ->
                sw.isFocusable = true
                sw.isFocusableInTouchMode = true
                sw.isClickable = true
            }
        }

        healthCta?.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.redownloadAllUnhealthy()
            viewModel.setViewMode(DownloadsViewMode.QUEUE)
            viewModel.setFilter(DownloadsFilter.DOWNLOADING)
        }
        root.findViewById<View>(R.id.card_offline_studio_lane)?.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.setViewMode(DownloadsViewMode.QUEUE)
            viewModel.setFilter(DownloadsFilter.DOWNLOADING)
        }
        expiryKeep?.setOnClickListener {
            ExpMotion.hapticTap(it)
            if (lastExpiryIds.isNotEmpty()) {
                viewModel.touchDownloads(lastExpiryIds)
                OfflineExpiryPolicy.recordDismissed()
                viewModel.refreshStudioPreferences()
                viewModel.setViewMode(DownloadsViewMode.QUEUE)
            }
        }
        expiryDelete?.setOnClickListener {
            ExpMotion.hapticTap(it)
            if (lastExpiryIds.isEmpty()) return@setOnClickListener
            confirmExpiryDelete(lastExpiryIds)
        }

        root.findViewById<View>(R.id.btn_offline_studio_lookahead_minus)?.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.bumpSmartLookahead(-1)
        }
        root.findViewById<View>(R.id.btn_offline_studio_lookahead_plus)?.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.bumpSmartLookahead(1)
        }
        root.findViewById<View>(R.id.btn_offline_studio_quota_minus)?.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.bumpSmartSeriesQuota(-1)
        }
        root.findViewById<View>(R.id.btn_offline_studio_quota_plus)?.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.bumpSmartSeriesQuota(1)
        }
        root.findViewById<View>(R.id.btn_offline_studio_soft_minus)?.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.bumpSoftLimitGb(-1)
        }
        root.findViewById<View>(R.id.btn_offline_studio_soft_plus)?.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.bumpSoftLimitGb(1)
        }
        root.findViewById<View>(R.id.btn_offline_studio_concurrent_minus)?.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.bumpMaxConcurrent(-1)
        }
        root.findViewById<View>(R.id.btn_offline_studio_concurrent_plus)?.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.bumpMaxConcurrent(1)
        }

        smartSwitch?.setOnCheckedChangeListener { _, checked ->
            if (bindingControls) return@setOnCheckedChangeListener
            viewModel.setSmartEnabled(checked)
        }
        autoDeleteSwitch?.setOnCheckedChangeListener { _, checked ->
            if (bindingControls) return@setOnCheckedChangeListener
            if (checked) {
                confirmEnableAutoDelete()
            } else {
                viewModel.setAutoDeleteWatched(false)
            }
        }
        wifiSwitch?.setOnCheckedChangeListener { _, checked ->
            if (bindingControls) return@setOnCheckedChangeListener
            viewModel.setWifiOnly(checked)
        }

        packShare?.setOnClickListener {
            ExpMotion.hapticTap(it)
            sharePackChooser()
        }
        packImport?.setOnClickListener {
            ExpMotion.hapticTap(it)
            if (isTv) {
                DownloadPackUi.promptImportFromLink(fragment.requireActivity())
            } else {
                onScanPackQr()
            }
        }
        openSettings?.setOnClickListener {
            ExpMotion.hapticTap(it)
            SettingsDeepLink.openDownloadsScreen()
            fragment.findNavController().navigate(R.id.settings)
        }

        statusRail?.setOnClickListener {
            ExpMotion.hapticTap(it)
            // Advanced Care/Controls live in Settings — keep Downloads on Library/Queue.
            SettingsDeepLink.openDownloadsScreen()
            fragment.findNavController().navigate(R.id.settings)
        }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            viewModel.studioSnapshot
                .flowWithLifecycle(fragment.viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .collect { bindSnapshot(it) }
        }
    }

    fun applyMode(mode: DownloadsViewMode) {
        val safe = when (mode) {
            DownloadsViewMode.CARE, DownloadsViewMode.CONTROLS -> DownloadsViewMode.LIBRARY
            else -> mode
        }
        if (safe != mode) {
            viewModel.setViewMode(safe)
            return
        }
        val library = safe == DownloadsViewMode.LIBRARY
        val queue = safe == DownloadsViewMode.QUEUE
        chipLibrary?.let { styleModeChip(it, library) }
        chipQueue?.let { styleModeChip(it, queue) }
        chipCare?.visibility = View.GONE
        chipControls?.visibility = View.GONE
        carePanel?.isVisible = false
        controlsPanel?.isVisible = false
    }

    fun modeTitleRes(mode: DownloadsViewMode): Int = when (mode) {
        DownloadsViewMode.LIBRARY -> R.string.offline_library_title
        DownloadsViewMode.QUEUE -> R.string.downloads_title
        DownloadsViewMode.CARE -> R.string.offline_studio_care_title
        DownloadsViewMode.CONTROLS -> R.string.offline_studio_controls_title
    }

    private fun scrollControlsToPacks() {
        val scroll = controlsPanel ?: return
        val card = root.findViewById<View>(R.id.card_offline_studio_packs) ?: return
        scroll.post {
            scroll.scrollTo(0, card.top.coerceAtLeast(0))
            if (isTv) card.requestFocus()
        }
    }

    private fun confirmExpiryDelete(ids: List<String>) {
        val ctx = fragment.requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.offline_studio_expiry_delete)
            .setMessage(
                ctx.getString(R.string.offline_studio_expiry_delete_confirm, ids.size),
            )
            .setPositiveButton(R.string.offline_studio_expiry_delete) { _, _ ->
                viewModel.removeDownloads(ids)
                OfflineExpiryPolicy.recordDismissed()
                viewModel.refreshStudioPreferences()
                viewModel.setViewMode(DownloadsViewMode.QUEUE)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmEnableAutoDelete() {
        val ctx = fragment.requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.offline_studio_auto_delete)
            .setMessage(R.string.offline_studio_auto_delete_confirm)
            .setPositiveButton(R.string.offline_studio_auto_delete_clean_now) { _, _ ->
                viewModel.setAutoDeleteWatched(true)
                viewModel.clearWatched()
            }
            .setNeutralButton(R.string.offline_studio_auto_delete_enable_only) { _, _ ->
                viewModel.setAutoDeleteWatched(true)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                bindingControls = true
                autoDeleteSwitch?.isChecked = false
                bindingControls = false
            }
            .setOnCancelListener {
                bindingControls = true
                autoDeleteSwitch?.isChecked = false
                bindingControls = false
            }
            .show()
    }

    private fun bindSnapshot(snap: OfflineStudioSnapshot) {
        val ctx = fragment.requireContext()
        applyDensityChrome()
        statusRail?.let { rail ->
            val parts = buildList {
                if (snap.activeCount > 0) {
                    add(ctx.getString(R.string.offline_studio_rail_lane, snap.activeCount))
                }
                if (snap.health.hasUnhealthy) {
                    add(ctx.getString(R.string.offline_studio_rail_health, snap.health.count))
                }
                if (snap.expiryCount > 0) {
                    add(ctx.getString(R.string.offline_studio_rail_expiry, snap.expiryCount))
                }
                if (snap.packCount > 0) {
                    add(ctx.getString(R.string.offline_studio_rail_packs, snap.packCount))
                }
            }
            rail.isVisible = parts.isNotEmpty()
            rail.text = parts.joinToString(" · ")
            rail.contentDescription = rail.text
        }

        laneBody?.text = ctx.getString(
            R.string.offline_studio_lane_body,
            snap.watchingLaneCount,
            snap.queueLaneCount,
            snap.smartLaneCount,
        )

        healthCard?.isVisible = snap.health.hasUnhealthy
        if (snap.health.hasUnhealthy) {
            healthBody?.text = DownloadHealth.bannerMessage(ctx, snap.health)
        }

        lastExpiryIds = snap.expiryIds
        expiryCard?.isVisible = snap.expiryCount > 0
        if (snap.expiryCount > 0) {
            val more = (snap.expiryCount - snap.expiryTitles.size).coerceAtLeast(0)
            val lines = buildString {
                snap.expiryTitles.forEachIndexed { i, title ->
                    if (i > 0) append('\n')
                    append(title)
                }
                if (more > 0) {
                    if (isNotEmpty()) append('\n')
                    append(ctx.getString(R.string.offline_studio_expiry_more, more))
                }
            }
            expiryBody?.text = lines
        }

        val careHasWork = snap.health.hasUnhealthy || snap.expiryCount > 0
        careEmpty?.isVisible = !careHasWork
        if (!careHasWork) {
            careEmpty?.setText(
                if (snap.laneActiveCount > 0) {
                    R.string.offline_studio_care_empty_lane
                } else {
                    R.string.offline_studio_care_empty
                },
            )
        }

        bindingControls = true
        smartSwitch?.isChecked = snap.smartEnabled
        autoDeleteSwitch?.isChecked = snap.autoDeleteWatched
        wifiSwitch?.isChecked = snap.wifiOnly
        bindingControls = false

        lookaheadValue?.text = snap.smartLookahead.toString()
        quotaValue?.text = snap.smartSeriesQuota.toString()
        concurrentValue?.text = snap.maxConcurrent.toString()
        softValue?.text = if (snap.softLimitGb <= 0) {
            ctx.getString(R.string.settings_download_soft_limit_unlimited)
        } else {
            snap.softLimitGb.toString()
        }
        quotaSummary?.text = ctx.getString(
            R.string.offline_studio_quota_summary,
            snap.maxSeriesOccupancy,
            snap.smartSeriesQuota,
            snap.seriesWithQuota,
        )
        // Disk walk must not run on the main collector (File IO on Main Thread).
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val (used, free) = withContext(Dispatchers.IO) {
                DownloadStorage.formatBytes(ctx, DownloadStorage.usedBytes(ctx)) to
                    DownloadStorage.formatBytes(ctx, DownloadStorage.freeBytes(ctx))
            }
            if (!fragment.isAdded) return@launch
            storageBody?.text = ctx.getString(
                R.string.offline_studio_storage_body,
                if (snap.softLimitGb <= 0) {
                    ctx.getString(R.string.settings_download_soft_limit_unlimited)
                } else {
                    ctx.getString(R.string.settings_download_soft_limit_value, snap.softLimitGb)
                },
                snap.maxConcurrent,
                if (snap.wifiOnly) {
                    ctx.getString(R.string.offline_studio_wifi_on)
                } else {
                    ctx.getString(R.string.offline_studio_wifi_off)
                },
                used,
                free,
            )
        }
        wifiWindowBody?.text = wifiWindowSummary(ctx)
        packsBody?.text = when {
            snap.packCount <= 0 -> ctx.getString(R.string.offline_studio_packs_empty)
            snap.packCount == 1 -> ctx.getString(R.string.offline_studio_packs_one)
            else -> ctx.getString(R.string.offline_studio_packs_many, snap.packCount)
        }
        packShare?.isEnabled = snap.packCount > 0
        packImport?.let { btn ->
            (btn as? TextView)?.setText(
                if (isTv) R.string.offline_studio_pack_import_tv else R.string.offline_studio_pack_import,
            )
        }
    }

    private fun wifiWindowSummary(ctx: android.content.Context): String {
        if (!UserPreferences.downloadWifiWindowEnabled) {
            return ctx.getString(R.string.offline_studio_wifi_window_off)
        }
        return ctx.getString(
            R.string.offline_studio_wifi_window_summary,
            formatMinute(UserPreferences.downloadWifiWindowStartMinute),
            formatMinute(UserPreferences.downloadWifiWindowEndMinute),
        )
    }

    private fun formatMinute(minuteOfDay: Int): String {
        val m = minuteOfDay.coerceIn(0, 24 * 60 - 1)
        return String.format("%02d:%02d", m / 60, m % 60)
    }

    private fun sharePackChooser() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val packs = withContext(Dispatchers.IO) {
                runCatching {
                    DownloadRepository.get(fragment.requireContext().applicationContext)
                        .observeSeasonPacks()
                        .first()
                }.getOrDefault(emptyList())
            }
            if (packs.isEmpty()) {
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.offline_studio_packs_empty,
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            val completed = packs.filter { it.state == DownloadItemState.COMPLETED.name }
            val candidates = if (completed.isNotEmpty()) completed else packs
            if (candidates.size == 1) {
                DownloadPackUi.shareSeasonPack(fragment, candidates.first())
                return@launch
            }
            val labels = candidates.map { pack ->
                fragment.getString(
                    R.string.offline_studio_pack_picker_item,
                    pack.tvShowTitle,
                    pack.seasonNumber,
                    pack.completedEpisodes,
                    pack.totalEpisodes,
                )
            }.toTypedArray()
            AlertDialog.Builder(fragment.requireContext())
                .setTitle(R.string.offline_studio_pack_share)
                .setItems(labels) { _, which ->
                    candidates.getOrNull(which)?.let { pack ->
                        DownloadPackUi.shareSeasonPack(fragment, pack)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Compact Leanback: slightly tighter Care/Controls typography and padding (LD-015). */
    private fun applyDensityChrome() {
        if (!isTv) return
        val compact = com.betterstreamflix.app.ui.LeanbackDensityPolicy.offlineStudioCompactChrome()
        val textSp = if (compact) 13f else 15f
        val padDp = if (compact) 10 else 16
        val padPx = (padDp * fragment.resources.displayMetrics.density).toInt()
        listOfNotNull(carePanel, controlsPanel).forEach { panel ->
            panel.setPadding(padPx, padPx, padPx, padPx)
        }
        listOfNotNull(
            laneBody,
            healthBody,
            expiryBody,
            quotaSummary,
            storageBody,
            packsBody,
            statusRail,
        ).forEach { tv ->
            tv.textSize = textSp
        }
    }
}
