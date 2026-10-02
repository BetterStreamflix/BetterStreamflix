package com.dskja.betterstreamflix.fragments.tv_show

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.FragmentTvShowMobileBinding
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.ui.DetailHeaderController
import com.dskja.betterstreamflix.ui.DetailLoadingErrorChrome
import com.dskja.betterstreamflix.ui.DetailTab
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.utils.Http409CacheGuard
import com.dskja.betterstreamflix.utils.ExpNavAutoHide
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class TvShowMobileFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentTvShowMobileBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<TvShowMobileFragmentArgs>()
    private val database get() = AppDatabase.getInstance(requireContext())
    private val viewModel by viewModelsFactory {
        TvShowViewModel(
            id = args.id,
            database = database,
            fallbackPoster = args.poster,
            fallbackBanner = args.banner,
        )
    }

    private val appAdapter = AppAdapter()
    private var currentTvShow: TvShow? = null
    private var selectedTab: DetailTab = DetailTab.EPISODES

    /** Season episode load UI state for the in-page Episodes tab. */
    fun seasonEpisodeUi(): TvShowViewModel = viewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layoutId = R.layout.fragment_tv_show_mobile
        val view = inflater.inflate(layoutId, container, false)
        _binding = FragmentTvShowMobileBinding.bind(view)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ExpNavAutoHide.attach(binding.root)
        com.dskja.betterstreamflix.utils.ExpPressEffects.wireLoadingRetry(binding.isLoading.root)
        ExpMotion.enterScreen(binding.root)
        ExperimentalMobileDesign.applyReducedGlass(binding.root)
        if (ExperimentalMobileDesign.enabled()) {
            ExpMotion.staggerFirstFill(binding.rvTvShow)
        }
        DetailHeaderController.wireBack(binding.root)
        DetailHeaderController.wireCast(this, binding.root)
        appAdapter.onDetailTabSelectedListener = { tab ->
            if (selectedTab != tab) {
                selectedTab = tab
                appAdapter.selectedDetailTab = tab
                rebuildBody(scrollTabsToTop = true)
            }
        }
        appAdapter.selectedDetailTab = selectedTab

        initializeTvShow()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    TvShowViewModel.State.Loading -> binding.isLoading.apply {
                        ExpMotion.fadeInAndShow(root)
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is TvShowViewModel.State.SuccessLoading -> {
                        displayTvShow(state.tvShow)
                        ExpMotion.fadeOutAndHide(binding.isLoading.root)
                    }
                    is TvShowViewModel.State.FailedLoading -> {
                        if (http409Guard.handle(requireContext(), state.error) { viewModel.getTvShow(args.id) }) {
                                return@collect
                            }
                        DetailLoadingErrorChrome.bind(
                            root = binding.isLoading.root,
                            context = requireContext(),
                            error = state.error,
                            showToast = !ExperimentalMobileDesign.enabled(),
                            onRetry = { viewModel.getTvShow(args.id) },
                        )
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.seasonState.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { seasonState ->
                when (seasonState) {
                    is TvShowViewModel.SeasonState.SuccessLoading -> {
                        val tvShow = currentTvShow ?: return@collect
                        tvShow.seasons.firstOrNull { it.id == seasonState.season.id }
                            ?.episodes = seasonState.episodes
                        seasonState.season.episodes = seasonState.episodes
                        // Only rebuild the episodes body — skip full list churn on other tabs.
                        if (selectedTab == DetailTab.EPISODES) {
                            rebuildBody(scrollTabsToTop = false)
                        }
                    }
                    is TvShowViewModel.SeasonState.FailedLoading -> {
                        if (selectedTab == DetailTab.EPISODES) {
                            rebuildBody(scrollTabsToTop = false)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onPause() {
        com.dskja.betterstreamflix.ui.TrailerPlaybackController.silenceAllActive()
        super.onPause()
    }

    override fun onDestroyView() {
        com.dskja.betterstreamflix.ui.TrailerPlaybackController.silenceAllActive()
        _binding?.let { appAdapter.onSaveInstanceState(it.rvTvShow) }
        _binding = null
        super.onDestroyView()
    }


    private var detailScrollOffset = 0

    private fun initializeTvShow() {
        detailScrollOffset = 0
        DetailHeaderController.onScrolled(binding.root, 0)
        binding.rvTvShow.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(0),
            )
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    detailScrollOffset = (detailScrollOffset + dy).coerceAtLeast(0)
                    DetailHeaderController.onScrolled(binding.root, detailScrollOffset)
                }
            })
        }
    }

    /**
     * Everything the detail list renders, except the list/favorite state. When only that
     * changes we refresh the header icon instead of re-submitting and jumping the page.
     */
    private fun contentSignature(tvShow: TvShow) = listOf(
        tvShow.id,
        tvShow.title,
        tvShow.overview,
        tvShow.poster,
        tvShow.banner,
        // logo intentionally omitted — async LogoPersist must not reset detail tabs
        tvShow.trailer,
        tvShow.quality,
        tvShow.rating,
        tvShow.runtime,
        tvShow.lastPlayedEpisodeId,
        tvShow.seasons.size,
        tvShow.seasons.sumOf { it.episodes.size },
        tvShow.seasons.map { "${it.id}:${it.episodes.size}" },
        tvShow.genres.size,
        tvShow.directors.size,
        tvShow.cast.size,
        tvShow.recommendations.size,
    )

    private var lastContentSignature: List<Any?>? = null

    private fun displayTvShow(tvShow: TvShow) {
        currentTvShow = tvShow
        prefetchLogo(tvShow.logo)
        DetailHeaderController.wireCast(this, binding.root)

        val signature = contentSignature(tvShow)
        if (lastContentSignature == signature) {
            // Favorite-only / list-state refresh: keep the current list to avoid scroll jump.
            binding.rvTvShow.post {
                if (!isAdded) return@post
                DetailHeaderController.bindTvShow(this, binding.root, tvShow)
            }
            return
        }
        lastContentSignature = signature
        selectedTab = when {
            tvShow.seasons.isNotEmpty() -> DetailTab.EPISODES
            tvShow.recommendations.isNotEmpty() -> DetailTab.SIMILAR
            else -> DetailTab.ABOUT
        }
        appAdapter.selectedDetailTab = selectedTab
        rebuildBody(scrollTabsToTop = false)
        // Bind after body submit so the hero ImageView exists in the RecyclerView item.
        binding.rvTvShow.post {
            if (!isAdded) return@post
            DetailHeaderController.bindTvShow(this, binding.root, tvShow)
        }
    }

    private fun prefetchLogo(logoUrl: String?) {
        com.dskja.betterstreamflix.logo.TmdbLogoGlide.prefetch(
            context = requireContext(),
            logoUrl = logoUrl,
            wifiOnly = true,
        )
    }

    fun loadSeasonEpisodes(season: Season) {
        val tvShow = currentTvShow ?: return
        viewModel.loadSeasonEpisodes(tvShow, season)
    }

    private fun rebuildBody(scrollTabsToTop: Boolean) {
        val tvShow = currentTvShow ?: return
        if (selectedTab != DetailTab.TRAILER) {
            com.dskja.betterstreamflix.ui.TrailerPlaybackController.silenceAllActive()
        }
        val body: List<AppAdapter.Item> = when (selectedTab) {
            DetailTab.EPISODES -> listOfNotNull(
                tvShow.takeIf { it.seasons.isNotEmpty() }
                    ?.copy()
                    ?.apply { itemType = AppAdapter.Type.TV_SHOW_SEASONS_MOBILE },
            )
            DetailTab.SIMILAR -> listOf(
                tvShow.copy().apply { itemType = AppAdapter.Type.TV_SHOW_RECOMMENDATIONS_MOBILE },
            )
            DetailTab.TRAILER -> listOf(
                tvShow.copy().apply { itemType = AppAdapter.Type.TV_SHOW_TRAILER_MOBILE },
            )
            DetailTab.ABOUT -> aboutBody(tvShow)
        }
        appAdapter.submitList(
            listOfNotNull(
                tvShow.apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE },
                tvShow.copy().apply { itemType = AppAdapter.Type.TV_SHOW_TABS_MOBILE },
            ) + body,
        )
        if (scrollTabsToTop) {
            binding.rvTvShow.post { binding.rvTvShow.smoothScrollToPosition(1) }
        }
    }

    private fun aboutBody(tvShow: TvShow): List<AppAdapter.Item> = buildList {
        add(tvShow.copy().apply { itemType = AppAdapter.Type.TV_SHOW_ABOUT_MOBILE })
        if (tvShow.directors.isNotEmpty()) {
            add(tvShow.copy().apply { itemType = AppAdapter.Type.TV_SHOW_DIRECTORS_MOBILE })
        }
        if (tvShow.cast.isNotEmpty()) {
            add(tvShow.copy().apply { itemType = AppAdapter.Type.TV_SHOW_CAST_MOBILE })
        }
    }
}
