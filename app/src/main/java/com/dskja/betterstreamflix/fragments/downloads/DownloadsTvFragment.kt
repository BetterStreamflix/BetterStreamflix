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
import com.dskja.betterstreamflix.databinding.FragmentDownloadsTvBinding
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
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.viewModelsFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadsTvFragment : Fragment() {
    private var _binding: FragmentDownloadsTvBinding? = null
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
        _binding = FragmentDownloadsTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.rvDownloads.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDownloads.itemAnimator = null
        binding.rvDownloads.adapter = adapter
        binding.btnDownloadsMenu.setOnClickListener { showMenu(it) }
        binding.chipFilterAll.setOnClickListener { viewModel.setFilter(DownloadsFilter.ALL) }
        binding.chipFilterDownloading.setOnClickListener { viewModel.setFilter(DownloadsFilter.DOWNLOADING) }
        binding.chipFilterCompleted.setOnClickListener { viewModel.setFilter(DownloadsFilter.COMPLETED) }
        binding.chipFilterFailed.setOnClickListener { viewModel.setFilter(DownloadsFilter.FAILED) }

        if (ExperimentalMobileDesign.enabled()) {
            ExpMotion.enterScreen(binding.root)
            val onSurface = com.google.android.material.color.MaterialColors.getColor(
                binding.tvDownloadsTitle,
                com.google.android.material.R.attr.colorOnSurface,
            )
            val onVariant = com.google.android.material.color.MaterialColors.getColor(
                binding.tvDownloadsStorage,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
            val error = com.google.android.material.color.MaterialColors.getColor(
                binding.tvDownloadsBanner,
                androidx.appcompat.R.attr.colorError,
            )
            binding.tvDownloadsTitle.setTextColor(onSurface)
            binding.tvDownloadsStorage.setTextColor(onVariant)
            binding.tvDownloadsBanner.setTextColor(error)
            binding.btnDownloadsMenu.imageTintList =
                android.content.res.ColorStateList.valueOf(onSurface)
            binding.btnDownloadsMenu.setBackgroundResource(
                ExperimentalMobileDesign.iconChipBackground(),
            )
            with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                binding.btnDownloadsMenu.applyExpPress()
            }
            ExpMotion.revealHeader(binding.tvDownloadsTitle, binding.tvDownloadsStorage)
            binding.root.findViewById<View>(R.id.v_downloads_filters_edge_fade_start)?.isVisible = true
            binding.root.findViewById<View>(R.id.v_downloads_filters_edge_fade_end)?.isVisible = true
        } else {
            binding.root.findViewById<View>(R.id.v_downloads_filters_edge_fade_start)?.isVisible = false
            binding.root.findViewById<View>(R.id.v_downloads_filters_edge_fade_end)?.isVisible = false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Combine filter so empty→empty filter switches still refresh CTA copy
            // (StateFlow skips equal emptyList emissions).
            combine(viewModel.rows, viewModel.selectedFilter) { rows, filter -> rows to filter }
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { (rows, filter) ->
                adapter.submitList(rows.toList())
                val empty = rows.isEmpty()
                binding.tvDownloadsEmpty.isVisible = empty
                binding.tvDownloadsEmpty.setText(
                    if (filter == DownloadsFilter.ALL) {
                        R.string.downloads_empty
                    } else {
                        R.string.downloads_filter_empty
                    },
                )
                val emptyCta = binding.root.findViewById<android.widget.TextView>(
                    R.id.btn_downloads_empty_cta,
                )
                val filterAll = filter == DownloadsFilter.ALL
                if (empty && ExperimentalMobileDesign.enabled()) {
                    emptyCta?.setText(
                        if (filterAll) R.string.exp_empty_browse_catalog
                        else R.string.exp_empty_clear_filter,
                    )
                }
                ExpEmptyChrome.bind(
                    emptyView = binding.tvDownloadsEmpty,
                    emptyRule = binding.root.findViewById(R.id.v_downloads_empty_rule),
                    emptyCta = emptyCta,
                    visible = empty,
                    tintOnSurfaceVariant = false,
                    onCtaClick = {
                        if (filterAll) {
                            runCatching { findNavController().navigate(R.id.home) }
                        } else {
                            viewModel.setFilter(DownloadsFilter.ALL)
                        }
                    },
                )
                if (!empty) {
                    binding.tvDownloadsEmpty.setTag(R.id.exp_enter_animated_tag, null)
                }
                if (empty && binding.rvDownloads.hasFocus()) {
                    emptyCta?.requestFocus() ?: binding.chipFilterAll.requestFocus()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedFilter.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect {
                styleFilters(it)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.storageLabel.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect {
                binding.tvDownloadsStorage.text = it
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.wifiPaused.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { paused ->
                binding.tvDownloadsBanner.isVisible = paused || viewModel.lowSpace.value
                binding.tvDownloadsBanner.setText(
                    if (paused) R.string.downloads_wifi_paused else R.string.downloads_low_space,
                )
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
                exp && selected -> com.google.android.material.color.MaterialColors.getColor(
                    chip, com.google.android.material.R.attr.colorOnPrimary,
                )
                exp -> com.google.android.material.color.MaterialColors.getColor(
                    chip, com.google.android.material.R.attr.colorOnSurfaceVariant,
                )
                selected -> 0xFF111111.toInt()
                else -> 0xFFFFFFFF.toInt()
            },
        )
        chip.refreshDrawableState()
        if (exp && selected && !wasSelected) {
            com.dskja.betterstreamflix.utils.ExpMotion.popIn(chip)
        }
        if (exp) {
            with(com.dskja.betterstreamflix.utils.ExpPressEffects) { chip.applyExpPress() }
            chip.typeface = if (selected) {
                android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            } else {
                android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            }
            chip.letterSpacing = if (selected) 0.02f else 0f
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
                    3 -> viewModel.clearCompleted()
                    4 -> viewModel.clearFailed()
                    5 -> {
                        SettingsDeepLink.openDownloadsScreen()
                        findNavController().navigate(R.id.settings)
                    }
                    6 -> viewModel.retryAllFailed()
                    7 -> viewModel.clearWatched()
                    10 -> viewModel.setSort(DownloadsSort.NEWEST)
                    11 -> viewModel.setSort(DownloadsSort.TITLE)
                    12 -> viewModel.setSort(DownloadsSort.SIZE)
                }
                true
            }
            show()
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
            } ?: run {
                showDownloadError(R.string.download_error_file_missing)
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
            val videoType = DownloadController.deserializeVideoType(row.entity.videoTypeJson) ?: return@launch
            activateDownloadProvider(row.entity.providerName)
            val local = withContext(Dispatchers.IO) {
                OfflinePlayback.buildLocalVideo(requireContext(), row.entity)
            } ?: run {
                showDownloadError(R.string.download_error_file_missing)
                return@launch
            }
            OfflineVideoCache.put(row.entity.contentKey, local)
            findNavController().navigate(
                R.id.action_global_player,
                bundleOf(
                    "id" to videoType.let {
                        when (it) {
                            is Video.Type.Movie -> it.id
                            is Video.Type.Episode -> it.id
                        }
                    },
                    "title" to row.entity.title,
                    "subtitle" to row.entity.subtitle,
                    "videoType" to videoType,
                    "preferredServerName" to DownloadsMobileFragment.OFFLINE_SERVER,
                ),
            )
        }
    }

    private fun retry(row: DownloadRowUiModel.Item) {
        viewLifecycleOwner.lifecycleScope.launch {
            val videoType = DownloadController.deserializeVideoType(row.entity.videoTypeJson) ?: return@launch
            activateDownloadProvider(row.entity.providerName)
            viewModel.remove(row.id)
            when (videoType) {
                is Video.Type.Movie -> DownloadOptionsController.enqueueMovie(
                    this@DownloadsTvFragment,
                    Movie(
                        id = videoType.id,
                        title = videoType.title,
                        poster = videoType.poster,
                        providerName = row.entity.providerName,
                    ),
                )
                is Video.Type.Episode -> DownloadOptionsController.enqueueEpisode(
                    this@DownloadsTvFragment,
                    Episode(
                        id = videoType.id,
                        number = videoType.number,
                        title = videoType.title,
                        poster = videoType.poster,
                        overview = videoType.overview,
                        tvShow = TvShow(
                            id = videoType.tvShow.id,
                            title = videoType.tvShow.title,
                            poster = videoType.tvShow.poster,
                            providerName = row.entity.providerName,
                        ),
                        season = Season(
                            id = "",
                            number = videoType.season.number,
                            title = videoType.season.title,
                        ),
                    ),
                )
            }
        }
    }

    private fun activateDownloadProvider(providerName: String?) {
        val name = providerName?.takeIf { it.isNotBlank() } ?: return
        Provider.findByName(name)?.let { UserPreferences.currentProvider = it }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
