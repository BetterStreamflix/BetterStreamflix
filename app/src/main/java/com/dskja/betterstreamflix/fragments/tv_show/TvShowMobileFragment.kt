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
import com.dskja.betterstreamflix.databinding.FragmentTvShowMobileBinding
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.Http409CacheGuard
import com.dskja.betterstreamflix.utils.ExpNavAutoHide
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.LoggingUtils
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.loadTvShowBanner
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layoutId = ExperimentalMobileDesign.layout(
            R.layout.fragment_tv_show_mobile,
            R.layout.fragment_tv_show_mobile_exp,
        )
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
            ExpMotion.kenBurns(
                binding.ivTvShowBanner,
                drift = !ExperimentalMobileDesign.heroParallax(),
            )
        }
        binding.root.findViewById<View>(com.dskja.betterstreamflix.R.id.iv_detail_back)
            ?.also { back ->
                androidx.appcompat.widget.TooltipCompat.setTooltipText(
                    back, back.context.getString(com.dskja.betterstreamflix.R.string.exp_back))
                if (ExperimentalMobileDesign.enabled()) {
                    back.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
                    with(com.dskja.betterstreamflix.utils.ExpPressEffects) { back.applyExpPress() }
                    ExpMotion.popIn(back)
                }
            }
            ?.setOnClickListener {
                ExpMotion.hapticTap(it)
                androidx.navigation.Navigation.findNavController(binding.root).navigateUp()
            }

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
                                val doRetry = { viewModel.getTvShow(args.id) }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(requireContext(), getString(com.dskja.betterstreamflix.R.string.clear_cache_done), com.dskja.betterstreamflix.R.string.loading_error_clear_cache)
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
        _binding?.let { appAdapter.onSaveInstanceState(it.rvTvShow) }
        _binding = null
        super.onDestroyView()
    }


    private var detailScrollOffset = 0

    private fun initializeTvShow() {
        binding.rvTvShow.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(20.dp(requireContext()))
            )
            if (ExperimentalMobileDesign.enabled()) {
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        detailScrollOffset += dy
                        val parallax = (detailScrollOffset * 0.42f).coerceIn(0f, 720f)
                        binding.ivTvShowBanner.translationY = -parallax
                        binding.ivTvShowBanner.alpha = (1f - parallax / 900f).coerceIn(0.55f, 1f)
                    }
                })
            }
        }
    }

    private fun displayTvShow(tvShow: TvShow) {
        binding.ivTvShowBanner.loadTvShowBanner(tvShow) {
            transition(DrawableTransitionOptions.withCrossFade())
        }

        appAdapter.submitList(listOfNotNull(
            tvShow.apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE },

            tvShow.takeIf { it.seasons.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_SEASONS_MOBILE },

            tvShow.takeIf { it.directors.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_DIRECTORS_MOBILE },

            tvShow.takeIf { it.cast.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_CAST_MOBILE },

            tvShow.takeIf { it.recommendations.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_RECOMMENDATIONS_MOBILE },
        ))
    }
}
