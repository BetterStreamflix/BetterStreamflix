package com.dskja.betterstreamflix.download.ui

import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.download.DownloadHealth
import com.dskja.betterstreamflix.download.DownloadPackUi
import com.dskja.betterstreamflix.download.DownloadRepository
import com.dskja.betterstreamflix.fragments.settings.SettingsDeepLink
import com.dskja.betterstreamflix.utils.ExpMotion
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Binds Offline Studio Care + Controls panels and the status rail on Downloads
 * (Mobile + TV). Writes the same [com.dskja.betterstreamflix.utils.UserPreferences]
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
    private val careEmpty: View? = root.findViewById(R.id.tv_offline_studio_care_empty)

    private val smartSwitch: MaterialSwitch? = root.findViewById(R.id.switch_offline_studio_smart)
    private val autoDeleteSwitch: MaterialSwitch? = root.findViewById(R.id.switch_offline_studio_auto_delete)
    private val quotaSummary: TextView? = root.findViewById(R.id.tv_offline_studio_quota_summary)
    private val lookaheadValue: TextView? = root.findViewById(R.id.tv_offline_studio_lookahead_value)
    private val quotaValue: TextView? = root.findViewById(R.id.tv_offline_studio_quota_value)
    private val softValue: TextView? = root.findViewById(R.id.tv_offline_studio_soft_value)
    private val storageBody: TextView? = root.findViewById(R.id.tv_offline_studio_storage_body)
    private val packsBody: TextView? = root.findViewById(R.id.tv_offline_studio_packs_body)
    private val packShare: View? = root.findViewById(R.id.btn_offline_studio_pack_share)
    private val packImport: View? = root.findViewById(R.id.btn_offline_studio_pack_import)
    private val openSettings: View? = root.findViewById(R.id.btn_offline_studio_open_settings)

    private var bindingControls = false
    private var lastExpiryIds: List<String> = emptyList()

    fun attach() {
        chipLibrary?.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.setViewMode(DownloadsViewMode.LIBRARY)
        }
        chipQueue?.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.setViewMode(DownloadsViewMode.QUEUE)
        }
        chipCare?.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.setViewMode(DownloadsViewMode.CARE)
        }
        chipControls?.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.setViewMode(DownloadsViewMode.CONTROLS)
        }
        if (isTv) {
            listOfNotNull(chipLibrary, chipQueue, chipCare, chipControls).forEach {
                it.isFocusable = true
                it.isFocusableInTouchMode = true
            }
        }

        healthCta?.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.redownloadAllUnhealthy()
        }
        expiryKeep?.setOnClickListener {
            ExpMotion.hapticTap(it)
            if (lastExpiryIds.isNotEmpty()) viewModel.touchDownloads(lastExpiryIds)
        }
        expiryDelete?.setOnClickListener {
            ExpMotion.hapticTap(it)
            if (lastExpiryIds.isNotEmpty()) viewModel.removeDownloads(lastExpiryIds)
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

        smartSwitch?.setOnCheckedChangeListener { _, checked ->
            if (bindingControls) return@setOnCheckedChangeListener
            viewModel.setSmartEnabled(checked)
        }
        autoDeleteSwitch?.setOnCheckedChangeListener { _, checked ->
            if (bindingControls) return@setOnCheckedChangeListener
            viewModel.setAutoDeleteWatched(checked)
        }

        packShare?.setOnClickListener {
            ExpMotion.hapticTap(it)
            shareLatestPack()
        }
        packImport?.setOnClickListener {
            ExpMotion.hapticTap(it)
            if (isTv) {
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.offline_studio_pack_import_tv_hint,
                    Toast.LENGTH_LONG,
                ).show()
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
            val snap = viewModel.studioSnapshot.value
            viewModel.setViewMode(
                when {
                    snap.health.hasUnhealthy || snap.expiryCount > 0 -> DownloadsViewMode.CARE
                    else -> DownloadsViewMode.CONTROLS
                },
            )
        }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            viewModel.studioSnapshot
                .flowWithLifecycle(fragment.viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .collect { bindSnapshot(it) }
        }
    }

    fun applyMode(mode: DownloadsViewMode) {
        val library = mode == DownloadsViewMode.LIBRARY
        val queue = mode == DownloadsViewMode.QUEUE
        val care = mode == DownloadsViewMode.CARE
        val controls = mode == DownloadsViewMode.CONTROLS
        chipLibrary?.let { styleModeChip(it, library) }
        chipQueue?.let { styleModeChip(it, queue) }
        chipCare?.let { styleModeChip(it, care) }
        chipControls?.let { styleModeChip(it, controls) }
        carePanel?.isVisible = care
        controlsPanel?.isVisible = controls
    }

    fun modeTitleRes(mode: DownloadsViewMode): Int = when (mode) {
        DownloadsViewMode.LIBRARY -> R.string.offline_library_title
        DownloadsViewMode.QUEUE -> R.string.downloads_title
        DownloadsViewMode.CARE -> R.string.offline_studio_care_title
        DownloadsViewMode.CONTROLS -> R.string.offline_studio_controls_title
    }

    private fun bindSnapshot(snap: OfflineStudioSnapshot) {
        val ctx = fragment.requireContext()
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

        bindingControls = true
        smartSwitch?.isChecked = snap.smartEnabled
        autoDeleteSwitch?.isChecked = snap.autoDeleteWatched
        bindingControls = false

        lookaheadValue?.text = snap.smartLookahead.toString()
        quotaValue?.text = snap.smartSeriesQuota.toString()
        softValue?.text = if (snap.softLimitGb <= 0) {
            ctx.getString(R.string.offline_studio_soft_unlimited)
        } else {
            snap.softLimitGb.toString()
        }
        quotaSummary?.text = ctx.getString(
            R.string.offline_studio_quota_summary,
            snap.seriesQuotaOccupied,
            snap.seriesWithQuota,
            snap.smartSeriesQuota,
        )
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
        )
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

    private fun shareLatestPack() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val chosen = withContext(Dispatchers.IO) {
                runCatching {
                    DownloadRepository.get(fragment.requireContext().applicationContext)
                        .observeSeasonPacks()
                        .first()
                        .maxByOrNull { it.updatedAt }
                }.getOrNull()
            }
            if (chosen == null) {
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.offline_studio_packs_empty,
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            DownloadPackUi.shareSeasonPack(fragment, chosen)
        }
    }
}
