package com.betterstreamflix.app.fragments.season

import com.betterstreamflix.app.models.TrailerCatalog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import com.betterstreamflix.app.R
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.databinding.FragmentSeasonTvBinding
import com.betterstreamflix.app.download.ui.DownloadOptionsController
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.utils.ExpEmptyChrome
import com.betterstreamflix.app.utils.Http409CacheGuard
import com.betterstreamflix.app.utils.TmdbUtils
import com.betterstreamflix.app.utils.TvFocusChain
import com.betterstreamflix.app.utils.format
import com.betterstreamflix.app.utils.viewModelsFactory
import com.betterstreamflix.app.ui.DetailLoadingErrorChrome
import com.betterstreamflix.app.ui.TrailerPlaybackController
import com.betterstreamflix.app.utils.ArtworkUrls
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import androidx.core.view.isVisible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SeasonTvFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentSeasonTvBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<SeasonTvFragmentArgs>()
    private val database get() = AppDatabase.getInstanceOrNull(requireContext())
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
                    SeasonViewModel.State.LoadingEpisodes -> {
                        com.betterstreamflix.app.utils.TvLoadingOverlay.show(binding.isLoading.root)
                        binding.isLoading.gIsLoadingRetry.visibility = View.GONE
                    }

                    is SeasonViewModel.State.SuccessLoadingEpisodes -> {
                        displaySeason(state.episodes)
                        com.betterstreamflix.app.utils.TvLoadingOverlay.hide(binding.isLoading.root)
                    }

                    is SeasonViewModel.State.FailedLoadingEpisodes -> {
                        // Auto clear cache on HTTP 409 and retry
                        if (http409Guard.handle(requireContext(), state.error) { viewModel.getSeasonEpisodes(args.seasonId) }) {
                                return@collect
                            }
                        com.betterstreamflix.app.utils.TvLoadingOverlay.disarm(binding.isLoading.root)
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

    override fun onPause() {
        captureFocusedEpisodeIndex()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        restoreEpisodeFocus()
    }

    override fun onDestroyView() {
        _binding?.let { appAdapter.onSaveInstanceState(it.hgvEpisodes) }
        _binding = null
        super.onDestroyView()
    }

    private fun captureFocusedEpisodeIndex() {
        val ui = _binding ?: return
        val focused = ui.hgvEpisodes.focusedChild ?: return
        val holder = ui.hgvEpisodes.findContainingViewHolder(focused) ?: return
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            focusedEpisodeIndex = pos
        }
    }

    private fun initializeSeason() {
        binding.tvSeasonTitle.text = args.seasonTitle
        binding.root.findViewById<android.widget.TextView>(R.id.tv_season_eyebrow)?.apply {
            text = args.tvShowTitle.ifBlank { getString(R.string.exp_season_eyebrow) }
        }
        bindSeasonBanner()

        val db = database
        if (db == null) {
            runCatching {
                findNavController().navigate(R.id.providers)
            }
            return
        }
        SeasonSwitcher.bind(
            fragment = this,
            spinner = binding.spSeasonPicker,
            database = db,
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
            // One-tap season → Smart-Download queue (quota checked inside enqueueSeason).
            viewLifecycleOwner.lifecycleScope.launch {
                val tvShow = withContext(Dispatchers.IO) {
                    database?.tvShowDao()?.getById(args.tvShowId)
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

        binding.hgvEpisodes.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(resources.getDimension(R.dimen.season_episodes_spacing).toInt())
            // Leanback WRAP_CONTENT HGV is unsafe — pin like detail cast rows (TV-SEAS-06).
            setRowHeight(
                com.betterstreamflix.app.ui.LeanbackDensityPolicy.dpToPx(
                    this,
                    com.betterstreamflix.app.ui.LeanbackDensityPolicy.seasonEpisodeRowHeightDp(),
                ),
            )
            setHasFixedSize(true)
        }
    }

    private fun bindSeasonBanner() {
        val banner = binding.root.findViewById<android.widget.ImageView>(R.id.iv_season_banner)
            ?: return
        val url = ArtworkUrls.bannerOrPoster(args.tvShowBanner, args.tvShowPoster, hero = false)
        if (url.isNullOrBlank()) {
            banner.setImageDrawable(null)
            return
        }
        Glide.with(banner)
            .load(url)
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade(280))
            .into(banner)
    }

    private fun wireSeasonTrailerButton() {
        viewLifecycleOwner.lifecycleScope.launch {
            val tvShow = withContext(Dispatchers.IO) {
                database?.tvShowDao()?.getById(args.tvShowId)
            }
            val year = tvShow?.released?.format("yyyy")?.toIntOrNull()
            val canLookup = TmdbUtils.hasTrailerLookupKeys(
                tmdbId = tvShow?.tmdbId,
                imdbId = tvShow?.imdbId,
                title = tvShow?.title ?: args.tvShowTitle,
                year = year,
            )
            if (!isAdded || _binding == null) return@launch
            // Hide until a playable URL is ready (parity with Movie/TV detail hero CTAs).
            binding.btnSeasonTrailer.isVisible = false
            rewireSeasonHeaderFocus()
            if (!canLookup) return@launch

            val remote = withContext(Dispatchers.IO) {
                TmdbUtils.listYoutubeTrailers(
                    tmdbId = tvShow?.tmdbId,
                    isTv = true,
                    title = tvShow?.title ?: args.tvShowTitle,
                    year = year,
                    imdbId = tvShow?.imdbId,
                    seasonNumber = args.seasonNumber.takeIf { it > 0 }
                        ?: viewModel.seasonNumber.takeIf { it > 0 },
                )
            }
            if (!isAdded || _binding == null) return@launch
            val url = TrailerCatalog.preferredPlayableUrl(remote)
            if (url.isNullOrBlank()) return@launch

            // Empty season: keep exclusive focus on empty CTA (TV-SEAS-07).
            if (loadedEpisodes.isEmpty() && binding.tvSeasonEmpty.isVisible) {
                binding.btnSeasonTrailer.isVisible = false
                rewireSeasonHeaderFocus()
                return@launch
            }

            binding.btnSeasonTrailer.isVisible = true
            binding.btnSeasonTrailer.setOnClickListener {
                TrailerPlaybackController.play(this@SeasonTvFragment, url)
            }
            rewireSeasonHeaderFocus()
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
        if (empty) {
            // Trailer competes with empty CTA — hide it while empty (TV-SEAS-07).
            binding.btnSeasonTrailer.isVisible = false
            val emptyCta = binding.root.findViewById<View>(R.id.btn_season_empty_cta)
            emptyCta?.visibility = View.VISIBLE
            emptyCta?.isFocusable = true
            emptyCta?.isFocusableInTouchMode = true
            emptyCta?.setOnClickListener {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
            emptyCta?.post { emptyCta.requestFocus() }
        }
        binding.btnSeasonDownload.isEnabled = !empty
        binding.btnSeasonDownload.alpha = if (empty) 0.4f else 1f
        if (episodes.isNotEmpty()) {
            binding.btnSeasonDownload.text = getString(
                R.string.detail_download_season_count,
                args.seasonNumber.coerceAtLeast(1),
                episodes.size,
            )
        } else {
            binding.btnSeasonDownload.setText(R.string.season_download)
        }

        val lastWatchedIndex = episodes
            .filter { it.watchHistory != null }
            .sortedByDescending { it.watchHistory?.lastEngagementTimeUtcMillis }
            .firstOrNull()
            ?.let { episodes.indexOf(it) }
            ?: episodes.indexOfLast { it.isWatched }

        appAdapter.submitList(preparedEpisodes)

        if (!empty) {
            val scrollIndex = focusedEpisodeIndex ?: when {
                lastWatchedIndex == -1 -> 0
                lastWatchedIndex < episodes.lastIndex -> lastWatchedIndex + 1
                else -> lastWatchedIndex
            }
            focusedEpisodeIndex = scrollIndex
            binding.hgvEpisodes.scrollAndFocus(scrollIndex)
        }
        rewireSeasonHeaderFocus()
        binding.hgvEpisodes.post { rewireSeasonHeaderFocus() }
    }

    /** Header actions ↔ episode HGV (or empty CTA) vertical chain. */
    private fun rewireSeasonHeaderFocus() {
        val ui = _binding ?: return
        val picker = ui.btnSeasonPicker.takeIf { it.isVisible } ?: ui.spSeasonPicker
        TvFocusChain.linkHorizontal(
            picker,
            ui.btnSeasonTrailer,
            ui.btnSeasonDownload,
        )
        val emptyCta = ui.root.findViewById<View>(R.id.btn_season_empty_cta)
        val episodeTarget = when {
            ui.hgvEpisodes.isVisible && ui.hgvEpisodes.childCount > 0 -> {
                val focusedChild = ui.hgvEpisodes.focusedChild
                focusedChild
                    ?: ui.hgvEpisodes
                        .findViewHolderForAdapterPosition(focusedEpisodeIndex ?: 0)
                        ?.itemView
                    ?: ui.hgvEpisodes.getChildAt(0)
                    ?: ui.hgvEpisodes
            }
            emptyCta?.isVisible == true -> emptyCta
            else -> ui.hgvEpisodes.takeIf { it.isVisible }
        }
        TvFocusChain.linkDown(
            listOf(picker, ui.btnSeasonTrailer, ui.btnSeasonDownload),
            episodeTarget,
        )
        episodeTarget?.nextFocusUpId = listOf(
            ui.btnSeasonDownload,
            ui.btnSeasonTrailer,
            picker,
        ).firstOrNull { it.isVisible && it.isFocusable }?.id ?: View.NO_ID
    }

    /** Public handoff for side-nav / destination focus (Leanback). */
    fun requestContentFocus(): Boolean {
        val ui = _binding ?: return false
        if (!ui.hgvEpisodes.isVisible || loadedEpisodes.isEmpty()) return false
        restoreEpisodeFocus()
        return true
    }

    private fun restoreEpisodeFocus() {
        val ui = _binding ?: return
        if (!ui.hgvEpisodes.isVisible || loadedEpisodes.isEmpty()) return
        val index = (focusedEpisodeIndex ?: 0).coerceIn(0, loadedEpisodes.lastIndex)
        focusedEpisodeIndex = index
        ui.hgvEpisodes.scrollAndFocus(index)
        ui.hgvEpisodes.post { rewireSeasonHeaderFocus() }
    }

    private fun RecyclerView.scrollAndFocus(position: Int) {
        scrollToPosition(position)
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                viewTreeObserver.removeOnGlobalLayoutListener(this)
                findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                rewireSeasonHeaderFocus()
            }
        })
    }

}
