package com.dskja.betterstreamflix.fragments.downloads

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.FragmentDownloadsTvBinding
import com.dskja.betterstreamflix.download.DownloadController
import com.dskja.betterstreamflix.download.DownloadItemState
import com.dskja.betterstreamflix.download.ExternalDownloadHandoff
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
import com.dskja.betterstreamflix.platform.playerbackend.ExternalStreamHandoff
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.TvFocusChain
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.viewModelsFactory
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
        binding.rvDownloads.itemAnimator = null
        binding.rvDownloads.adapter = adapter
        wireDownloadFocus()
        binding.btnDownloadsMenu.setOnClickListener { showMenu(it) }
        binding.chipFilterAll.setOnClickListener { viewModel.setFilter(DownloadsFilter.ALL) }
        binding.chipFilterDownloading.setOnClickListener { viewModel.setFilter(DownloadsFilter.DOWNLOADING) }
        binding.chipFilterCompleted.setOnClickListener { viewModel.setFilter(DownloadsFilter.COMPLETED) }
        binding.chipFilterFailed.setOnClickListener { viewModel.setFilter(DownloadsFilter.FAILED) }

        binding.root.findViewById<View>(R.id.v_downloads_filters_edge_fade_start)?.isVisible = false
        binding.root.findViewById<View>(R.id.v_downloads_filters_edge_fade_end)?.isVisible = false

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
                val downId = if (empty) {
                    emptyCta?.id ?: binding.chipFilterAll.id
                } else {
                    binding.rvDownloads.id
                }
                listOf(
                    binding.chipFilterAll,
                    binding.chipFilterDownloading,
                    binding.chipFilterCompleted,
                    binding.chipFilterFailed,
                ).forEach { chip -> chip.nextFocusDownId = downId }
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

    private fun wireDownloadFocus() {
        binding.hsvDownloadsFilters.isFocusable = false
        TvFocusChain.linkHorizontal(
            binding.chipFilterAll,
            binding.chipFilterDownloading,
            binding.chipFilterCompleted,
            binding.chipFilterFailed,
        )
        binding.chipFilterAll.nextFocusLeftId = R.id.nav_main
        listOf(
            binding.chipFilterAll,
            binding.chipFilterDownloading,
            binding.chipFilterCompleted,
            binding.chipFilterFailed,
        ).forEach { chip ->
            chip.nextFocusUpId = binding.btnDownloadsMenu.id
            chip.nextFocusDownId = binding.rvDownloads.id
        }
        binding.btnDownloadsMenu.nextFocusDownId = binding.chipFilterAll.id
        binding.btnDownloadsMenu.nextFocusLeftId = binding.chipFilterFailed.id
    }

    private fun updateBanner(lowSpace: Boolean, wifiPaused: Boolean) {
        when {
            wifiPaused -> {
                binding.tvDownloadsBanner.isVisible = true
                binding.tvDownloadsBanner.setText(R.string.downloads_wifi_paused)
            }
            lowSpace -> {
                binding.tvDownloadsBanner.isVisible = true
                binding.tvDownloadsBanner.setText(R.string.downloads_low_space)
            }
            else -> binding.tvDownloadsBanner.isVisible = false
        }
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
        labels += 10 to R.string.downloads_sort_newest
        labels += 11 to R.string.downloads_sort_title
        labels += 12 to R.string.downloads_sort_size
        labels += 5 to R.string.downloads_action_settings
        val titles = labels.map { getString(it.second) }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.downloads_title)
            .setItems(titles) { _, which ->
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
                    10 -> viewModel.setSort(DownloadsSort.NEWEST)
                    11 -> viewModel.setSort(DownloadsSort.TITLE)
                    12 -> viewModel.setSort(DownloadsSort.SIZE)
                }
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
        val labels = mutableListOf<Pair<Int, Int>>()
        if (row.state == DownloadItemState.COMPLETED) {
            labels += 1 to R.string.downloads_action_share
            labels += 4 to R.string.downloads_action_play_with
        }
        if (row.entity.streamUrl.startsWith("http")) {
            labels += 5 to R.string.downloads_action_download_with
        }
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
                    3 -> viewModel.remove(row.id)
                    4 -> playWithExternal(row)
                    5 -> downloadWithExternal(row)
                }
            }
            .show()
    }

    private fun showPackMenu(pack: DownloadRowUiModel.SeasonPack, anchor: View) {
        val labels = listOf(
            1 to R.string.downloads_pack_pause,
            2 to R.string.downloads_pack_resume,
            3 to R.string.downloads_pack_delete,
        )
        val titles = labels.map { getString(it.second) }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setItems(titles) { _, which ->
                when (labels[which].first) {
                    1 -> viewModel.pausePack(pack.pack.id)
                    2 -> viewModel.resumePack(pack.pack.id)
                    3 -> viewModel.removePack(pack.pack.id)
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
        val ok = ExternalDownloadHandoff.launch(
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
        if (ok) {
            ExpDialogChrome.notify(requireContext(), R.string.external_download_started)
        }
    }

    private fun playOffline(row: DownloadRowUiModel.Item) {
        viewLifecycleOwner.lifecycleScope.launch {
            val videoType = DownloadController.deserializeVideoType(row.entity.videoTypeJson)
            if (videoType == null) {
                showDownloadError(R.string.download_error_file_missing)
                return@launch
            }
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
        Provider.findByName(name)?.let { UserPreferences.setCurrentProviderForPlayback(it) }
    }

    private fun showDownloadError(messageRes: Int) {
        val message = getString(messageRes)
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
