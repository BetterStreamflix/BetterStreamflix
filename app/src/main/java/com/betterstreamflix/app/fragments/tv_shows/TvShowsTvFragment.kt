package com.betterstreamflix.app.fragments.tv_shows

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
import com.betterstreamflix.app.databinding.FragmentTvShowsTvBinding
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.ui.HomeProfileChip
import com.betterstreamflix.app.utils.UserPreferences
import com.betterstreamflix.app.utils.CacheUtils
import com.betterstreamflix.app.utils.ExpEmptyChrome
import com.betterstreamflix.app.utils.Http409CacheGuard
import com.betterstreamflix.app.utils.viewModelsFactory
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch

class TvShowsTvFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentTvShowsTvBinding? = null
    private val binding get() = _binding!!

    private val database get() = AppDatabase.getInstanceOrNull(requireContext())
    private val viewModel by viewModelsFactory { TvShowsViewModel(database) }

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTvShowsTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeTvShows()
        HomeProfileChip.refresh(this, binding.root, showName = true)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    TvShowsViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        com.betterstreamflix.app.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    TvShowsViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is TvShowsViewModel.State.SuccessLoading -> {
                        displayTvShows(state.tvShows, state.hasMore)
                        appAdapter.isLoading = false
                        binding.vgvTvShows.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                        com.betterstreamflix.app.utils.ExpPressEffects.showLoadingSkeleton(
                            binding.isLoading.root, false,
                        )
                    }
                    is TvShowsViewModel.State.FailedLoading -> {
                        if (http409Guard.handle(requireContext(), state.error) {
                                if (appAdapter.isLoading) appAdapter.isLoading = false
                                                            viewModel.getTvShows()
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
                                btnIsLoadingRetry.setOnClickListener { viewModel.getTvShows() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    com.betterstreamflix.app.utils.ExpDialogChrome.notify(requireContext(), getString(com.betterstreamflix.app.R.string.clear_cache_done), com.betterstreamflix.app.R.string.loading_error_clear_cache)
                                    viewModel.getTvShows()
                                }
                                binding.vgvTvShows.visibility = View.GONE
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

    private fun initializeTvShows() {
        binding.vgvTvShows.apply {
            setNumColumns(com.betterstreamflix.app.ui.LeanbackDensityPolicy.posterGridColumns())
            setItemSpacing(
                com.betterstreamflix.app.ui.LeanbackDensityPolicy.dpToPx(
                    this,
                    com.betterstreamflix.app.ui.LeanbackDensityPolicy.catalogItemSpacingDp(),
                ),
            )
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
        }

        binding.root.requestFocus()
    }

    private fun displayTvShows(tvShows: List<TvShow>, hasMore: Boolean) {
        appAdapter.submitList(tvShows.onEach {
            it.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
        })

        val empty = tvShows.isEmpty()
        binding.tvTvShowsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.vgvTvShows.visibility = if (empty) View.GONE else View.VISIBLE
        ExpEmptyChrome.bind(
            emptyView = binding.tvTvShowsEmpty,
            emptyRule = binding.root.findViewById(R.id.v_tv_shows_empty_rule),
            emptyCta = binding.root.findViewById(R.id.btn_tv_shows_empty_cta),
            visible = empty,
            onCtaClick = { runCatching { findNavController().navigate(R.id.providers) } },
        )

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMoreTvShows() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }
}