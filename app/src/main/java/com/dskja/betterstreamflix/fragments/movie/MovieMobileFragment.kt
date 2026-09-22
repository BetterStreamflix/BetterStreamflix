package com.dskja.betterstreamflix.fragments.movie

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.FragmentMovieMobileBinding
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.ui.DetailHeaderController
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.ExpNavAutoHide
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.LoggingUtils
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.loadMovieBanner
import com.dskja.betterstreamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class MovieMobileFragment : Fragment() {

    private var _binding: FragmentMovieMobileBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<MovieMobileFragmentArgs>()
    private val database get() = AppDatabase.getInstance(requireContext())
    private val viewModel by viewModelsFactory { MovieViewModel(args.id, database) }

    private val appAdapter = AppAdapter()

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
            ExpMotion.kenBurns(
                binding.ivMovieBanner,
                drift = !ExperimentalMobileDesign.heroParallax(),
            )
        }
        DetailHeaderController.wireBack(binding.root)

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
                                val doRetry = { viewModel.getMovie(args.id) }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
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
                SpacingItemDecoration(20.dp(requireContext()))
            )
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    detailScrollOffset = (detailScrollOffset + dy).coerceAtLeast(0)
                    DetailHeaderController.onScrolled(binding.root, detailScrollOffset)
                    if (ExperimentalMobileDesign.enabled()) {
                        val parallax = (detailScrollOffset * 0.28f).coerceIn(0f, 420f)
                        binding.ivMovieBanner.translationY = -parallax
                    }
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
        movie.logo,
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
        binding.ivMovieBanner.loadMovieBanner(movie, hero = false) {
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
        DetailHeaderController.bindMovie(this, binding.root, movie)

        val signature = contentSignature(movie)
        if (lastContentSignature == signature) {
            DetailHeaderController.refreshListState(binding.root, movie.isFavorite)
            return
        }
        lastContentSignature = signature

        appAdapter.submitList(listOfNotNull(
            movie.apply { itemType = AppAdapter.Type.MOVIE_MOBILE },
            movie.copy().apply { itemType = AppAdapter.Type.MOVIE_TABS_MOBILE },
            movie.takeIf { it.recommendations.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.MOVIE_RECOMMENDATIONS_MOBILE },
            movie.copy().apply { itemType = AppAdapter.Type.MOVIE_TRAILER_MOBILE },
            movie.copy().apply { itemType = AppAdapter.Type.MOVIE_ABOUT_MOBILE },
        ))
    }
}
