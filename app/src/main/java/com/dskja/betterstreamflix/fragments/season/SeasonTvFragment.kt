package com.dskja.betterstreamflix.fragments.season

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.FragmentSeasonTvBinding
import com.dskja.betterstreamflix.download.ui.DownloadOptionsController
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.Http409CacheGuard
import com.dskja.betterstreamflix.utils.TmdbUtils
import com.dskja.betterstreamflix.utils.format
import com.dskja.betterstreamflix.utils.viewModelsFactory
import com.dskja.betterstreamflix.ui.DetailLoadingErrorChrome
import com.dskja.betterstreamflix.ui.TrailerPlaybackController
import androidx.core.view.isVisible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SeasonTvFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentSeasonTvBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<SeasonTvFragmentArgs>()
    private val database get() = AppDatabase.getInstance(requireContext())
    private val viewModel by viewModelsFactory {
        SeasonViewModel(
            args.seasonId,
            args.tvShowId,
            database,
            args.seasonNumber,
        )
    }

    private val appAdapter = AppAdapter()
    private var loadedEpisodes: List<Episode> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSeasonTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeSeason()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    SeasonViewModel.State.LoadingEpisodes -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                    }

                    is SeasonViewModel.State.SuccessLoadingEpisodes -> {
                        displaySeason(state.episodes)
                        binding.isLoading.root.visibility = View.GONE
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(
                            binding.isLoading.root, false,
                        )
                    }

                    is SeasonViewModel.State.FailedLoadingEpisodes -> {
                        // Auto clear cache on HTTP 409 and retry
                        if (http409Guard.handle(requireContext(), state.error) { viewModel.getSeasonEpisodes(args.seasonId) }) {
                                return@collect
                            }
                        DetailLoadingErrorChrome.bind(
                            root = binding.isLoading.root,
                            context = requireContext(),
                            error = state.error,
                            requestFocusOnRetry = true,
                            onRetry = { viewModel.getSeasonEpisodes(args.seasonId) },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding?.let { appAdapter.onSaveInstanceState(it.hgvEpisodes) }
        _binding = null
        super.onDestroyView()
    }

    private fun initializeSeason() {
        binding.tvSeasonTitle.text = args.seasonTitle

        SeasonSwitcher.bind(
            fragment = this,
            spinner = binding.spSeasonPicker,
            database = database,
            tvShowId = args.tvShowId,
            tvShowTitle = args.tvShowTitle,
            tvShowPoster = args.tvShowPoster,
            tvShowBanner = args.tvShowBanner,
            currentSeasonId = args.seasonId,
            currentSeasonNumber = args.seasonNumber,
            currentSeasonTitle = args.seasonTitle,
            dialogButton = binding.btnSeasonPicker,
        )

        wireSeasonTrailerButton()

        binding.btnSeasonDownload.setOnClickListener {
            val episodes = loadedEpisodes
            if (episodes.isEmpty()) {
                Toast.makeText(requireContext(), R.string.season_download_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val confirm = AlertDialog.Builder(requireContext())
            val confirmMessage = getString(R.string.season_download_confirm, episodes.size)
            confirm
                .setMessage(confirmMessage)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val tvShow = withContext(Dispatchers.IO) {
                            database.tvShowDao().getById(args.tvShowId)
                        } ?: TvShow(
                            id = args.tvShowId,
                            title = viewModel.tvShowTitle.ifBlank {
                                episodes.firstOrNull()?.tvShow?.title.orEmpty()
                            },
                        )
                        DownloadOptionsController.enqueueSeason(
                            this@SeasonTvFragment,
                            tvShow,
                            viewModel.seasonNumber,
                            episodes,
                        )
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    dialog.setOnShowListener {
                        ExpDialogChrome.polishButtons(dialog)
                    }
                    dialog.show()
                }
        }

        binding.hgvEpisodes.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(resources.getDimension(R.dimen.season_episodes_spacing).toInt())
        }
    }

    private fun wireSeasonTrailerButton() {
        viewLifecycleOwner.lifecycleScope.launch {
            val tvShow = withContext(Dispatchers.IO) {
                database.tvShowDao().getById(args.tvShowId)
            }
            val year = tvShow?.released?.format("yyyy")?.toIntOrNull()
            val canLookup = TmdbUtils.hasTrailerLookupKeys(
                tmdbId = tvShow?.tmdbId,
                imdbId = tvShow?.imdbId,
                title = tvShow?.title ?: args.tvShowTitle,
                year = year,
            )
            if (!isAdded || _binding == null) return@launch
            binding.btnSeasonTrailer.isVisible = canLookup
            if (!canLookup) return@launch
            binding.btnSeasonTrailer.setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    val remote = withContext(Dispatchers.IO) {
                        val show = database.tvShowDao().getById(args.tvShowId)
                        TmdbUtils.listYoutubeTrailers(
                            tmdbId = show?.tmdbId,
                            isTv = true,
                            title = show?.title ?: args.tvShowTitle,
                            year = show?.released?.format("yyyy")?.toIntOrNull(),
                            imdbId = show?.imdbId,
                            seasonNumber = args.seasonNumber.takeIf { it > 0 }
                                ?: viewModel.seasonNumber.takeIf { it > 0 },
                        )
                    }
                    if (!isAdded || _binding == null || view == null) return@launch
                    val url = remote.firstOrNull()?.second?.takeIf { it.isNotBlank() }
                    val ctx = context ?: return@launch
                    if (!url.isNullOrBlank()) {
                        TrailerPlaybackController.play(this@SeasonTvFragment, url)
                    } else {
                        Toast.makeText(
                            ctx,
                            R.string.detail_trailer_empty,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    private var focusedEpisodeIndex: Int? = null

    private fun displaySeason(episodes: List<Episode>) {
        loadedEpisodes = episodes
        val preparedEpisodes = episodes.onEach { episode ->
            episode.itemType = AppAdapter.Type.EPISODE_TV_ITEM
        }

        val empty = episodes.isEmpty()
        ExpEmptyChrome.bind(
            emptyView = binding.tvSeasonEmpty,
            emptyRule = binding.root.findViewById(R.id.v_season_empty_rule),
            emptyCta = binding.root.findViewById(R.id.btn_season_empty_cta),
            visible = empty,
            tintOnSurfaceVariant = false,
            onCtaClick = { requireActivity().onBackPressedDispatcher.onBackPressed() },
        )
        binding.hgvEpisodes.visibility = if (empty) View.GONE else View.VISIBLE
        binding.btnSeasonDownload.isEnabled = !empty
        binding.btnSeasonDownload.alpha = if (empty) 0.4f else 1f

        val lastWatchedIndex = episodes
            .filter { it.watchHistory != null }
            .sortedByDescending { it.watchHistory?.lastEngagementTimeUtcMillis }
            .firstOrNull()
            ?.let { episodes.indexOf(it) }
            ?: episodes.indexOfLast { it.isWatched }

        appAdapter.submitList(preparedEpisodes)

        if (!empty && focusedEpisodeIndex == null) {
            val scrollIndex = when {
                lastWatchedIndex == -1 -> 0
                lastWatchedIndex < episodes.lastIndex -> lastWatchedIndex + 1
                else -> lastWatchedIndex
            }
            binding.hgvEpisodes.scrollAndFocus(scrollIndex)
            focusedEpisodeIndex = scrollIndex
        }
    }

    private fun RecyclerView.scrollAndFocus(position: Int) {
        scrollToPosition(position)
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                viewTreeObserver.removeOnGlobalLayoutListener(this)
                findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        })
    }

}
