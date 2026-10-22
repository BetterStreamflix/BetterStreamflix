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
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.FragmentTvShowTvBinding
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.ui.DetailContentSignatures
import com.dskja.betterstreamflix.ui.DetailLoadingErrorChrome
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.Http409CacheGuard
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
    private val database get() = AppDatabase.getInstanceOrNull(requireContext())
    private val viewModel by viewModelsFactory {
        TvShowViewModel(
            id = args.id,
            database = database,
            fallbackPoster = args.poster,
            fallbackBanner = args.banner,
        )
    }

    private val appAdapter = AppAdapter()
    private var watchFocusedOnce = false
    private var lastContentSignature: List<Any?>? = null
    private var lastBannerKey: String? = null

    /** Vertical scroll state kept across view recreation (back-nav). */
    private var detailListState: android.os.Parcelable? = null
    /** True after a back-nav restore — suppresses the pos-0 watch-focus jump. */
    private var detailScrollRestored = false

    private companion object {
        const val KEY_DETAIL_LIST_STATE = "detail_list_state"
    }

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
        detailListState = detailListState ?: savedInstanceState?.let {
            androidx.core.os.BundleCompat.getParcelable(
                it, KEY_DETAIL_LIST_STATE, android.os.Parcelable::class.java,
            )
        }

        initializeTvShow()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    TvShowViewModel.State.Loading -> {
                        com.dskja.betterstreamflix.utils.TvLoadingOverlay.show(binding.isLoading.root)
                        binding.isLoading.gIsLoadingRetry.visibility = View.GONE
                    }
                    is TvShowViewModel.State.SuccessLoading -> {
                        displayTvShow(state.tvShow)
                        com.dskja.betterstreamflix.utils.TvLoadingOverlay.hide(binding.isLoading.root)
                        requestWatchFocus()
                        viewModel.prefetchSeasonEpisodesForRibbons(state.tvShow)
                    }
                    is TvShowViewModel.State.FailedLoading -> {
                        if (http409Guard.handle(requireContext(), state.error) { viewModel.getTvShow(args.id) }) {
                                return@collect
                            }
                        com.dskja.betterstreamflix.utils.TvLoadingOverlay.disarm(binding.isLoading.root)
                        DetailLoadingErrorChrome.bind(
                            root = binding.isLoading.root,
                            context = requireContext(),
                            error = state.error,
                            requestFocusOnRetry = true,
                            onRetry = { viewModel.getTvShow(args.id) },
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
        com.dskja.betterstreamflix.ui.TrailerPlayPipeline.endSession()
        com.dskja.betterstreamflix.ui.TrailerStreamCrossfade.clear()
        com.dskja.betterstreamflix.player.PlayerCleanExit.clearHomeRestore()
        _binding?.let {
            appAdapter.onSaveInstanceState(it.vgvTvShow)
            detailListState = it.vgvTvShow.layoutManager?.onSaveInstanceState()
        }
        lastContentSignature = null
        lastBannerKey = null
        _binding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val state = detailListState ?: _binding?.vgvTvShow?.layoutManager?.onSaveInstanceState()
        if (state != null) outState.putParcelable(KEY_DETAIL_LIST_STATE, state)
    }

    /** Restores the pre-navigation scroll offset once and parks the pos-0 focus jump. */
    private fun restoreDetailScrollIfPending() {
        val state = detailListState ?: return
        detailListState = null
        detailScrollRestored = true
        binding.vgvTvShow.post {
            _binding?.vgvTvShow?.layoutManager?.onRestoreInstanceState(state)
        }
    }

    private fun initializeTvShow() {
        binding.vgvTvShow.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(resources.getDimension(R.dimen.detail_tv_item_spacing).toInt())
        }
    }

    /** Public handoff for side-nav / destination focus (Leanback). */
    fun requestContentFocus(): Boolean {
        val grid = _binding?.vgvTvShow ?: return false
        if (!grid.isAttachedToWindow || (grid.adapter?.itemCount ?: 0) == 0) return false
        requestWatchFocus(force = true)
        return true
    }

    private fun requestWatchFocus(force: Boolean = false) {
        binding.vgvTvShow.post {
            if (!isAdded || _binding == null) return@post
            val watch = binding.vgvTvShow
                .findViewHolderForAdapterPosition(0)
                ?.itemView
                ?.findViewById<View>(R.id.btn_tv_show_watch_now)
                ?: return@post
            // After a progress refresh the list is rebuilt and focus is gone.
            // Keep an existing focus (seasons, cast) where the user left it.
            if (!force && detailScrollRestored) return@post
            if (!force && watchFocusedOnce && binding.vgvTvShow.findFocus() != null) return@post
            watch.requestFocus()
            watchFocusedOnce = true
        }
    }

    private fun displayTvShow(tvShow: TvShow) {
        restoreDetailScrollIfPending()
        val signature = DetailContentSignatures.tvShow(tvShow)
        if (lastContentSignature == signature) {
            // Favorite / watching-only refresh: hero CTA already updated in place.
            return
        }
        lastContentSignature = signature

        val bannerKey = "${tvShow.banner}|${tvShow.poster}"
        if (lastBannerKey != bannerKey) {
            lastBannerKey = bannerKey
            binding.ivTvShowBanner.loadTvShowBanner(tvShow, hero = false) {
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

        val items = listOfNotNull(
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
        )
        appAdapter.submitList(items)
        if (args.openTab.equals("similar", ignoreCase = true)) {
            val similarIndex = items.indexOfFirst {
                it.itemType == AppAdapter.Type.TV_SHOW_RECOMMENDATIONS_TV
            }
            if (similarIndex >= 0) {
                binding.vgvTvShow.post {
                    runCatching { binding.vgvTvShow.scrollToPosition(similarIndex) }
                }
            }
        }
    }
}
