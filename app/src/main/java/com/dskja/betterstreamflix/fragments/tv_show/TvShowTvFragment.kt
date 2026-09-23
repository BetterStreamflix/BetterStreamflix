package com.dskja.betterstreamflix.fragments.tv_show

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
import com.dskja.betterstreamflix.databinding.FragmentTvShowTvBinding
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.Http409CacheGuard
import com.dskja.betterstreamflix.utils.LoggingUtils
import com.dskja.betterstreamflix.utils.TmdbUtils
import com.dskja.betterstreamflix.utils.format
import com.dskja.betterstreamflix.utils.loadTvShowBanner
import com.dskja.betterstreamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class TvShowTvFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentTvShowTvBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<TvShowTvFragmentArgs>()
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTvShowTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeTvShow()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    TvShowViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is TvShowViewModel.State.SuccessLoading -> {
                        displayTvShow(state.tvShow)
                        binding.isLoading.root.visibility = View.GONE
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(
                            binding.isLoading.root, false,
                        )
                    }
                    is TvShowViewModel.State.FailedLoading -> {
                        if (http409Guard.handle(requireContext(), state.error) { viewModel.getTvShow(args.id) }) {
                                return@collect
                            }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.isLoading.apply {
                            com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, false)
                            gIsLoadingRetry.visibility = View.VISIBLE
                            com.dskja.betterstreamflix.utils.ExpPressEffects.animateLoadingError(root)
                            btnIsLoadingRetry.setOnClickListener { viewModel.getTvShow(args.id) }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(requireContext(), getString(com.dskja.betterstreamflix.R.string.clear_cache_done), com.dskja.betterstreamflix.R.string.loading_error_clear_cache)
                                viewModel.getTvShow(args.id)
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
                            btnIsLoadingRetry.requestFocus()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding?.let { appAdapter.onSaveInstanceState(it.vgvTvShow) }
        _binding = null
        super.onDestroyView()
    }

    private fun initializeTvShow() {
        binding.vgvTvShow.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(resources.getDimension(R.dimen.detail_tv_item_spacing).toInt())
        }
    }

    private fun displayTvShow(tvShow: TvShow) {
        binding.ivTvShowBanner.loadTvShowBanner(tvShow) {
            if (!DeviceCapabilities.shouldReduceHomeEffects(requireContext())) {
                transition(DrawableTransitionOptions.withCrossFade())
            } else {
                this
            }
        }

        appAdapter.submitList(listOfNotNull(
            tvShow.apply { itemType = AppAdapter.Type.TV_SHOW_TV },

            tvShow.takeIf { it.seasons.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_SEASONS_TV },

            tvShow.takeIf { it.directors.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_DIRECTORS_TV },

            tvShow.takeIf { it.cast.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_CAST_TV },

            tvShow.takeIf {
                !it.trailer.isNullOrBlank() ||
                    TmdbUtils.hasTrailerLookupKeys(
                        tmdbId = it.tmdbId,
                        imdbId = it.imdbId,
                        title = it.title,
                        year = it.released?.format("yyyy")?.toIntOrNull(),
                    )
            }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_TRAILER_TV },

            tvShow.takeIf { it.recommendations.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_RECOMMENDATIONS_TV },
        ))
    }
}
