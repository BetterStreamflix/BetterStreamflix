package com.dskja.betterstreamflix.fragments.downloads

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.FragmentDownloadsMobileBinding
import com.dskja.betterstreamflix.download.DownloadController
import com.dskja.betterstreamflix.download.DownloadItemState
import com.dskja.betterstreamflix.download.OfflinePlayback
import com.dskja.betterstreamflix.download.ui.DownloadOptionsController
import com.dskja.betterstreamflix.download.ui.DownloadRowUiModel
import com.dskja.betterstreamflix.download.ui.DownloadsAdapter
import com.dskja.betterstreamflix.download.ui.DownloadsFilter
import com.dskja.betterstreamflix.download.ui.DownloadsSort
import com.dskja.betterstreamflix.download.ui.DownloadsViewModel
import com.dskja.betterstreamflix.fragments.settings.SettingsDeepLink
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.viewModelsFactory
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpNavAutoHide
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadsMobileFragment : Fragment() {
    private var _binding: FragmentDownloadsMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModelsFactory {
        DownloadsViewModel(requireContext().applicationContext)
    }

    private val adapter = DownloadsAdapter(
        onPlay = { playOffline(it) },
        onPauseResume = { row ->
            if (row.state == DownloadItemState.PAUSED) viewModel.resume(row.id)
            else viewModel.pause(row.id)
        },
        onRetry = { retry(it) },
        onDelete = { viewModel.remove(it.id) },
        onItemMore = { row, anchor -> showItemMenu(row, anchor) },
        onPackMore = { pack, anchor -> showPackMenu(pack, anchor) },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDownloadsMobileBinding.bind(
            inflater.inflate(
                R.layout.fragment_downloads_mobile,
                container,
                false,
            )
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ExpNavAutoHide.attach(binding.root)
        ExpMotion.enterScreen(binding.root)
        ExperimentalMobileDesign.applyReducedGlass(binding.root)
        ExpMotion.staggerFirstFill(binding.rvDownloads)
        if (ExperimentalMobileDesign.enabled()) {
            ExpMotion.revealHeader(
                binding.root.findViewById(R.id.tv_downloads_eyebrow),
                binding.tvDownloadsTitle,
                binding.root.findViewById(R.id.tv_downloads_tagline),
                binding.root.findViewById(R.id.v_downloads_rule),
            )
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_downloads_rule))
            binding.btnDownloadsMenu.setBackgroundResource(
                ExperimentalMobileDesign.iconChipBackground(),
            )
            ExpMotion.popIn(binding.btnDownloadsMenu)
            listOf(
                binding.chipFilterAll,
                binding.chipFilterDownloading,
                binding.chipFilterCompleted,
                binding.chipFilterFailed,
            ).forEachIndexed { index, chip ->
                chip.postDelayed({ ExpMotion.popIn(chip) }, 32L * index)
            }
        }
        binding.rvDownloads.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDownloads.itemAnimator = null
        binding.rvDownloads.adapter = adapter
        binding.btnDownloadsMenu.apply {
            if (ExperimentalMobileDesign.enabled()) {
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { applyExpPress() }
            }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                showMenu(it)
            }
        }
        listOf(
            binding.chipFilterAll to DownloadsFilter.ALL,
            binding.chipFilterDownloading to DownloadsFilter.DOWNLOADING,
            binding.chipFilterCompleted to DownloadsFilter.COMPLETED,
            binding.chipFilterFailed to DownloadsFilter.FAILED,
        ).forEach { (chip, filter) ->
            if (ExperimentalMobileDesign.enabled()) {
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { chip.applyExpPress() }
            }
            chip.setOnClickListener {
                ExpMotion.hapticTap(it)
                viewModel.setFilter(filter)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Combine filter so empty→empty filter switches still refresh CTA copy
            // (StateFlow skips equal emptyList emissions).
            combine(viewModel.rows, viewModel.selectedFilter) { rows, filter -> rows to filter }
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { (rows, filter) ->
                adapter.submitList(rows.toList())
                if (rows.isEmpty()) {
                    val cta = binding.root.findViewById<View>(R.id.btn_downloads_empty_cta)
                    val emptyRule = binding.root.findViewById<View>(R.id.v_downloads_empty_rule)
                    if (ExperimentalMobileDesign.enabled()) {
                        binding.tvDownloadsEmpty.setBackgroundResource(
                            ExperimentalMobileDesign.glassCardBackground(),
                        )
                    }
                    if (binding.tvDownloadsEmpty.visibility != View.VISIBLE) {
                        binding.tvDownloadsEmpty.isVisible = true
                        emptyRule?.isVisible = true
                        if (ExperimentalMobileDesign.enabled()) {
                            ExpMotion.revealHeader(binding.tvDownloadsEmpty, emptyRule, cta)
                            ExpMotion.pulseAccentRule(emptyRule)
                        } else {
                            ExpMotion.fadeInAndShow(binding.tvDownloadsEmpty)
                            emptyRule?.let { ExpMotion.fadeInAndShow(it) }
                            cta?.let { ExpMotion.fadeInAndShow(it) }
                        }
                    } else {
                        binding.tvDownloadsEmpty.isVisible = true
                        emptyRule?.isVisible = true
                    }
                    val filterAll = filter == DownloadsFilter.ALL
                    binding.root.findViewById<android.widget.TextView>(R.id.btn_downloads_empty_cta)?.let { chip ->
                        val wasVisible = chip.isVisible
                        chip.isVisible = true
                        chip.setText(
                            if (filterAll) R.string.exp_empty_browse_catalog
                            else R.string.exp_empty_clear_filter,
                        )
                        if (ExperimentalMobileDesign.enabled()) {
                            chip.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
                            with(com.dskja.betterstreamflix.utils.ExpPressEffects) { chip.applyExpPress() }
                            if (!wasVisible) ExpMotion.popIn(chip)
                        }
                        chip.setOnClickListener {
                            ExpMotion.hapticTap(it)
                            if (filterAll) {
                                findNavController().navigate(R.id.home)
                            } else {
                                viewModel.setFilter(DownloadsFilter.ALL)
                            }
                        }
                    }
                } else {
                    if (ExperimentalMobileDesign.enabled() && binding.tvDownloadsEmpty.isVisible) {
                        ExpMotion.fadeOutAndHide(binding.tvDownloadsEmpty)
                        binding.root.findViewById<View>(R.id.v_downloads_empty_rule)
                            ?.let { ExpMotion.fadeOutAndHide(it) }
                        binding.root.findViewById<View>(R.id.btn_downloads_empty_cta)
                            ?.let { ExpMotion.fadeOutAndHide(it) }
                    } else {
                        binding.tvDownloadsEmpty.isVisible = false
                        binding.root.findViewById<View>(R.id.v_downloads_empty_rule)?.isVisible = false
                        binding.root.findViewById<View>(R.id.btn_downloads_empty_cta)?.isVisible = false
                    }
                }
                binding.tvDownloadsEmpty.setText(
                    if (filter == DownloadsFilter.ALL) {
                        R.string.downloads_empty
                    } else {
                        R.string.downloads_filter_empty
                    },
                )
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedFilter.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect {
                styleFilters(it)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.storageLabel.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect {
                val storage = binding.tvDownloadsStorage
                val wasBlank = storage.text.isNullOrBlank()
                storage.text = it
                if (ExperimentalMobileDesign.enabled() && wasBlank && !it.isNullOrBlank()) {
                    storage.setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                    val padH = (10 * resources.displayMetrics.density).toInt()
                    val padV = (4 * resources.displayMetrics.density).toInt()
                    storage.setPadding(padH, padV, padH, padV)
                    ExpMotion.popIn(storage)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.lowSpace.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { low ->
                updateBanner(low, viewModel.wifiPaused.value)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.wifiPaused.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { paused ->
                updateBanner(viewModel.lowSpace.value, paused)
            }
        }
        viewModel.refreshStorage()
        styleFilters(viewModel.currentFilter())
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshWifiPreference()
        viewModel.startLiveProgress()
    }

    override fun onPause() {
        viewModel.stopLiveProgress()
        super.onPause()
    }

    private fun styleFilters(selected: DownloadsFilter) {
        styleChip(binding.chipFilterAll, selected == DownloadsFilter.ALL)
        styleChip(binding.chipFilterDownloading, selected == DownloadsFilter.DOWNLOADING)
        styleChip(binding.chipFilterCompleted, selected == DownloadsFilter.COMPLETED)
        styleChip(binding.chipFilterFailed, selected == DownloadsFilter.FAILED)
    }

    private fun styleChip(chip: android.widget.TextView, selected: Boolean) {
        val wasSelected = chip.isSelected
        chip.isSelected = selected
        val exp = ExperimentalMobileDesign.enabled()
        chip.setBackgroundResource(
            when {
                exp && selected -> ExperimentalMobileDesign.primaryButtonBackground()
                exp -> ExperimentalMobileDesign.chipBackground()
                selected -> R.drawable.bg_download_filter_chip_selected
                else -> R.drawable.bg_download_filter_chip
            },
        )
        chip.setTextColor(
            when {
                exp && selected -> MaterialColors.getColor(
                    chip, com.google.android.material.R.attr.colorOnPrimary,
                )
                exp -> MaterialColors.getColor(
                    chip, com.google.android.material.R.attr.colorOnSurfaceVariant,
                )
                selected -> 0xFF111111.toInt()
                else -> 0xFFFFFFFF.toInt()
            },
        )
        chip.refreshDrawableState()
        if (exp && selected && !wasSelected) {
            ExpMotion.popIn(chip)
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_downloads_rule))
        }
        if (exp) {
            chip.typeface = if (selected) {
                android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            } else {
                android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            }
            chip.letterSpacing = if (selected) 0.02f else 0f
        }
    }

    private fun updateBanner(lowSpace: Boolean, wifiPaused: Boolean) {
        val banner = binding.tvDownloadsBanner
        val wasVisible = banner.isVisible
        when {
            wifiPaused -> {
                banner.setText(R.string.downloads_wifi_paused)
                if (ExperimentalMobileDesign.enabled()) {
                    banner.setTextColor(
                        com.google.android.material.color.MaterialColors.getColor(
                            banner, com.google.android.material.R.attr.colorTertiary,
                        ),
                    )
                }
            }
            lowSpace -> {
                banner.setText(R.string.downloads_low_space)
                if (ExperimentalMobileDesign.enabled()) {
                    banner.setTextColor(
                        com.google.android.material.color.MaterialColors.getColor(
                            banner, androidx.appcompat.R.attr.colorError,
                        ),
                    )
                }
            }
            else -> {
                if (ExperimentalMobileDesign.enabled() && wasVisible) {
                    ExpMotion.fadeOutAndHide(banner)
                } else {
                    banner.isVisible = false
                }
                return
            }
        }
        banner.isVisible = true
        if (ExperimentalMobileDesign.enabled()) {
            banner.setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
            val density = resources.displayMetrics.density
            banner.setPadding(
                (14 * density).toInt(),
                (10 * density).toInt(),
                (14 * density).toInt(),
                (10 * density).toInt(),
            )
            with(com.dskja.betterstreamflix.utils.ExpPressEffects) { banner.applyExpPress() }
            if (!wasVisible) {
                ExpMotion.popIn(banner)
            }
        }
    }

    private fun showMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, R.string.downloads_action_pause_all)
            menu.add(0, 2, 1, R.string.downloads_action_resume_all)
            menu.add(0, 6, 2, R.string.downloads_action_retry_all_failed)
            menu.add(0, 3, 3, R.string.downloads_action_clear_completed)
            menu.add(0, 7, 4, R.string.downloads_action_clear_watched)
            menu.add(0, 4, 5, R.string.downloads_action_clear_failed)
            val sortMenu = menu.addSubMenu(0, 8, 6, R.string.downloads_sort)
            sortMenu.add(0, 10, 0, R.string.downloads_sort_newest)
                .setCheckable(true)
                .setChecked(viewModel.currentSort() == DownloadsSort.NEWEST)
            sortMenu.add(0, 11, 1, R.string.downloads_sort_title)
                .setCheckable(true)
                .setChecked(viewModel.currentSort() == DownloadsSort.TITLE)
            sortMenu.add(0, 12, 2, R.string.downloads_sort_size)
                .setCheckable(true)
                .setChecked(viewModel.currentSort() == DownloadsSort.SIZE)
            sortMenu.setGroupCheckable(0, true, true)
            menu.add(0, 5, 7, R.string.downloads_action_settings)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> viewModel.pauseAll()
                    2 -> viewModel.resumeAll()
                    3 -> confirmDestructive(R.string.settings_download_clear_completed_confirm) {
                        viewModel.clearCompleted()
                    }
                    4 -> confirmDestructive(R.string.settings_download_clear_failed_confirm) {
                        viewModel.clearFailed()
                    }
                    5 -> {
                        SettingsDeepLink.openDownloadsScreen()
                        findNavController().navigate(R.id.settings)
                    }
                    6 -> viewModel.retryAllFailed()
                    7 -> confirmDestructive(R.string.downloads_action_clear_watched_confirm) {
                        viewModel.clearWatched()
                    }
                    10 -> viewModel.setSort(DownloadsSort.NEWEST)
                    11 -> viewModel.setSort(DownloadsSort.TITLE)
                    12 -> viewModel.setSort(DownloadsSort.SIZE)
                }
                true
            }
            show()
        }
    }

    private fun confirmDestructive(messageRes: Int, onConfirm: () -> Unit) {
        val builder = if (ExperimentalMobileDesign.enabled()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
        } else {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
        }
        val glass = if (ExperimentalMobileDesign.enabled()) {
            com.dskja.betterstreamflix.utils.ExpDialogChrome.buildGlassMessage(
                requireContext(),
                getString(messageRes),
            )
        } else {
            null
        }
        if (glass != null) builder.setView(glass.root)
        else builder.setMessage(messageRes)
        builder
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    if (glass != null) {
                        com.dskja.betterstreamflix.utils.ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                    } else {
                        com.dskja.betterstreamflix.utils.ExpDialogChrome.polishButtons(dialog)
                    }
                }
                dialog.show()
            }
    }

    private fun showItemMenu(row: DownloadRowUiModel.Item, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            if (row.state == DownloadItemState.COMPLETED) {
                menu.add(0, 1, 0, R.string.downloads_action_share)
            }
            if (row.state == DownloadItemState.FAILED) {
                menu.add(0, 2, 1, R.string.downloads_action_retry)
            }
            menu.add(0, 3, 2, R.string.downloads_action_delete)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> share(row)
                    2 -> retry(row)
                    3 -> viewModel.remove(row.id)
                }
                true
            }
            show()
        }
    }

    private fun showPackMenu(pack: DownloadRowUiModel.SeasonPack, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, R.string.downloads_pack_pause)
            menu.add(0, 2, 1, R.string.downloads_pack_resume)
            menu.add(0, 3, 2, R.string.downloads_pack_delete)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> viewModel.pausePack(pack.pack.id)
                    2 -> viewModel.resumePack(pack.pack.id)
                    3 -> viewModel.removePack(pack.pack.id)
                }
                true
            }
            show()
        }
    }

    private fun share(row: DownloadRowUiModel.Item) {
        viewLifecycleOwner.lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                OfflinePlayback.exportShareUri(requireContext(), row.entity)
            }
            if (uri == null) {
                // Cache-only Media3 downloads have no on-disk file to share.
                showDownloadError(R.string.downloads_share_cache_only)
                return@launch
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                startActivity(android.content.Intent.createChooser(intent, row.entity.title))
            }
        }
    }

    private fun playOffline(row: DownloadRowUiModel.Item) {
        viewLifecycleOwner.lifecycleScope.launch {
            val videoType = DownloadController.deserializeVideoType(row.entity.videoTypeJson)
            if (videoType == null) {
                showDownloadError(R.string.downloads_failed_generic)
                return@launch
            }
            activateDownloadProvider(row.entity.providerName)
            val local = withContext(Dispatchers.IO) {
                OfflinePlayback.buildLocalVideo(requireContext(), row.entity)
            }
            if (local == null) {
                showDownloadError(R.string.download_error_file_missing)
                return@launch
            }
            OfflineVideoCache.put(row.entity.contentKey, local)
            findNavController().navigate(
                R.id.action_global_player,
                bundleOf(
                    "id" to when (videoType) {
                        is Video.Type.Movie -> videoType.id
                        is Video.Type.Episode -> videoType.id
                    },
                    "title" to row.entity.title,
                    "subtitle" to row.entity.subtitle,
                    "videoType" to videoType,
                    "preferredServerName" to OFFLINE_SERVER,
                ),
            )
        }
    }

    private fun showDownloadError(messageRes: Int) {
        val message = getString(messageRes)
        if (ExperimentalMobileDesign.enabled()) {
            ExpDialogChrome.showInfo(
                requireContext(),
                R.string.downloads_title,
                message,
            ) { ctx -> MaterialAlertDialogBuilder(ctx) }
        } else {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun retry(row: DownloadRowUiModel.Item) {
        viewLifecycleOwner.lifecycleScope.launch {
            val videoType = DownloadController.deserializeVideoType(row.entity.videoTypeJson) ?: return@launch
            activateDownloadProvider(row.entity.providerName)
            viewModel.remove(row.id)
            when (videoType) {
                is Video.Type.Movie -> {
                    val movie = com.dskja.betterstreamflix.models.Movie(
                        id = videoType.id,
                        title = videoType.title,
                        poster = videoType.poster,
                        providerName = row.entity.providerName,
                    )
                    DownloadOptionsController.enqueueMovie(this@DownloadsMobileFragment, movie)
                }
                is Video.Type.Episode -> {
                    val episode = com.dskja.betterstreamflix.models.Episode(
                        id = videoType.id,
                        number = videoType.number,
                        title = videoType.title,
                        poster = videoType.poster,
                        overview = videoType.overview,
                        tvShow = com.dskja.betterstreamflix.models.TvShow(
                            id = videoType.tvShow.id,
                            title = videoType.tvShow.title,
                            poster = videoType.tvShow.poster,
                            providerName = row.entity.providerName,
                        ),
                        season = com.dskja.betterstreamflix.models.Season(
                            id = "",
                            number = videoType.season.number,
                            title = videoType.season.title,
                        ),
                    )
                    DownloadOptionsController.enqueueEpisode(this@DownloadsMobileFragment, episode)
                }
            }
        }
    }

    /** Prefer the download's stamped provider so autoplay/retry resolve the right catalog. */
    private fun activateDownloadProvider(providerName: String?) {
        val name = providerName?.takeIf { it.isNotBlank() } ?: return
        Provider.findByName(name)?.let { UserPreferences.setCurrentProviderForPlayback(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val OFFLINE_SERVER = "__offline__"
    }
}

/**
 * In-memory shortcut for resolved local [Video]s after Room confirms a COMPLETED download.
 *
 * [put] is optional (Downloads UI / PlayerViewModel may warm the cache).
 * [take] is a consume-once helper used only after validation in [com.dskja.betterstreamflix.fragments.player.PlayerViewModel]
 * resolve — do not use it as the primary offline resolution path.
 */
object OfflineVideoCache {
    private val map = mutableMapOf<String, Video>()
    fun put(key: String, video: Video) {
        map[key] = video
    }
    /** Consume a cached video once. Prefer Room + [com.dskja.betterstreamflix.download.OfflinePlayback] for truth. */
    fun take(key: String): Video? = map.remove(key)
    fun get(key: String): Video? = map[key]
    fun remove(key: String) {
        map.remove(key)
    }
    fun clear() {
        map.clear()
    }
}
