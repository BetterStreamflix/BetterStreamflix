package com.dskja.betterstreamflix.fragments.season

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.FragmentSeasonMobileBinding
import com.dskja.betterstreamflix.download.ui.DownloadOptionsController
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.Http409CacheGuard
import com.dskja.betterstreamflix.utils.ExpNavAutoHide
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.LoggingUtils
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.viewModelsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SeasonMobileFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentSeasonMobileBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<SeasonMobileFragmentArgs>()
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
        _binding = FragmentSeasonMobileBinding.bind(
            inflater.inflate(
                R.layout.fragment_season_mobile,
                container,
                false,
            )
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ExpNavAutoHide.attach(binding.root)
        com.dskja.betterstreamflix.utils.ExpPressEffects.wireLoadingRetry(binding.isLoading.root)
        ExpMotion.enterScreen(binding.root)
        ExperimentalMobileDesign.applyReducedGlass(binding.root)
        binding.root.findViewById<View>(com.dskja.betterstreamflix.R.id.iv_detail_back)
            ?.also { back ->
                androidx.appcompat.widget.TooltipCompat.setTooltipText(
                    back, back.context.getString(com.dskja.betterstreamflix.R.string.exp_back))
                if (ExperimentalMobileDesign.enabled()) {
                    back.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
                    with(com.dskja.betterstreamflix.utils.ExpPressEffects) { back.applyExpPress() }
                }
            }
            ?.setOnClickListener {
                ExpMotion.hapticTap(it)
                androidx.navigation.Navigation.findNavController(binding.root).navigateUp()
            }
        ExpMotion.staggerFirstFill(binding.rvEpisodes)
        if (ExperimentalMobileDesign.enabled()) {
            ExpMotion.revealHeader(
                binding.root.findViewById(R.id.tv_season_eyebrow),
                binding.tvSeasonTitle,
                binding.root.findViewById(R.id.tv_season_tagline),
                binding.root.findViewById(R.id.v_season_rule),
            )
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_season_rule))
            ExpMotion.popIn(binding.spSeasonPicker)
            ExpMotion.popIn(binding.btnSeasonDownload)
        }

        initializeSeason()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    SeasonViewModel.State.LoadingEpisodes -> binding.isLoading.apply {
                        ExpMotion.fadeInAndShow(root)
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is SeasonViewModel.State.SuccessLoadingEpisodes -> {
                        displaySeason(state.episodes)
                        ExpMotion.fadeOutAndHide(binding.isLoading.root)
                    }
                    is SeasonViewModel.State.FailedLoadingEpisodes -> {
                        if (http409Guard.handle(requireContext(), state.error) { viewModel.getSeasonEpisodes(args.seasonId) }) {
                                return@collect
                            }
                        if (!com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled()) {
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
                                val doRetry = { viewModel.getSeasonEpisodes(args.seasonId) }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(requireContext(), getString(com.dskja.betterstreamflix.R.string.clear_cache_done), com.dskja.betterstreamflix.R.string.loading_error_clear_cache)
                                    doRetry()
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    LoggingUtils.showErrorDialog(requireContext(), state.error)
                                }
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
            with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                binding.spSeasonPicker.applyExpPress()
                binding.btnSeasonDownload.applyExpPress()
            }
            binding.spSeasonPicker.setBackgroundResource(ExperimentalMobileDesign.spinnerBackground())
            binding.btnSeasonDownload.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
        }

        binding.btnSeasonDownload.setOnClickListener {
            ExpMotion.hapticTap(it)
            val episodes = loadedEpisodes
            if (episodes.isEmpty()) {
                if (ExperimentalMobileDesign.enabled()) {
                    com.dskja.betterstreamflix.utils.ExpDialogChrome.showInfo(
                        requireContext(),
                        R.string.season_download,
                        getString(R.string.season_download_empty),
                    ) { ctx ->
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    }
                } else {
                    Toast.makeText(requireContext(), R.string.season_download_empty, Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }
            val confirm = if (ExperimentalMobileDesign.enabled()) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            } else {
                AlertDialog.Builder(requireContext())
            }
            val confirmMessage = getString(R.string.season_download_confirm, episodes.size)
            val glass = if (ExperimentalMobileDesign.enabled()) {
                com.dskja.betterstreamflix.utils.ExpDialogChrome.buildGlassMessage(
                    requireContext(),
                    confirmMessage,
                )
            } else {
                null
            }
            if (glass != null) confirm.setView(glass.root)
            else confirm.setMessage(confirmMessage)
            confirm
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    ExpMotion.hapticTap(binding.root)
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
                            this@SeasonMobileFragment,
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
                        if (glass != null) {
                            com.dskja.betterstreamflix.utils.ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                        } else {
                            com.dskja.betterstreamflix.utils.ExpDialogChrome.polishButtons(dialog)
                        }
                    }
                    dialog.show()
                }
        }

        binding.rvEpisodes.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(20.dp(requireContext()))
            )
        }
    }

    private fun displaySeason(episodes: List<Episode>) {
        loadedEpisodes = episodes
        appAdapter.submitList(episodes.onEach { episode ->
            episode.itemType = AppAdapter.Type.EPISODE_MOBILE_ITEM
        })
        if (episodes.isNotEmpty()) {
            binding.btnSeasonDownload.text = getString(
                R.string.detail_download_season_count,
                args.seasonNumber.coerceAtLeast(1),
                episodes.size,
            )
        } else {
            binding.btnSeasonDownload.setText(R.string.season_download)
        }

        val empty = episodes.isEmpty()
        ExpEmptyChrome.bind(
            emptyView = binding.root.findViewById(R.id.tv_season_empty),
            emptyRule = binding.root.findViewById(R.id.v_season_empty_rule),
            emptyCta = binding.root.findViewById(R.id.btn_season_empty_cta),
            visible = empty,
            tintOnSurfaceVariant = false,
            onCtaClick = { requireActivity().onBackPressedDispatcher.onBackPressed() },
        )
        binding.rvEpisodes.visibility = if (empty) View.GONE else View.VISIBLE
        binding.btnSeasonDownload.isEnabled = !empty
        binding.btnSeasonDownload.alpha = if (empty) 0.4f else 1f
        if (!empty && ExperimentalMobileDesign.enabled()) {
            val chip = binding.btnSeasonDownload
            if (chip.getTag(R.id.exp_enter_animated_tag) != true) {
                chip.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.popIn(chip)
            }
        }

        val episodeIndex = episodes
            .sortedByDescending { it.watchHistory?.lastEngagementTimeUtcMillis }
            .firstOrNull { it.watchHistory != null }
            ?.let { episodes.indexOf(it) }
            ?: episodes.indexOfLast { it.isWatched }
                .takeIf { it != -1 && it + 1 < episodes.size }
                ?.let { it + 1 }

        if (episodeIndex != null) {
            val layoutManager = binding.rvEpisodes.layoutManager as? LinearLayoutManager
            layoutManager?.scrollToPositionWithOffset(
                episodeIndex,
                binding.rvEpisodes.height / 2 - 100.dp(requireContext())
            )
        }
    }
}
