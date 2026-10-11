package com.dskja.betterstreamflix.fragments.downloads

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.databinding.FragmentDownloadsTvBinding
import com.dskja.betterstreamflix.download.DownloadController
import com.dskja.betterstreamflix.download.DownloadItemEntity
import com.dskja.betterstreamflix.download.DownloadItemState
import com.dskja.betterstreamflix.download.DownloadKind
import com.dskja.betterstreamflix.download.ExternalDownloadHandoff
import com.dskja.betterstreamflix.download.OfflineExpiryNudge
import com.dskja.betterstreamflix.download.OfflineLibrary
import com.dskja.betterstreamflix.download.OfflinePlayback
import com.dskja.betterstreamflix.download.ui.DownloadOptionsController
import com.dskja.betterstreamflix.download.ui.DownloadRowUiModel
import com.dskja.betterstreamflix.download.ui.DownloadsAdapter
import com.dskja.betterstreamflix.download.ui.DownloadsFilter
import com.dskja.betterstreamflix.download.ui.DownloadsSort
import com.dskja.betterstreamflix.download.ui.DownloadsViewMode
import com.dskja.betterstreamflix.download.ui.DownloadsViewModel
import com.dskja.betterstreamflix.download.ui.OfflineLibraryActions
import com.dskja.betterstreamflix.fragments.settings.SettingsDeepLink
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.platform.playerbackend.ExternalStreamHandoff
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.TvFocusChain
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.hideKeyboard
import com.dskja.betterstreamflix.utils.viewModelsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadsTvFragment : Fragment() {
    private var _binding: FragmentDownloadsTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModelsFactory {
        DownloadsViewModel(requireContext().applicationContext)
    }

    private var libraryEntries: List<OfflineLibrary.Entry> = emptyList()
    private var librarySpacing: RecyclerView.ItemDecoration? = null
    private val libraryAdapter = AppAdapter().apply {
        onMovieClickListener = { movie ->
            val entity = OfflineLibrary.findMovieEntity(libraryEntries, movie.id)
            if (entity != null) playOfflineEntity(entity)
        }
        onTvShowClickListener = { show ->
            val series = OfflineLibrary.findSeries(libraryEntries, show.id)
            if (series != null) {
                OfflineLibraryActions.showSeriesEpisodePicker(requireContext(), series) {
                    playOfflineEntity(it)
                }
            }
        }
    }

    private val adapter = DownloadsAdapter(
        onPlay = { playOffline(it) },
        onPauseResume = { row ->
            if (row.state == DownloadItemState.PAUSED) viewModel.resume(row.id)
            else viewModel.pause(row.id)
        },
        onRetry = { retry(it) },
        onDelete = { row ->
            confirmDestructive(R.string.downloads_delete_confirm) {
                viewModel.remove(row.id)
            }
        },
        onItemMore = { row, anchor -> showItemMenu(row, anchor) },
        onPackMore = { pack, anchor -> showPackMenu(pack, anchor) },
        onPackPause = { viewModel.pausePack(it.pack.id) },
        onPackResume = { viewModel.resumePack(it.pack.id) },
        onPackDelete = { pack ->
            confirmDestructive(R.string.downloads_delete_confirm) {
                viewModel.removePack(pack.pack.id)
            }
        },
        onSeriesToggle = { viewModel.toggleSeriesFolder(it.seriesKey) },
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
        binding.rvDownloads.itemAnimator = null
        wireDownloadFocus()
        binding.btnDownloadsMenu.setOnClickListener { showMenu(it) }
        binding.chipModeLibrary.setOnClickListener { viewModel.setViewMode(DownloadsViewMode.LIBRARY) }
        binding.chipModeManage.setOnClickListener { viewModel.setViewMode(DownloadsViewMode.MANAGE) }
        wireOfflineLibrarySearch()
        binding.chipFilterAll.setOnClickListener { viewModel.setFilter(DownloadsFilter.ALL) }
        binding.chipFilterDownloading.setOnClickListener { viewModel.setFilter(DownloadsFilter.DOWNLOADING) }
        binding.chipFilterCompleted.setOnClickListener { viewModel.setFilter(DownloadsFilter.COMPLETED) }
        binding.chipFilterFailed.setOnClickListener { viewModel.setFilter(DownloadsFilter.FAILED) }

        binding.root.findViewById<View>(R.id.v_downloads_filters_edge_fade_start)?.isVisible = false
        binding.root.findViewById<View>(R.id.v_downloads_filters_edge_fade_end)?.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedViewMode
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { applyViewMode(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            combine(viewModel.libraryEntries, viewModel.librarySearchQuery) { entries, query ->
                entries to query
            }
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { (entries, query) ->
                    libraryEntries = entries
                    if (viewModel.currentViewMode() != DownloadsViewMode.LIBRARY) return@collect
                    libraryAdapter.submitList(OfflineLibrary.toAppItems(entries, mobile = false))
                    renderTvEmpty(
                        empty = entries.isEmpty(),
                        libraryMode = true,
                        searchActive = query.isNotBlank(),
                        manageFilter = viewModel.currentFilter(),
                    )
                }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // Combine filter so empty→empty filter switches still refresh CTA copy
            // (StateFlow skips equal emptyList emissions).
            combine(viewModel.rows, viewModel.selectedFilter) { rows, filter -> rows to filter }
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { (rows, filter) ->
                if (viewModel.currentViewMode() != DownloadsViewMode.MANAGE) return@collect
                adapter.submitList(rows.toList())
                renderTvEmpty(
                    empty = rows.isEmpty(),
                    libraryMode = false,
                    searchActive = false,
                    manageFilter = filter,
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
                binding.tvDownloadsStorage.text = it
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.lowSpace.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { low ->
                updateBanner(low, viewModel.wifiPaused.value, viewModel.healthSummary.value)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.wifiPaused.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { paused ->
                updateBanner(viewModel.lowSpace.value, paused, viewModel.healthSummary.value)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.healthSummary.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { health ->
                updateBanner(viewModel.lowSpace.value, viewModel.wifiPaused.value, health)
            }
        }
        binding.btnDownloadsHealthRedownload.setOnClickListener {
            viewModel.redownloadAllUnhealthy()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.wifiOnlyEnabled.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { enabled ->
                val sw = binding.switchDownloadsWifiOnly
                if (sw.isChecked != enabled) {
                    sw.isChecked = enabled
                }
            }
        }
        binding.switchDownloadsWifiOnly.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setWifiOnly(isChecked)
        }
        viewModel.refreshStorage()
        styleFilters(viewModel.currentFilter())
        applyViewMode(viewModel.currentViewMode())
    }

    private fun applyViewMode(mode: DownloadsViewMode) {
        val library = mode == DownloadsViewMode.LIBRARY
        binding.tvDownloadsTitle.setText(
            if (library) R.string.offline_library_title else R.string.downloads_title,
        )
        binding.llOfflineLibraryBadge.isVisible = library
        binding.clOfflineLibrarySearch.isVisible = library
        binding.hsvDownloadsFilters.isVisible = !library
        styleChip(binding.chipModeLibrary, library)
        styleChip(binding.chipModeManage, !library)
        librarySpacing?.let { binding.rvDownloads.removeItemDecoration(it) }
        librarySpacing = null
        if (library) {
            val libraryCols =
                com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.downloadsLibraryColumns()
            binding.rvDownloads.layoutManager = object : GridLayoutManager(requireContext(), libraryCols) {
                override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
                    if (direction == View.FOCUS_LEFT) {
                        val pos = binding.rvDownloads.getChildAdapterPosition(
                            binding.rvDownloads.findContainingItemView(focused) ?: return@onInterceptFocusSearch null,
                        )
                        if (pos >= 0 && pos % spanCount == 0) {
                            return activity?.findViewById(R.id.nav_main)
                        }
                    }
                    if (direction == View.FOCUS_UP) {
                        val pos = binding.rvDownloads.getChildAdapterPosition(
                            binding.rvDownloads.findContainingItemView(focused) ?: return@onInterceptFocusSearch null,
                        )
                        if (pos in 0 until spanCount) return binding.etOfflineLibrarySearch
                    }
                    return super.onInterceptFocusSearch(focused, direction)
                }
            }
            binding.rvDownloads.adapter = libraryAdapter
            librarySpacing = SpacingItemDecoration(12.dp(requireContext())).also {
                binding.rvDownloads.addItemDecoration(it)
            }
            libraryAdapter.submitList(OfflineLibrary.toAppItems(libraryEntries, mobile = false))
            renderTvEmpty(
                empty = libraryEntries.isEmpty(),
                libraryMode = true,
                searchActive = viewModel.librarySearchQuery.value.isNotBlank(),
                manageFilter = viewModel.currentFilter(),
            )
        } else {
            binding.rvDownloads.layoutManager = object : LinearLayoutManager(requireContext()) {
                override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
                    if (direction == View.FOCUS_LEFT) {
                        val row = binding.rvDownloads.findContainingItemView(focused)
                        val primary = row?.findViewById<View>(R.id.btn_download_primary)
                        val delete = row?.findViewById<View>(R.id.btn_download_delete)
                        val atLeftEdge = primary == null || focused == primary || focused == row
                        if (atLeftEdge && focused != delete) {
                            return activity?.findViewById(R.id.nav_main)
                        }
                    }
                    if (direction == View.FOCUS_UP) {
                        val row = binding.rvDownloads.findContainingItemView(focused)
                        val position = row?.let(binding.rvDownloads::getChildAdapterPosition)
                            ?: RecyclerView.NO_POSITION
                        if (position == 0) return binding.chipFilterAll
                    }
                    return super.onInterceptFocusSearch(focused, direction)
                }
            }
            binding.rvDownloads.adapter = adapter
            renderTvEmpty(
                empty = adapter.itemCount == 0,
                libraryMode = false,
                searchActive = false,
                manageFilter = viewModel.currentFilter(),
            )
        }
        wireDownloadFocus()
    }

    private fun wireOfflineLibrarySearch() {
        val search = binding.etOfflineLibrarySearch
        val clear = binding.btnOfflineLibrarySearchClear
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                clear.isVisible = q.isNotBlank()
                viewModel.setLibraryQuery(q)
            }
        })
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        clear.setOnClickListener {
            search.setText("")
            hideKeyboard()
        }
    }

    private fun renderTvEmpty(
        empty: Boolean,
        libraryMode: Boolean,
        searchActive: Boolean,
        manageFilter: DownloadsFilter,
    ) {
        binding.tvDownloadsEmpty.setText(
            when {
                libraryMode && searchActive -> R.string.offline_library_empty_search
                libraryMode -> R.string.offline_library_empty
                manageFilter == DownloadsFilter.ALL -> R.string.downloads_empty
                else -> R.string.downloads_filter_empty
            },
        )
        val emptyCta = binding.root.findViewById<android.widget.TextView>(
            R.id.btn_downloads_empty_cta,
        )
        ExpEmptyChrome.bind(
            emptyView = binding.tvDownloadsEmpty,
            emptyRule = binding.root.findViewById(R.id.v_downloads_empty_rule),
            emptyCta = emptyCta,
            visible = empty,
            tintOnSurfaceVariant = false,
            onCtaClick = {
                when {
                    libraryMode && searchActive -> {
                        binding.etOfflineLibrarySearch.setText("")
                        viewModel.setLibraryQuery("")
                    }
                    libraryMode || manageFilter == DownloadsFilter.ALL ->
                        runCatching { findNavController().navigate(R.id.home) }
                    else -> viewModel.setFilter(DownloadsFilter.ALL)
                }
            },
        )
        if (!empty) {
            binding.tvDownloadsEmpty.setTag(R.id.exp_enter_animated_tag, null)
        }
        val downId = if (empty) {
            emptyCta?.id ?: binding.chipModeLibrary.id
        } else {
            binding.rvDownloads.id
        }
        listOf(
            binding.chipModeLibrary,
            binding.chipModeManage,
            binding.chipFilterAll,
            binding.chipFilterDownloading,
            binding.chipFilterCompleted,
            binding.chipFilterFailed,
        ).forEach { chip -> chip.nextFocusDownId = downId }
        binding.etOfflineLibrarySearch.nextFocusDownId = downId
        if (empty && binding.rvDownloads.hasFocus()) {
            emptyCta?.requestFocus() ?: binding.chipModeLibrary.requestFocus()
        }
    }

    private fun wireDownloadFocus() {
        binding.hsvDownloadsFilters.isFocusable = false
        TvFocusChain.linkHorizontal(binding.chipModeLibrary, binding.chipModeManage)
        TvFocusChain.linkHorizontal(
            binding.chipFilterAll,
            binding.chipFilterDownloading,
            binding.chipFilterCompleted,
            binding.chipFilterFailed,
        )
        binding.chipModeLibrary.nextFocusLeftId = R.id.nav_main
        binding.chipFilterAll.nextFocusLeftId = R.id.nav_main
        listOf(binding.chipModeLibrary, binding.chipModeManage).forEach { chip ->
            chip.nextFocusUpId = binding.switchDownloadsWifiOnly.id
            chip.nextFocusDownId = if (viewModel.currentViewMode() == DownloadsViewMode.LIBRARY) {
                binding.etOfflineLibrarySearch.id
            } else {
                binding.chipFilterAll.id
            }
        }
        listOf(
            binding.chipFilterAll,
            binding.chipFilterDownloading,
            binding.chipFilterCompleted,
            binding.chipFilterFailed,
        ).forEach { chip ->
            chip.nextFocusUpId = binding.chipModeLibrary.id
            chip.nextFocusDownId = binding.rvDownloads.id
        }
        binding.etOfflineLibrarySearch.nextFocusUpId = binding.chipModeLibrary.id
        binding.etOfflineLibrarySearch.nextFocusLeftId = R.id.nav_main
        binding.btnDownloadsMenu.nextFocusDownId = binding.switchDownloadsWifiOnly.id
        binding.btnDownloadsMenu.nextFocusLeftId = binding.chipModeManage.id
        binding.switchDownloadsWifiOnly.nextFocusUpId = binding.btnDownloadsMenu.id
        binding.switchDownloadsWifiOnly.nextFocusLeftId = R.id.nav_main
        val healthCta = binding.btnDownloadsHealthRedownload
        if (healthCta.isVisible) {
            binding.switchDownloadsWifiOnly.nextFocusDownId = healthCta.id
            healthCta.nextFocusUpId = binding.switchDownloadsWifiOnly.id
            healthCta.nextFocusDownId = binding.chipModeLibrary.id
            healthCta.nextFocusLeftId = R.id.nav_main
            listOf(binding.chipModeLibrary, binding.chipModeManage).forEach { chip ->
                chip.nextFocusUpId = healthCta.id
            }
        } else {
            binding.switchDownloadsWifiOnly.nextFocusDownId = binding.chipModeLibrary.id
            listOf(binding.chipModeLibrary, binding.chipModeManage).forEach { chip ->
                chip.nextFocusUpId = binding.switchDownloadsWifiOnly.id
            }
        }
    }

    private fun updateBanner(
        lowSpace: Boolean,
        wifiPaused: Boolean,
        health: com.dskja.betterstreamflix.download.DownloadHealth.Summary,
    ) {
        val row = binding.llDownloadsBanner
        val cta = binding.btnDownloadsHealthRedownload
        when {
            wifiPaused -> {
                row.isVisible = true
                binding.tvDownloadsBanner.setText(R.string.downloads_wifi_paused)
                cta.isVisible = false
            }
            lowSpace -> {
                row.isVisible = true
                binding.tvDownloadsBanner.setText(R.string.downloads_low_space)
                cta.isVisible = false
            }
            health.hasUnhealthy -> {
                row.isVisible = true
                binding.tvDownloadsBanner.text =
                    com.dskja.betterstreamflix.download.DownloadHealth
                        .bannerMessage(requireContext(), health)
                cta.isVisible = true
            }
            else -> {
                cta.isVisible = false
                row.isVisible = false
            }
        }
        wireDownloadFocus()
    }

    override fun onResume() {
        super.onResume()
        viewModel.ensureProfileScope()
        viewModel.refreshWifiPreference()
        viewModel.startLiveProgress()
        maybePromptOfflineExpiry()
    }

    private fun maybePromptOfflineExpiry() {
        viewLifecycleOwner.lifecycleScope.launch {
            delay(450L)
            if (!isAdded || view == null) return@launch
            if (viewModel.wifiPaused.value || viewModel.lowSpace.value) return@launch
            val stale = viewModel.loadOfflineExpiryCandidates()
            if (stale.isEmpty() || !isAdded) return@launch
            OfflineExpiryNudge.maybeShow(
                fragment = this@DownloadsTvFragment,
                stale = stale,
                onKeep = { items -> viewModel.touchDownloads(items.map { it.id }) },
                onDelete = { items -> viewModel.removeDownloads(items.map { it.id }) },
            )
        }
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
        chip.isSelected = selected
        chip.setBackgroundResource(
            if (selected) R.drawable.bg_download_filter_chip_selected
            else R.drawable.bg_download_filter_chip,
        )
        chip.setTextColor(
            if (selected) 0xFF111111.toInt() else 0xFFFFFFFF.toInt(),
        )
        chip.isFocusable = true
        chip.isFocusableInTouchMode = true
        chip.refreshDrawableState()
        chip.typeface = if (selected) {
            android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        } else {
            android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
        chip.setOnFocusChangeListener { v, hasFocus ->
            v.scaleX = if (hasFocus) 1.06f else 1f
            v.scaleY = if (hasFocus) 1.06f else 1f
            v.refreshDrawableState()
        }
    }

    private fun showMenu(anchor: View) {
        val labels = mutableListOf<Pair<Int, Int>>()
        labels += 1 to R.string.downloads_action_pause_all
        labels += 2 to R.string.downloads_action_resume_all
        labels += 6 to R.string.downloads_action_retry_all_failed
        labels += 3 to R.string.downloads_action_clear_completed
        labels += 7 to R.string.downloads_action_clear_watched
        labels += 4 to R.string.downloads_action_clear_failed
        labels += 5 to R.string.downloads_action_settings
        val actionTitles = labels.map { getString(it.second) }.toTypedArray()
        val sortOptions = listOf(
            DownloadsSort.NEWEST to R.string.downloads_sort_newest,
            DownloadsSort.TITLE to R.string.downloads_sort_title,
            DownloadsSort.SIZE to R.string.downloads_sort_size,
        )
        val sortChecked = sortOptions.indexOfFirst { it.first == viewModel.currentSort() }
            .coerceAtLeast(0)
        val sortTitles = sortOptions.map { getString(it.second) }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.downloads_title)
            .setItems(actionTitles) { _, which ->
                when (labels[which].first) {
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
                }
            }
            .setNeutralButton(R.string.downloads_sort) { _, _ ->
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.downloads_sort)
                    .setSingleChoiceItems(sortTitles, sortChecked) { dialog, which ->
                        sortOptions.getOrNull(which)?.let { viewModel.setSort(it.first) }
                        dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .show()
    }

    private fun confirmDestructive(messageRes: Int, onConfirm: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setMessage(messageRes)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showItemMenu(row: DownloadRowUiModel.Item, anchor: View) {
        val isDash = ExternalDownloadHandoff.isDashStream(row.entity.streamUrl, row.entity.mimeType)
        val labels = mutableListOf<Pair<Int, Int>>()
        if (row.state == DownloadItemState.COMPLETED) {
            labels += 1 to R.string.downloads_action_share
            if (!isDash) {
                labels += 4 to R.string.downloads_action_play_with
            }
        }
        if (row.entity.kind == DownloadKind.EPISODE.name) {
            labels += 6 to R.string.downloads_pack_share
        }
        // Leanback/TV: no ADM/1DM handoff (external downloader prefs are hidden too).
        if (row.state == DownloadItemState.FAILED) {
            labels += 2 to R.string.downloads_action_retry
        }
        labels += 3 to R.string.downloads_action_delete
        val titles = labels.map { getString(it.second) }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setItems(titles) { _, which ->
                when (labels[which].first) {
                    1 -> share(row)
                    2 -> retry(row)
                    3 -> confirmDestructive(R.string.downloads_delete_confirm) {
                        viewModel.remove(row.id)
                    }
                    4 -> playWithExternal(row)
                    6 -> com.dskja.betterstreamflix.download.DownloadPackUi.shareItems(
                        this@DownloadsTvFragment,
                        listOf(row.entity),
                        row.entity.title,
                    )
                }
            }
            .show()
    }

    private fun showPackMenu(pack: DownloadRowUiModel.SeasonPack, anchor: View) {
        val labels = listOf(
            4 to R.string.downloads_pack_share,
            1 to R.string.downloads_pack_pause,
            2 to R.string.downloads_pack_resume,
            3 to R.string.downloads_pack_delete,
        )
        val titles = labels.map { getString(it.second) }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setItems(titles) { _, which ->
                when (labels[which].first) {
                    4 -> com.dskja.betterstreamflix.download.DownloadPackUi.shareSeasonPack(
                        this@DownloadsTvFragment,
                        pack.pack,
                    )
                    1 -> viewModel.pausePack(pack.pack.id)
                    2 -> viewModel.resumePack(pack.pack.id)
                    3 -> confirmDestructive(R.string.downloads_delete_confirm) {
                        viewModel.removePack(pack.pack.id)
                    }
                }
            }
            .show()
    }

    private fun share(row: DownloadRowUiModel.Item) {
        viewLifecycleOwner.lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                OfflinePlayback.exportShareUri(requireContext(), row.entity)
            } ?: run {
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

    private fun playWithExternal(row: DownloadRowUiModel.Item) {
        if (ExternalDownloadHandoff.isDashStream(row.entity.streamUrl, row.entity.mimeType)) {
            showDownloadError(R.string.downloads_play_with_dash_unsupported)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val localUri = withContext(Dispatchers.IO) {
                OfflinePlayback.exportShareUri(requireContext(), row.entity)?.toString()
            }
            if (localUri.isNullOrBlank()) {
                showDownloadError(R.string.downloads_play_with_cache_only)
                return@launch
            }
            val shareUri = android.net.Uri.parse(localUri)
            ExternalStreamHandoff.launch(
                requireActivity(),
                ExternalStreamHandoff.Request(
                    sourceUrl = localUri,
                    title = row.entity.title,
                    mimeType = OfflinePlayback.mimeForShareUri(
                        shareUri,
                        row.entity.mimeType.ifBlank { "video/*" },
                    ),
                ),
                forceChooser = true,
            )
        }
    }

    private fun playOffline(row: DownloadRowUiModel.Item) = playOfflineEntity(row.entity)

    private fun playOfflineEntity(entity: DownloadItemEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!viewModel.isPlayAllowed(entity)) {
                showDownloadError(R.string.downloads_failed_generic)
                return@launch
            }
            val videoType = DownloadController.deserializeVideoType(entity.videoTypeJson)
            if (videoType == null) {
                showDownloadError(R.string.download_error_file_missing)
                return@launch
            }
            activateDownloadProvider(entity.providerName)
            val local = withContext(Dispatchers.IO) {
                OfflinePlayback.buildLocalVideo(requireContext(), entity)
            } ?: run {
                showDownloadError(R.string.download_error_file_missing)
                return@launch
            }
            viewModel.touchDownloads(listOf(entity.id))
            OfflineVideoCache.put(entity.contentKey, local)
            findNavController().navigate(
                R.id.action_global_player,
                bundleOf(
                    "id" to videoType.let {
                        when (it) {
                            is Video.Type.Movie -> it.id
                            is Video.Type.Episode -> it.id
                        }
                    },
                    "title" to entity.title,
                    "subtitle" to entity.subtitle,
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
        Provider.findByName(name)?.let { UserPreferences.setCurrentProviderForPlayback(it) }
    }

    private fun showDownloadError(messageRes: Int) {
        val message = getString(messageRes)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.downloads_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
