package com.dskja.betterstreamflix.fragments.live

import android.os.Bundle
import android.view.KeyEvent
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
import com.dskja.betterstreamflix.databinding.FragmentLiveHubTvBinding
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
import com.dskja.betterstreamflix.utils.LoggingUtils
import com.dskja.betterstreamflix.utils.TvLoadingOverlay
import com.dskja.betterstreamflix.utils.dp
import kotlinx.coroutines.launch

class LiveHubTvFragment : Fragment() {

    private var _binding: FragmentLiveHubTvBinding? = null
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
        _binding = FragmentLiveHubTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ExpMotion.enterScreen(binding.root)
        com.dskja.betterstreamflix.utils.ExpPressEffects.wireLoadingRetry(binding.isLoading.root)
        refreshProfileChip()

        binding.vgvLiveHub.apply {
            setNumColumns(1)
            adapter = appAdapter.apply {
                stateRestorationPolicy =
                    RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    LiveHubViewModel.State.Loading -> {
                        bindEmptyChrome(visible = false)
                        TvLoadingOverlay.show(binding.isLoading.root)
                        binding.isLoading.gIsLoadingRetry.visibility = View.GONE
                        hideWarning()
                    }
                    LiveHubViewModel.State.Restricted -> {
                        TvLoadingOverlay.hide(binding.isLoading.root)
                        hideWarning()
                        displayRestricted()
                    }
                    is LiveHubViewModel.State.Success -> {
                        display(state.categories)
                        TvLoadingOverlay.hide(binding.isLoading.root)
                        showWarning(state.warning)
                        warmZapForSections(state.categories)
                        binding.vgvLiveHub.post { focusFirstChannel() }
                    }
                    LiveHubViewModel.State.Empty -> {
                        display(emptyList())
                        TvLoadingOverlay.hide(binding.isLoading.root)
                        hideWarning()
                        binding.btnLiveHubEmptyCta.post {
                            if (binding.btnLiveHubEmptyCta.isVisible) {
                                binding.btnLiveHubEmptyCta.requestFocus()
                            }
                        }
                    }
                    is LiveHubViewModel.State.Failed -> {
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT,
                        ).show()
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
                            binding.vgvLiveHub.visibility = View.GONE
                            btnIsLoadingRetry.requestFocus()
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
        refreshProfileChip()
    }

    private fun reload() {
        viewModel.load(
            sportLabel = getString(R.string.live_hub_section_sport),
            newsLabel = getString(R.string.live_hub_section_news),
            channelsLabel = getString(R.string.live_hub_section_channels),
            emptyMessage = getString(R.string.live_hub_empty),
        )
    }

    private fun display(categories: List<Category>) {
        // Omit empty sections after load (parity with Home isolateFeatured style).
        val visible = categories.filter { it.list.isNotEmpty() }
        visible.forEach { category ->
            category.list.filterIsInstance<TvShow>().forEach { show ->
                show.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
            }
            category.itemSpacing = 16.dp(requireContext())
            category.itemType = AppAdapter.Type.CATEGORY_TV_ITEM
        }
        bindEmptyChrome(visible = visible.isEmpty())
        binding.vgvLiveHub.isVisible = visible.isNotEmpty()
        appAdapter.submitList(visible)
    }

    private fun bindEmptyChrome(visible: Boolean) {
        binding.tvLiveHubEmpty.text = getString(R.string.live_hub_empty_tv)
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
        binding.vgvLiveHub.isVisible = false
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
        banner.isFocusable = true
        banner.isFocusableInTouchMode = true
        banner.isClickable = true
        banner.nextFocusDownId = R.id.vgv_live_hub
        banner.contentDescription = getString(R.string.home_catalog_warning_tap_retry)
        banner.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
                return@setOnKeyListener false
            }
            focusFirstChannel()
            true
        }
        banner.setOnClickListener { reload() }
    }

    private fun hideWarning() {
        binding.tvLiveHubWarning.apply {
            if (isFocused) clearFocus()
            isFocusable = false
            isFocusableInTouchMode = false
            isVisible = false
            setOnClickListener(null)
            setOnKeyListener(null)
        }
    }

    /**
     * Warm each hub section without wiping prior providers' zap session.
     * Neighbor warm runs for every IPTV section provider (not only app currentProvider).
     */
    private fun warmZapForSections(categories: List<Category>) {
        val shows = categories.flatMap { it.list.filterIsInstance<TvShow>() }
        if (shows.isEmpty()) return
        shows.groupBy { it.providerName.orEmpty() }.forEach { (providerName, providerShows) ->
            val provider = Provider.findByName(providerName) as? IptvProvider ?: return@forEach
            val channels = providerShows.map {
                IptvLiveSession.Channel(id = it.id, name = it.title, logo = it.poster)
            }
            IptvLiveSession.remember(channels, provider = provider, clearOnProviderChange = false)
            val anchors = providerShows.take(3)
            anchors.firstOrNull()?.let { first ->
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

    private fun refreshProfileChip() {
        HomeProfileChip.refresh(
            fragment = this,
            root = _binding?.root,
            showName = true,
        )
        _binding?.root?.findViewById<View>(R.id.tv_home_profile_chip)?.let { chip ->
            chip.isFocusableInTouchMode = true
            chip.nextFocusDownId = R.id.vgv_live_hub
            chip.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
                    return@setOnKeyListener false
                }
                focusFirstChannel()
                true
            }
        }
    }

    private fun focusFirstChannel(): Boolean {
        val grid = _binding?.vgvLiveHub ?: return false
        if (!grid.isAttachedToWindow || (grid.adapter?.itemCount ?: 0) == 0) return false
        return HomeProfileChip.focusFirstPoster(grid)
    }

    /** Side-nav Back / focus handoff. */
    fun requestContentFocus(): Boolean {
        val grid = _binding?.vgvLiveHub ?: return false
        if (!grid.isAttachedToWindow || (grid.adapter?.itemCount ?: 0) == 0) return false
        return focusFirstChannel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
