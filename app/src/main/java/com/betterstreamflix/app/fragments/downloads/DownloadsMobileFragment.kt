package com.betterstreamflix.app.fragments.downloads

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.betterstreamflix.app.R
import com.betterstreamflix.app.activities.tools.QrScannerActivity
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.databinding.FragmentDownloadsMobileBinding
import com.betterstreamflix.app.download.DownloadController
import com.betterstreamflix.app.download.DownloadItemEntity
import com.betterstreamflix.app.download.DownloadItemState
import com.betterstreamflix.app.download.DownloadKind
import com.betterstreamflix.app.download.DownloadPackUi
import com.betterstreamflix.app.download.ExternalDownloadHandoff
import com.betterstreamflix.app.download.OfflineExpiryNudge
import com.betterstreamflix.app.download.OfflineLibrary
import com.betterstreamflix.app.download.OfflinePlayback
import com.betterstreamflix.app.download.ui.DownloadOptionsController
import com.betterstreamflix.app.download.ui.DownloadRowUiModel
import com.betterstreamflix.app.download.ui.DownloadsAdapter
import com.betterstreamflix.app.download.ui.DownloadsFilter
import com.betterstreamflix.app.download.ui.DownloadsSort
import com.betterstreamflix.app.download.ui.DownloadsViewMode
import com.betterstreamflix.app.download.ui.DownloadsViewModel
import com.betterstreamflix.app.download.ui.OfflineLibraryActions
import com.betterstreamflix.app.download.ui.OfflineStudioController
import com.betterstreamflix.app.fragments.settings.SettingsDeepLink
import com.betterstreamflix.app.models.Video
import com.betterstreamflix.app.platform.playerbackend.ExternalStreamHandoff
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.ui.SpacingItemDecoration
import com.betterstreamflix.app.utils.ExpDialogChrome
import com.betterstreamflix.app.utils.UserPreferences
import com.betterstreamflix.app.utils.dp
import com.betterstreamflix.app.utils.hideKeyboard
import com.betterstreamflix.app.utils.viewModelsFactory
import com.betterstreamflix.app.utils.ExpMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadsMobileFragment : Fragment() {
    private var _binding: FragmentDownloadsMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModelsFactory {
        DownloadsViewModel(requireContext().applicationContext)
    }

    private val scanPackQrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val raw = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_VALUE).orEmpty()
        val activity = activity ?: return@registerForActivityResult
        DownloadPackUi.handleIncomingRaw(activity, raw)
    }

    private var libraryEntries: List<OfflineLibrary.Entry> = emptyList()
    private var librarySpacing: RecyclerView.ItemDecoration? = null
    private var studioController: OfflineStudioController? = null
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
            confirmDestructive(R.string.downloads_pack_delete_confirm) {
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
        ExpMotion.enterScreen(binding.root)
        ExpMotion.staggerFirstFill(binding.rvDownloads)
        binding.rvDownloads.itemAnimator = null
        binding.btnDownloadsMenu.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                showMenu(it)
            }
        }
        studioController = OfflineStudioController(
            fragment = this,
            root = binding.root,
            viewModel = viewModel,
            isTv = false,
            onScanPackQr = { scanDownloadPackQr() },
            styleModeChip = { chip, selected -> styleChip(chip, selected) },
        ).also { it.attach() }
        wireOfflineLibrarySearch()
        listOf(
            binding.chipFilterAll to DownloadsFilter.ALL,
            binding.chipFilterDownloading to DownloadsFilter.DOWNLOADING,
            binding.chipFilterCompleted to DownloadsFilter.COMPLETED,
            binding.chipFilterFailed to DownloadsFilter.FAILED,
        ).forEach { (chip, filter) ->
            chip.setOnClickListener {
                ExpMotion.hapticTap(it)
                viewModel.setFilter(filter)
            }
        }

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
                    libraryAdapter.submitList(OfflineLibrary.toAppItems(entries, mobile = true))
                    renderEmptyState(
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
                if (viewModel.currentViewMode() != DownloadsViewMode.QUEUE) return@collect
                adapter.submitList(rows.toList())
                renderEmptyState(
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
        applyViewMode(viewModel.currentViewMode())
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
            // Don't stack on Care / Controls; wifi / low-space banners may coexist (OS-024).
            val mode = viewModel.currentViewMode()
            if (mode == DownloadsViewMode.CARE || mode == DownloadsViewMode.CONTROLS) return@launch
            val stale = viewModel.loadOfflineExpiryCandidates()
            if (stale.isEmpty() || !isAdded) return@launch
            OfflineExpiryNudge.maybeShow(
                fragment = this@DownloadsMobileFragment,
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

    private fun applyViewMode(mode: DownloadsViewMode) {
        val library = mode == DownloadsViewMode.LIBRARY
        val queue = mode == DownloadsViewMode.QUEUE
        val studioPanel = mode == DownloadsViewMode.CARE || mode == DownloadsViewMode.CONTROLS
        binding.tvDownloadsTitle.setText(
            when (mode) {
                DownloadsViewMode.CARE, DownloadsViewMode.CONTROLS -> R.string.offline_studio_title
                else -> studioController?.modeTitleRes(mode) ?: R.string.offline_studio_title
            },
        )
        binding.llOfflineLibraryBadge.isVisible = library
        binding.clOfflineLibrarySearch.isVisible = library
        binding.hsvDownloadsFilters.isVisible = queue
        binding.rvDownloads.isVisible = library || queue
        studioController?.applyMode(mode)
        if (studioPanel) {
            binding.tvDownloadsEmpty.isVisible = false
            binding.root.findViewById<View>(R.id.v_downloads_empty_rule)?.isVisible = false
            binding.root.findViewById<View>(R.id.btn_downloads_empty_cta)?.isVisible = false
            return
        }
        librarySpacing?.let { binding.rvDownloads.removeItemDecoration(it) }
        librarySpacing = null
        if (library) {
            val span = when {
                resources.configuration.smallestScreenWidthDp >= 600 -> 4
                else -> 3
            }
            binding.rvDownloads.layoutManager = GridLayoutManager(requireContext(), span)
            binding.rvDownloads.adapter = libraryAdapter
            librarySpacing = SpacingItemDecoration(10.dp(requireContext())).also {
                binding.rvDownloads.addItemDecoration(it)
            }
            libraryAdapter.submitList(OfflineLibrary.toAppItems(libraryEntries, mobile = true))
            renderEmptyState(
                empty = libraryEntries.isEmpty(),
                libraryMode = true,
                searchActive = viewModel.librarySearchQuery.value.isNotBlank(),
                manageFilter = viewModel.currentFilter(),
            )
        } else {
            val tablet = resources.configuration.smallestScreenWidthDp >= 600
            binding.rvDownloads.layoutManager = if (tablet) {
                GridLayoutManager(requireContext(), 2).also { grid ->
                    grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int =
                            adapter.spanForPosition(position, fullSpan = 2)
                    }
                }
            } else {
                LinearLayoutManager(requireContext())
            }
            binding.rvDownloads.adapter = adapter
            adapter.submitList(viewModel.rows.value)
            renderEmptyState(
                empty = viewModel.rows.value.isEmpty(),
                libraryMode = false,
                searchActive = false,
                manageFilter = viewModel.currentFilter(),
            )
        }
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

    private fun renderEmptyState(
        empty: Boolean,
        libraryMode: Boolean,
        searchActive: Boolean,
        manageFilter: DownloadsFilter,
    ) {
        val cta = binding.root.findViewById<View>(R.id.btn_downloads_empty_cta)
        val emptyRule = binding.root.findViewById<View>(R.id.v_downloads_empty_rule)
        if (!empty) {
            binding.tvDownloadsEmpty.isVisible = false
            emptyRule?.isVisible = false
            cta?.isVisible = false
            return
        }
        binding.tvDownloadsEmpty.setText(
            when {
                libraryMode && searchActive -> R.string.offline_library_empty_search
                libraryMode -> R.string.offline_library_empty
                manageFilter == DownloadsFilter.ALL -> R.string.downloads_empty
                else -> R.string.downloads_filter_empty
            },
        )
        binding.tvDownloadsEmpty.isVisible = true
        emptyRule?.isVisible = true
        binding.root.findViewById<android.widget.TextView>(R.id.btn_downloads_empty_cta)?.let { chip ->
            chip.isVisible = true
            val resetLibrarySearch = libraryMode && searchActive
            val filterAll = !libraryMode && manageFilter == DownloadsFilter.ALL
            chip.setText(
                when {
                    resetLibrarySearch -> R.string.exp_empty_clear_filter
                    filterAll || libraryMode -> R.string.exp_empty_browse_catalog
                    else -> R.string.exp_empty_clear_filter
                },
            )
            chip.setOnClickListener {
                ExpMotion.hapticTap(it)
                when {
                    resetLibrarySearch -> {
                        binding.etOfflineLibrarySearch.setText("")
                        viewModel.setLibraryQuery("")
                    }
                    filterAll || libraryMode -> findNavController().navigate(R.id.home)
                    else -> viewModel.setFilter(DownloadsFilter.ALL)
                }
            }
        }
    }

    private fun styleFilters(selected: DownloadsFilter) {
        styleChip(binding.chipFilterAll, selected == DownloadsFilter.ALL)
        styleChip(binding.chipFilterDownloading, selected == DownloadsFilter.DOWNLOADING)
        styleChip(binding.chipFilterCompleted, selected == DownloadsFilter.COMPLETED)
        styleChip(binding.chipFilterFailed, selected == DownloadsFilter.FAILED)
    }

    private fun styleChip(chip: android.widget.TextView, selected: Boolean) {
        chip.isSelected = selected
        chip.isClickable = true
        chip.isFocusable = true
        ViewCompat.setAccessibilityHeading(chip, false)
        ViewCompat.setStateDescription(
            chip,
            if (selected) getString(R.string.downloads_filter_selected_a11y, chip.text) else null,
        )
        chip.contentDescription = if (selected) {
            getString(R.string.downloads_filter_selected_a11y, chip.text)
        } else {
            chip.text
        }
        chip.setBackgroundResource(
            if (selected) R.drawable.bg_download_filter_chip_selected
            else R.drawable.bg_download_filter_chip,
        )
        chip.setTextColor(
            if (selected) 0xFF111111.toInt() else 0xFFFFFFFF.toInt(),
        )
        chip.refreshDrawableState()
    }

    private fun updateBanner(
        lowSpace: Boolean,
        wifiPaused: Boolean,
        health: com.betterstreamflix.app.download.DownloadHealth.Summary,
    ) {
        val row = binding.llDownloadsBanner
        val banner = binding.tvDownloadsBanner
        val cta = binding.btnDownloadsHealthRedownload
        // Care already hosts health CTAs — avoid duplicate banner (OS-028 / OS-017).
        val hideHealthInCare = viewModel.currentViewMode() == DownloadsViewMode.CARE
        when {
            wifiPaused -> {
                banner.setText(R.string.downloads_wifi_paused)
                cta.isVisible = false
            }
            lowSpace -> {
                banner.setText(R.string.downloads_low_space)
                cta.isVisible = false
            }
            health.hasUnhealthy && !hideHealthInCare -> {
                banner.text = com.betterstreamflix.app.download.DownloadHealth
                    .bannerMessage(requireContext(), health)
                cta.isVisible = true
            }
            else -> {
                cta.isVisible = false
                row.isVisible = false
                return
            }
        }
        row.isVisible = true
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
            menu.add(0, 13, 7, R.string.downloads_action_storage)
            menu.add(0, 14, 8, R.string.download_pack_scan_qr)
            menu.add(0, 5, 9, R.string.downloads_action_settings)
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
                    5, 13 -> {
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
                    14 -> scanDownloadPackQr()
                }
                true
            }
            show()
        }
    }

    private fun confirmDestructive(messageRes: Int, onConfirm: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setMessage(messageRes)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    com.betterstreamflix.app.utils.ExpDialogChrome.polishButtons(dialog)
                }
                dialog.show()
            }
    }

    private fun showItemMenu(row: DownloadRowUiModel.Item, anchor: View) {
        val isDash = ExternalDownloadHandoff.isDashStream(row.entity.streamUrl, row.entity.mimeType)
        val isAdaptive = ExternalDownloadHandoff.isAdaptiveStream(
            row.entity.streamUrl,
            row.entity.mimeType,
        )
        val isEpisode = row.entity.kind == DownloadKind.EPISODE.name
        PopupMenu(requireContext(), anchor).apply {
            if (row.state == DownloadItemState.COMPLETED) {
                menu.add(0, 1, 0, R.string.downloads_action_share)
                if (!isDash) {
                    menu.add(0, 4, 1, R.string.downloads_action_play_with)
                }
            }
            if (isEpisode) {
                menu.add(0, 6, 2, R.string.downloads_pack_share)
            }
            if (row.entity.streamUrl.startsWith("http") && !isAdaptive) {
                menu.add(0, 5, 3, R.string.downloads_action_download_with)
            }
            if (row.state == DownloadItemState.FAILED) {
                menu.add(0, 2, 4, R.string.downloads_action_retry)
            }
            menu.add(0, 3, 5, R.string.downloads_action_delete)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> share(row)
                    2 -> retry(row)
                    3 -> confirmDestructive(R.string.downloads_delete_confirm) {
                        viewModel.remove(row.id)
                    }
                    4 -> playWithExternal(row)
                    5 -> downloadWithExternal(row)
                    6 -> com.betterstreamflix.app.download.DownloadPackUi.shareItems(
                        this@DownloadsMobileFragment,
                        listOf(row.entity),
                        row.entity.title,
                    )
                }
                true
            }
            show()
        }
    }

    private fun showPackMenu(pack: DownloadRowUiModel.SeasonPack, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 4, 0, R.string.downloads_pack_share)
            menu.add(0, 1, 1, R.string.downloads_pack_pause)
            menu.add(0, 2, 2, R.string.downloads_pack_resume)
            menu.add(0, 3, 3, R.string.downloads_pack_delete)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    4 -> com.betterstreamflix.app.download.DownloadPackUi.shareSeasonPack(
                        this@DownloadsMobileFragment,
                        pack.pack,
                    )
                    1 -> viewModel.pausePack(pack.pack.id)
                    2 -> viewModel.resumePack(pack.pack.id)
                    3 -> confirmDestructive(R.string.downloads_pack_delete_confirm) {
                        viewModel.removePack(pack.pack.id)
                    }
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

    private fun playWithExternal(row: DownloadRowUiModel.Item) {
        if (ExternalDownloadHandoff.isDashStream(row.entity.streamUrl, row.entity.mimeType)) {
            showDownloadError(R.string.downloads_play_with_dash_unsupported)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val localUri = withContext(Dispatchers.IO) {
                OfflinePlayback.exportShareUri(requireContext(), row.entity)?.toString()
            }
            // Cache-only Media3 downloads have no file another app can open.
            // Do not fall back to a possibly expired CDN URL.
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

    private fun downloadWithExternal(row: DownloadRowUiModel.Item) {
        val url = row.entity.streamUrl
        if (!url.startsWith("http")) {
            ExpDialogChrome.notify(
                requireContext(),
                R.string.external_download_invalid_url,
                R.string.external_download_with_title,
            )
            return
        }
        val headers = runCatching {
            val o = org.json.JSONObject(row.entity.headersJson.ifBlank { "{}" })
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        val result = ExternalDownloadHandoff.launch(
            requireActivity(),
            ExternalDownloadHandoff.Request(
                url = url,
                headers = headers,
                fileName = row.entity.title,
                mimeType = row.entity.mimeType,
                title = row.entity.title,
            ),
            forceChooser = false,
        )
        if (result is ExternalDownloadHandoff.HandoffResult.Started) {
            ExpDialogChrome.notify(requireContext(), R.string.external_download_started)
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
                showDownloadError(R.string.downloads_failed_generic)
                return@launch
            }
            activateDownloadProvider(entity.providerName)
            val local = withContext(Dispatchers.IO) {
                OfflinePlayback.buildLocalVideo(requireContext(), entity)
            }
            if (local == null) {
                showDownloadError(R.string.download_error_file_missing)
                return@launch
            }
            // Opening offline counts as a touch for the 30-day expiry clock.
            viewModel.touchDownloads(listOf(entity.id))
            OfflineVideoCache.put(entity.contentKey, local)
            com.betterstreamflix.app.player.PlayerCleanExit.rememberHomeFocus(
                entity.contentKey,
                rowHint = "downloads",
            )
            findNavController().navigate(
                R.id.action_global_player,
                bundleOf(
                    "id" to when (videoType) {
                        is Video.Type.Movie -> videoType.id
                        is Video.Type.Episode -> videoType.id
                    },
                    "title" to entity.title,
                    "subtitle" to entity.subtitle,
                    "videoType" to videoType,
                    "preferredServerName" to OFFLINE_SERVER,
                ),
            )
        }
    }

    private fun showDownloadError(messageRes: Int) {
        Toast.makeText(requireContext(), getString(messageRes), Toast.LENGTH_SHORT).show()
    }

    private fun retry(row: DownloadRowUiModel.Item) {
        viewLifecycleOwner.lifecycleScope.launch {
            val videoType = DownloadController.deserializeVideoType(row.entity.videoTypeJson) ?: return@launch
            activateDownloadProvider(row.entity.providerName)
            viewModel.remove(row.id)
            when (videoType) {
                is Video.Type.Movie -> {
                    val movie = com.betterstreamflix.app.models.Movie(
                        id = videoType.id,
                        title = videoType.title,
                        poster = videoType.poster,
                        providerName = row.entity.providerName,
                    )
                    DownloadOptionsController.enqueueMovie(this@DownloadsMobileFragment, movie)
                }
                is Video.Type.Episode -> {
                    val episode = com.betterstreamflix.app.models.Episode(
                        id = videoType.id,
                        number = videoType.number,
                        title = videoType.title,
                        poster = videoType.poster,
                        overview = videoType.overview,
                        tvShow = com.betterstreamflix.app.models.TvShow(
                            id = videoType.tvShow.id,
                            title = videoType.tvShow.title,
                            poster = videoType.tvShow.poster,
                            providerName = row.entity.providerName,
                        ),
                        season = com.betterstreamflix.app.models.Season(
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

    private fun scanDownloadPackQr() {
        scanPackQrLauncher.launch(Intent(requireContext(), QrScannerActivity::class.java))
    }

    /** Prefer the download's stamped provider so autoplay/retry resolve the right catalog. */
    private fun activateDownloadProvider(providerName: String?) {
        val name = providerName?.takeIf { it.isNotBlank() } ?: return
        Provider.findByName(name)?.let { UserPreferences.setCurrentProviderForPlayback(it) }
    }

    override fun onDestroyView() {
        studioController = null
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
 * [take] is a consume-once helper used only after validation in [com.betterstreamflix.app.fragments.player.PlayerViewModel]
 * resolve — do not use it as the primary offline resolution path.
 */
object OfflineVideoCache {
    private val map = mutableMapOf<String, Video>()
    fun put(key: String, video: Video) {
        map[key] = video
    }
    /** Consume a cached video once. Prefer Room + [com.betterstreamflix.app.download.OfflinePlayback] for truth. */
    fun take(key: String): Video? = map.remove(key)
    fun get(key: String): Video? = map[key]
    fun remove(key: String) {
        map.remove(key)
    }
    fun clear() {
        map.clear()
    }
}
