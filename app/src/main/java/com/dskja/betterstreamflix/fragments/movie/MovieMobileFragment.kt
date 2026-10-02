package com.dskja.betterstreamflix.fragments.movie

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
import com.dskja.betterstreamflix.databinding.FragmentMovieMobileBinding
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.ui.DetailHeaderController
import com.dskja.betterstreamflix.ui.DetailLoadingErrorChrome
import com.dskja.betterstreamflix.ui.DetailTab
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.utils.ExpNavAutoHide
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.Http409CacheGuard
import com.dskja.betterstreamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class MovieMobileFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentMovieMobileBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<MovieMobileFragmentArgs>()
    private val database get() = AppDatabase.getInstance(requireContext())
    private val viewModel by viewModelsFactory { MovieViewModel(args.id, database) }

    private val appAdapter = AppAdapter()
    private var currentMovie: Movie? = null
    private var selectedTab: DetailTab = DetailTab.SIMILAR

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMovieMobileBinding.bind(
            inflater.inflate(
                R.layout.fragment_movie_mobile,
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
        if (ExperimentalMobileDesign.enabled()) {
            ExpMotion.staggerFirstFill(binding.rvMovie)
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

        initializeMovie()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    MovieViewModel.State.Loading -> binding.isLoading.apply {
                        ExpMotion.fadeInAndShow(root)
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is MovieViewModel.State.SuccessLoading -> {
                        displayMovie(state.movie)
                        ExpMotion.fadeOutAndHide(binding.isLoading.root)
                    }
                    is MovieViewModel.State.FailedLoading -> {
                        if (http409Guard.handle(requireContext(), state.error) {
                                viewModel.getMovie(args.id)
                            }
                        ) {
                            return@collect
                        }
                        DetailLoadingErrorChrome.bind(
                            root = binding.isLoading.root,
                            context = requireContext(),
                            error = state.error,
                            showToast = !ExperimentalMobileDesign.enabled(),
                            onRetry = { viewModel.getMovie(args.id) },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding?.let { appAdapter.onSaveInstanceState(it.rvMovie) }
        _binding = null
        super.onDestroyView()
    }


    private var detailScrollOffset = 0

    private fun initializeMovie() {
        detailScrollOffset = 0
        DetailHeaderController.onScrolled(binding.root, 0)
        binding.rvMovie.apply {
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
    private fun contentSignature(movie: Movie) = listOf(
        movie.id,
        movie.title,
        movie.overview,
        movie.poster,
        movie.banner,
        // logo intentionally omitted — async LogoPersist must not reset detail tabs
        movie.trailer,
        movie.quality,
        movie.rating,
        movie.runtime,
        movie.isWatched,
        movie.watchHistory,
        movie.genres.size,
        movie.directors.size,
        movie.cast.size,
        movie.recommendations.size,
    )

    private var lastContentSignature: List<Any?>? = null

    private fun displayMovie(movie: Movie) {
        currentMovie = movie
        prefetchLogo(movie.logo)
        DetailHeaderController.wireCast(this, binding.root)

        val signature = contentSignature(movie)
        if (lastContentSignature == signature) {
            // Favorite-only / list-state refresh: keep the current list to avoid scroll jump.
            // Hero My List CTA already updates in place from the ViewHolder click path.
            binding.rvMovie.post {
                if (!isAdded) return@post
                DetailHeaderController.bindMovie(this, binding.root, movie)
            }
            return
        }
        lastContentSignature = signature
        selectedTab = if (movie.recommendations.isNotEmpty()) DetailTab.SIMILAR else DetailTab.ABOUT
        appAdapter.selectedDetailTab = selectedTab
        rebuildBody(scrollTabsToTop = false)
        // Bind after body submit so the hero ImageView exists in the RecyclerView item.
        binding.rvMovie.post {
            if (!isAdded) return@post
            DetailHeaderController.bindMovie(this, binding.root, movie)
        }
    }

    private fun prefetchLogo(logoUrl: String?) {
        com.dskja.betterstreamflix.logo.TmdbLogoGlide.prefetch(
            context = requireContext(),
            logoUrl = logoUrl,
            wifiOnly = true,
        )
    }

    private fun rebuildBody(scrollTabsToTop: Boolean) {
        val movie = currentMovie ?: return
        val body: List<AppAdapter.Item> = when (selectedTab) {
            DetailTab.SIMILAR -> listOf(
                movie.copy().apply { itemType = AppAdapter.Type.MOVIE_RECOMMENDATIONS_MOBILE },
            )
            DetailTab.TRAILER -> listOf(
                movie.copy().apply { itemType = AppAdapter.Type.MOVIE_TRAILER_MOBILE },
            )
            DetailTab.ABOUT -> aboutBody(movie)
            DetailTab.EPISODES -> emptyList()
        }
        appAdapter.submitList(
            listOfNotNull(
                movie.apply { itemType = AppAdapter.Type.MOVIE_MOBILE },
                movie.copy().apply { itemType = AppAdapter.Type.MOVIE_TABS_MOBILE },
            ) + body,
        )
        if (scrollTabsToTop) {
            binding.rvMovie.post { binding.rvMovie.smoothScrollToPosition(1) }
        }
    }

    private fun aboutBody(movie: Movie): List<AppAdapter.Item> = buildList {
        add(movie.copy().apply { itemType = AppAdapter.Type.MOVIE_ABOUT_MOBILE })
        if (movie.directors.isNotEmpty()) {
            add(movie.copy().apply { itemType = AppAdapter.Type.MOVIE_DIRECTORS_MOBILE })
        }
        if (movie.cast.isNotEmpty()) {
            add(movie.copy().apply { itemType = AppAdapter.Type.MOVIE_CAST_MOBILE })
        }
    }
}
