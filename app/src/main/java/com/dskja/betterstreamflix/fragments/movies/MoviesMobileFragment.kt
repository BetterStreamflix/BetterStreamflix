package com.dskja.betterstreamflix.fragments.movies

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
import androidx.navigation.fragment.findNavController
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.FragmentMoviesMobileBinding
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.viewModelsFactory
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.Http409CacheGuard
import com.dskja.betterstreamflix.utils.ExpNavAutoHide
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import kotlinx.coroutines.launch

class MoviesMobileFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentMoviesMobileBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { MoviesViewModel(database) }

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layoutRes = ExperimentalMobileDesign.layout(
            R.layout.fragment_movies_mobile,
            R.layout.fragment_movies_mobile_exp,
        )
        val root = inflater.inflate(layoutRes, container, false)
        _binding = FragmentMoviesMobileBinding.bind(root)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ExpNavAutoHide.attach(binding.root)
        com.dskja.betterstreamflix.utils.ExpPressEffects.wireLoadingRetry(binding.isLoading.root)
        ExpMotion.enterScreen(binding.root)
        ExperimentalMobileDesign.applyReducedGlass(binding.root)
        ExpMotion.staggerFirstFill(binding.rvMovies)
        if (ExperimentalMobileDesign.enabled()) {
            ExpMotion.startAnimation(binding.rvMovies, R.anim.exp_fade_slide_in)
            ExpMotion.revealHeader(
                binding.root.findViewById(R.id.tv_movies_eyebrow),
                binding.root.findViewById(R.id.tv_movies_brand),
                binding.root.findViewById(R.id.tv_movies_tagline),
                binding.root.findViewById(R.id.v_movies_rule),
            )
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_movies_rule))
            wireCatalogSortChip(
                binding.root.findViewById(R.id.tv_movies_sort_chip),
                onChanged = { viewModel.getMovies() },
            )
        }

        initializeMovies()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    MoviesViewModel.State.Loading -> binding.isLoading.apply {
                        ExpMotion.fadeInAndShow(root)
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    MoviesViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is MoviesViewModel.State.SuccessLoading -> {
                        displayMovies(state.movies, state.hasMore)
                        appAdapter.isLoading = false
                        ExpMotion.fadeOutAndHide(binding.isLoading.root)
                    }
                    is MoviesViewModel.State.FailedLoading -> {
                        if (http409Guard.handle(requireContext(), state.error) { viewModel.getMovies() }) {
                                return@collect
                            }
                        if (!ExperimentalMobileDesign.enabled()) {
                            Toast.makeText(
                                requireContext(),
                                state.error.message ?: "",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, false)
                                gIsLoadingRetry.visibility = View.VISIBLE
                                com.dskja.betterstreamflix.utils.ExpPressEffects.animateLoadingError(root)
                                val doRetry = { viewModel.getMovies() }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(requireContext(), getString(com.dskja.betterstreamflix.R.string.clear_cache_done), com.dskja.betterstreamflix.R.string.loading_error_clear_cache)
                                    doRetry()
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    com.dskja.betterstreamflix.utils.LoggingUtils.showErrorDialog(requireContext(), state.error)
                                }
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


    private fun initializeMovies() {
        binding.rvMovies.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(10.dp(requireContext()))
            )
        }
    }

    private fun wireCatalogSortChip(chip: android.widget.TextView?, onChanged: () -> Unit) {
        chip ?: return
        fun refreshLabel() {
            chip.setText(
                when (UserPreferences.catalogSortMode) {
                    com.dskja.betterstreamflix.utils.CatalogSortMode.LAST_RELEASE ->
                        R.string.catalog_sort_last_release
                    else -> R.string.catalog_sort_default
                }
            )
            val selected =
                UserPreferences.catalogSortMode ==
                    com.dskja.betterstreamflix.utils.CatalogSortMode.LAST_RELEASE
            chip.isSelected = selected
            if (ExperimentalMobileDesign.enabled()) {
                chip.setBackgroundResource(
                    if (selected) ExperimentalMobileDesign.primaryButtonBackground()
                    else ExperimentalMobileDesign.chipBackground(),
                )
                chip.setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        chip,
                        if (selected) com.google.android.material.R.attr.colorOnPrimary
                        else com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ),
                )
                chip.refreshDrawableState()
                if (selected) ExpMotion.popIn(chip)
            }
        }
        refreshLabel()
        with(com.dskja.betterstreamflix.utils.ExpPressEffects) { chip.applyExpPress() }
        ExpMotion.popIn(chip)
        chip.setOnClickListener {
            ExpMotion.hapticTap(it)
            UserPreferences.catalogSortMode =
                when (UserPreferences.catalogSortMode) {
                    com.dskja.betterstreamflix.utils.CatalogSortMode.DEFAULT ->
                        com.dskja.betterstreamflix.utils.CatalogSortMode.LAST_RELEASE
                    else -> com.dskja.betterstreamflix.utils.CatalogSortMode.DEFAULT
                }
            refreshLabel()
            ExpMotion.popIn(chip)
            onChanged()
        }
    }

    private fun displayMovies(movies: List<Movie>, hasMore: Boolean) {
        appAdapter.submitList(movies.onEach {
            it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
        })

        val empty = movies.isEmpty()
        ExpEmptyChrome.bind(
            emptyView = binding.root.findViewById(R.id.tv_movies_empty),
            emptyRule = binding.root.findViewById(R.id.v_movies_empty_rule),
            emptyCta = binding.root.findViewById(R.id.btn_movies_empty_cta),
            visible = empty,
            tintOnSurfaceVariant = false,
            onCtaClick = { findNavController().navigate(R.id.providers) },
        )
        binding.rvMovies.visibility = if (empty) View.GONE else View.VISIBLE

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMoreMovies() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }
}