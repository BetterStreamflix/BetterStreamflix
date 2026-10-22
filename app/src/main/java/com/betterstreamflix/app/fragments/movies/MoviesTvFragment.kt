package com.betterstreamflix.app.fragments.movies

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.betterstreamflix.app.R
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.databinding.FragmentMoviesTvBinding
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.ui.HomeProfileChip
import com.betterstreamflix.app.utils.UserPreferences
import com.betterstreamflix.app.utils.CacheUtils
import com.betterstreamflix.app.utils.ExpEmptyChrome
import com.betterstreamflix.app.utils.Http409CacheGuard
import com.betterstreamflix.app.utils.viewModelsFactory
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch

class MoviesTvFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentMoviesTvBinding? = null
    private val binding get() = _binding!!

    private val database get() = AppDatabase.getInstanceOrNull(requireContext())
    private val viewModel by viewModelsFactory { MoviesViewModel(database) }

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoviesTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeMovies()
        HomeProfileChip.refresh(this, binding.root, showName = true)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    MoviesViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        com.betterstreamflix.app.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    MoviesViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is MoviesViewModel.State.SuccessLoading -> {
                        displayMovies(state.movies, state.hasMore)
                        appAdapter.isLoading = false
                        binding.vgvMovies.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                        com.betterstreamflix.app.utils.ExpPressEffects.showLoadingSkeleton(
                            binding.isLoading.root, false,
                        )
                    }
                    is MoviesViewModel.State.FailedLoading -> {
                        if (http409Guard.handle(requireContext(), state.error) {
                                if (appAdapter.isLoading) appAdapter.isLoading = false
                                                            viewModel.getMovies()
                            }) {
                                return@collect
                            }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                com.betterstreamflix.app.utils.ExpPressEffects.showLoadingSkeleton(root, false)
                                gIsLoadingRetry.visibility = View.VISIBLE
                                btnIsLoadingRetry.setOnClickListener { viewModel.getMovies() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    com.betterstreamflix.app.utils.ExpDialogChrome.notify(requireContext(), getString(com.betterstreamflix.app.R.string.clear_cache_done), com.betterstreamflix.app.R.string.loading_error_clear_cache)
                                    viewModel.getMovies()
                                }
                                binding.vgvMovies.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        HomeProfileChip.refresh(this, _binding?.root, showName = true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun initializeMovies() {
        binding.vgvMovies.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setNumColumns(com.betterstreamflix.app.ui.LeanbackDensityPolicy.posterGridColumns())
            setItemSpacing(
                com.betterstreamflix.app.ui.LeanbackDensityPolicy.dpToPx(
                    this,
                    com.betterstreamflix.app.ui.LeanbackDensityPolicy.catalogItemSpacingDp(),
                ),
            )
        }

        binding.root.requestFocus()
    }

    private fun displayMovies(movies: List<Movie>, hasMore: Boolean) {
        appAdapter.submitList(movies.onEach {
            it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
        })

        val empty = movies.isEmpty()
        binding.tvMoviesEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.vgvMovies.visibility = if (empty) View.GONE else View.VISIBLE
        ExpEmptyChrome.bind(
            emptyView = binding.tvMoviesEmpty,
            emptyRule = binding.root.findViewById(R.id.v_movies_empty_rule),
            emptyCta = binding.root.findViewById(R.id.btn_movies_empty_cta),
            visible = empty,
            onCtaClick = { runCatching { findNavController().navigate(R.id.providers) } },
        )

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMoreMovies() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }
}