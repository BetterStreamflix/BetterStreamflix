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
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.Http409CacheGuard
import com.dskja.betterstreamflix.utils.LoggingUtils
import com.dskja.betterstreamflix.utils.viewModelsFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
                        if (!ExperimentalMobileDesign.enabled()) {
                            Toast.makeText(
                                requireContext(),
                                state.error.message ?: "",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        binding.isLoading.apply {
                            com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, false)
                            gIsLoadingRetry.visibility = View.VISIBLE
                            com.dskja.betterstreamflix.utils.ExpPressEffects.animateLoadingError(root)
                            btnIsLoadingRetry.setOnClickListener { viewModel.getSeasonEpisodes(args.seasonId) }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(requireContext(), getString(com.dskja.betterstreamflix.R.string.clear_cache_done), com.dskja.betterstreamflix.R.string.loading_error_clear_cache)
                                viewModel.getSeasonEpisodes(args.seasonId)
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
                            btnIsLoadingRetry.requestFocus()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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
        )

        if (ExperimentalMobileDesign.enabled()) {
            ExpMotion.enterScreen(binding.root)
            binding.tvSeasonTitle.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.tvSeasonTitle,
                    com.google.android.material.R.attr.colorOnSurface,
                ),
            )
            binding.root.findViewById<View>(R.id.v_season_title_rule)?.visibility = View.VISIBLE
            ExpMotion.revealHeader(
                binding.tvSeasonTitle,
                binding.root.findViewById(R.id.v_season_title_rule),
            )
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_season_title_rule))
            with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                binding.spSeasonPicker.applyExpPress()
                binding.btnSeasonDownload.applyExpPress()
            }
            binding.spSeasonPicker.setBackgroundResource(ExperimentalMobileDesign.spinnerBackground())
            binding.btnSeasonDownload.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
            ExpMotion.popIn(binding.spSeasonPicker)
            ExpMotion.popIn(binding.btnSeasonDownload)
        }

        binding.btnSeasonDownload.setOnClickListener {
            if (ExperimentalMobileDesign.enabled()) ExpMotion.hapticTap(it)
            val episodes = loadedEpisodes
            if (episodes.isEmpty()) {
                if (ExperimentalMobileDesign.enabled()) {
                    ExpDialogChrome.showInfo(
                        requireContext(),
                        R.string.season_download,
                        getString(R.string.season_download_empty),
                    ) { ctx -> MaterialAlertDialogBuilder(ctx) }
                } else {
                    Toast.makeText(requireContext(), R.string.season_download_empty, Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }
            val confirm = if (ExperimentalMobileDesign.enabled()) {
                MaterialAlertDialogBuilder(requireContext())
            } else {
                AlertDialog.Builder(requireContext())
            }
            val confirmMessage = getString(R.string.season_download_confirm, episodes.size)
            val glass = if (ExperimentalMobileDesign.enabled()) {
                ExpDialogChrome.buildGlassMessage(requireContext(), confirmMessage)
            } else {
                null
            }
            if (glass != null) confirm.setView(glass.root)
            else confirm.setMessage(confirmMessage)
            confirm
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (ExperimentalMobileDesign.enabled()) ExpMotion.hapticTap(binding.root)
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
                        if (glass != null) ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                        else ExpDialogChrome.polishButtons(dialog)
                        if (ExperimentalMobileDesign.enabled()) {
                            dialog.window?.decorView?.let { ExpMotion.enterScreen(it) }
                        }
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
