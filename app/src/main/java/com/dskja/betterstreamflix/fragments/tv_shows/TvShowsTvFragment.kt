package com.dskja.betterstreamflix.fragments.tv_shows

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
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.FragmentTvShowsTvBinding
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.Http409CacheGuard
import com.dskja.betterstreamflix.utils.viewModelsFactory
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch

class TvShowsTvFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentTvShowsTvBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    TvShowsViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    TvShowsViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is TvShowsViewModel.State.SuccessLoading -> {
                        displayTvShows(state.tvShows, state.hasMore)
                        appAdapter.isLoading = false
                        binding.vgvTvShows.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(
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
                        if (!com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled()) {
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
                                btnIsLoadingRetry.setOnClickListener { viewModel.getTvShows() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(requireContext(), getString(com.dskja.betterstreamflix.R.string.clear_cache_done), com.dskja.betterstreamflix.R.string.loading_error_clear_cache)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    private fun initializeTvShows() {
        com.dskja.betterstreamflix.utils.ExpPressEffects.wireLoadingRetry(binding.isLoading.root)
        if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled()) {
            com.dskja.betterstreamflix.utils.ExpMotion.enterScreen(binding.root)
            binding.tvTvShowsEmpty.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.tvTvShowsEmpty,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                ),
            )
        }
        binding.vgvTvShows.apply {
            val spacing = requireContext().resources.getDimension(R.dimen.tv_shows_spacing).toInt()
            setItemSpacing(spacing)
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