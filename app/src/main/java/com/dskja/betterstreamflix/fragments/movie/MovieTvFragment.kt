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
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.FragmentMovieTvBinding
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.ui.DetailContentSignatures
import com.dskja.betterstreamflix.ui.DetailLoadingErrorChrome
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.Http409CacheGuard
import com.dskja.betterstreamflix.utils.TmdbUtils
import com.dskja.betterstreamflix.utils.format
import com.dskja.betterstreamflix.utils.loadMovieBanner
import com.dskja.betterstreamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class MovieTvFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentMovieTvBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<MovieTvFragmentArgs>()
    private val database get() = AppDatabase.getInstance(requireContext())
    private val viewModel by viewModelsFactory { MovieViewModel(args.id, database) }

    private val appAdapter = AppAdapter()
    private var watchFocusedOnce = false
    private var lastContentSignature: List<Any?>? = null
    private var lastBannerKey: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMovieTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeMovie()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    MovieViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is MovieViewModel.State.SuccessLoading -> {
                        displayMovie(state.movie)
                        binding.isLoading.root.visibility = View.GONE
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(
                            binding.isLoading.root, false,
                        )
                        requestWatchFocus()
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
                            requestFocusOnRetry = true,
                            onRetry = { viewModel.getMovie(args.id) },
                        )
                    }
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
        _binding?.let { appAdapter.onSaveInstanceState(it.vgvMovie) }
        lastContentSignature = null
        lastBannerKey = null
        _binding = null
        super.onDestroyView()
    }

    private fun initializeMovie() {
        binding.vgvMovie.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(resources.getDimension(R.dimen.detail_tv_item_spacing).toInt())
        }
    }

    private fun requestWatchFocus() {
        if (watchFocusedOnce) return
        binding.vgvMovie.post {
            if (!isAdded || _binding == null) return@post
            val watch = binding.vgvMovie
                .findViewHolderForAdapterPosition(0)
                ?.itemView
                ?.findViewById<View>(R.id.btn_movie_watch_now)
            if (watch != null) {
                watch.requestFocus()
                watchFocusedOnce = true
            }
        }
    }

    private fun displayMovie(movie: Movie) {
        val signature = DetailContentSignatures.movie(movie)
        if (lastContentSignature == signature) {
            // Favorite-only / list-state refresh: hero CTA already updated in place.
            return
        }
        lastContentSignature = signature

        val bannerKey = "${movie.banner}|${movie.poster}"
        if (lastBannerKey != bannerKey) {
            lastBannerKey = bannerKey
            binding.ivMovieBanner.loadMovieBanner(movie, hero = false) {
                val (w, h) = com.dskja.betterstreamflix.ui.FeaturedSwiperChrome
                    .tvBackdropOverride(requireContext())
                override(w, h)
                if (!DeviceCapabilities.shouldReduceHomeEffects(requireContext())) {
                    transition(DrawableTransitionOptions.withCrossFade())
                } else {
                    this
                }
            }
        }

        appAdapter.submitList(listOfNotNull(
            movie.apply { itemType = AppAdapter.Type.MOVIE_TV },

            movie.takeIf { it.directors.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.MOVIE_DIRECTORS_TV },

            movie.takeIf { it.cast.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.MOVIE_CAST_TV },

            movie.takeIf {
                !it.trailer.isNullOrBlank() ||
                    TmdbUtils.hasTrailerLookupKeys(
                        tmdbId = it.tmdbId,
                        imdbId = it.imdbId,
                        title = it.title,
                        year = it.released?.format("yyyy")?.toIntOrNull(),
                    )
            }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.MOVIE_TRAILER_TV },

            movie.takeIf { it.recommendations.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.MOVIE_RECOMMENDATIONS_TV },
        ))
    }
}
