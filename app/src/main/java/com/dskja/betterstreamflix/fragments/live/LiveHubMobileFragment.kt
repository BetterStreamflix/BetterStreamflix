package com.dskja.betterstreamflix.fragments.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.databinding.FragmentLiveHubMobileBinding
import com.dskja.betterstreamflix.iptv.IptvLiveSession
import com.dskja.betterstreamflix.iptv.IptvZapPreloader
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.IptvProvider
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.ui.HomeProfileChip
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpNavAutoHide
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.LoggingUtils
import com.dskja.betterstreamflix.utils.dp
import kotlinx.coroutines.launch

class LiveHubMobileFragment : Fragment() {

    private var _binding: FragmentLiveHubMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LiveHubViewModel by lazy {
        ViewModelProvider(this)[LiveHubViewModel::class.java]
    }

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLiveHubMobileBinding.bind(
            inflater.inflate(R.layout.fragment_live_hub_mobile, container, false),
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ExpNavAutoHide.attach(binding.root)
        ExpMotion.enterScreen(binding.root)
        ExperimentalMobileDesign.applyReducedGlass(binding.root)
        ExpMotion.staggerFirstFill(binding.rvLiveHub)
        HomeProfileChip.refresh(this, binding.root)
        com.dskja.betterstreamflix.utils.ExpPressEffects.wireLoadingRetry(binding.isLoading.root)

        binding.rvLiveHub.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy =
                    RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    LiveHubViewModel.State.Loading -> {
                        bindEmptyChrome(visible = false)
                        binding.isLoading.apply {
                            ExpMotion.fadeInAndShow(root)
                            com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                            gIsLoadingRetry.visibility = View.GONE
                        }
                        hideWarning()
                    }
                    LiveHubViewModel.State.Restricted -> {
                        ExpMotion.fadeOutAndHide(binding.isLoading.root)
                        hideWarning()
                        displayRestricted()
                    }
                    is LiveHubViewModel.State.Success -> {
                        display(state.categories)
                        ExpMotion.fadeOutAndHide(binding.isLoading.root)
                        showWarning(state.warning)
                        warmZapForSections(state.categories)
                    }
                    LiveHubViewModel.State.Empty -> {
                        display(emptyList())
                        ExpMotion.fadeOutAndHide(binding.isLoading.root)
                        hideWarning()
                    }
                    is LiveHubViewModel.State.Failed -> {
                        if (!ExperimentalMobileDesign.enabled()) {
                            Toast.makeText(
                                requireContext(),
                                state.error.message ?: "",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        binding.isLoading.apply {
                            com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, false)
                            gIsLoadingRetry.visibility = View.VISIBLE
                            com.dskja.betterstreamflix.utils.ExpPressEffects.animateLoadingError(root)
                            val retry = { reload() }
                            btnIsLoadingRetry.setOnClickListener { retry() }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                ExpDialogChrome.notify(
                                    requireContext(),
                                    getString(R.string.clear_cache_done),
                                    R.string.loading_error_clear_cache,
                                )
                                retry()
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
                        }
                        bindEmptyChrome(visible = appAdapter.items.isEmpty())
                    }
                }
            }
        }

        reload()
    }

    override fun onResume() {
        super.onResume()
        HomeProfileChip.refresh(this, _binding?.root)
    }

    private fun reload() {
        viewModel.load(
            sportLabel = getString(R.string.live_hub_section_sport),
            newsLabel = getString(R.string.live_hub_section_news),
            channelsLabel = getString(R.string.live_hub_section_channels),
        )
    }

    private fun display(categories: List<Category>) {
        // Omit empty sections after load (parity with Home isolateFeatured style).
        val visible = categories.filter { it.list.isNotEmpty() }
        visible.forEach { category ->
            category.list.filterIsInstance<TvShow>().forEach { show ->
                show.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
            }
            category.itemSpacing = 10.dp(requireContext())
            category.itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
        }
        bindEmptyChrome(visible = visible.isEmpty())
        binding.rvLiveHub.isVisible = visible.isNotEmpty()
        appAdapter.submitList(visible)
    }

    private fun bindEmptyChrome(visible: Boolean) {
        binding.tvLiveHubEmpty.text = getString(R.string.live_hub_empty)
        ExpEmptyChrome.bind(
            emptyView = binding.tvLiveHubEmpty,
            emptyRule = binding.vLiveHubEmptyRule,
            emptyCta = binding.btnLiveHubEmptyCta,
            visible = visible,
            onCtaClick = { reload() },
        )
    }

    private fun displayRestricted() {
        appAdapter.submitList(emptyList())
        binding.rvLiveHub.isVisible = false
        bindEmptyChrome(visible = false)
        binding.tvLiveHubEmpty.text = getString(R.string.live_hub_restricted)
        binding.tvLiveHubEmpty.isVisible = true
    }

    private fun showWarning(warning: String?) {
        val banner = binding.tvLiveHubWarning
        if (warning.isNullOrBlank()) {
            hideWarning()
            return
        }
        banner.text = warning
        banner.isVisible = true
        banner.setOnClickListener {
            ExpMotion.hapticTap(it)
            reload()
        }
    }

    private fun hideWarning() {
        binding.tvLiveHubWarning.apply {
            isVisible = false
            setOnClickListener(null)
        }
    }

    /**
     * Seed zap session + warm ±2 for the first channel of each shelf so the
     * first Live Hub play hops faster.
     */
    private fun warmZapForSections(categories: List<Category>) {
        val shows = categories.flatMap { it.list.filterIsInstance<TvShow>() }
        if (shows.isEmpty()) return
        val byProvider = shows.groupBy { it.providerName.orEmpty() }
        byProvider.forEach { (providerName, providerShows) ->
            val provider = Provider.findByName(providerName) as? IptvProvider ?: return@forEach
            val channels = providerShows.map {
                IptvLiveSession.Channel(id = it.id, name = it.title, logo = it.poster)
            }
            IptvLiveSession.remember(channels, provider = provider)
            val anchors = providerShows.take(3)
            anchors.firstOrNull()?.let { first ->
                // Seed playhead so warmNeighbors(±2) is not a no-op on hub browse.
                IptvLiveSession.setCurrent(first.id)
            }
            anchors.forEach { show ->
                IptvZapPreloader.warmChannel(
                    provider,
                    IptvLiveSession.Channel(id = show.id, name = show.title, logo = show.poster),
                )
            }
            IptvZapPreloader.warmNeighbors(provider, radius = 2)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
