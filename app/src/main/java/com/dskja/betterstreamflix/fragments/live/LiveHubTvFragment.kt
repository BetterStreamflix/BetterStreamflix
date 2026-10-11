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
import com.dskja.betterstreamflix.databinding.FragmentLiveHubTvBinding
import com.dskja.betterstreamflix.iptv.IptvLiveSession
import com.dskja.betterstreamflix.iptv.IptvZapPreloader
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.IptvProvider
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.LoggingUtils
import com.dskja.betterstreamflix.utils.TvLoadingOverlay
import com.dskja.betterstreamflix.utils.UserPreferences
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
                        binding.tvLiveHubEmpty.isVisible = false
                        TvLoadingOverlay.show(binding.isLoading.root)
                        binding.isLoading.gIsLoadingRetry.visibility = View.GONE
                        hideWarning()
                    }
                    is LiveHubViewModel.State.Success -> {
                        display(state.categories)
                        TvLoadingOverlay.hide(binding.isLoading.root)
                        showWarning(state.warning)
                        warmZapForSections(state.categories)
                        binding.vgvLiveHub.post {
                            if (!binding.vgvLiveHub.hasFocus()) {
                                binding.vgvLiveHub.requestFocus()
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
                        binding.tvLiveHubEmpty.isVisible = appAdapter.items.isEmpty()
                    }
                }
            }
        }

        reload()
    }

    private fun reload() {
        viewModel.load(
            sportLabel = getString(R.string.live_hub_section_sport),
            newsLabel = getString(R.string.live_hub_section_news),
            channelsLabel = getString(R.string.live_hub_section_channels),
        )
    }

    private fun display(categories: List<Category>) {
        categories.forEach { category ->
            category.list.filterIsInstance<TvShow>().forEach { show ->
                show.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
            }
            category.itemSpacing = 16.dp(requireContext())
            category.itemType = AppAdapter.Type.CATEGORY_TV_ITEM
        }
        binding.tvLiveHubEmpty.isVisible = categories.isEmpty()
        binding.vgvLiveHub.isVisible = categories.isNotEmpty()
        appAdapter.submitList(categories)
    }

    private fun showWarning(warning: String?) {
        val banner = binding.tvLiveHubWarning
        if (warning.isNullOrBlank()) {
            hideWarning()
            return
        }
        banner.text = warning
        banner.isVisible = true
        banner.setOnClickListener { reload() }
    }

    private fun hideWarning() {
        binding.tvLiveHubWarning.apply {
            isVisible = false
            setOnClickListener(null)
        }
    }

    private fun warmZapForSections(categories: List<Category>) {
        val shows = categories.flatMap { it.list.filterIsInstance<TvShow>() }
        if (shows.isEmpty()) return
        shows.groupBy { it.providerName.orEmpty() }.forEach { (providerName, providerShows) ->
            val provider = Provider.findByName(providerName) as? IptvProvider ?: return@forEach
            val channels = providerShows.map {
                IptvLiveSession.Channel(id = it.id, name = it.title, logo = it.poster)
            }
            IptvLiveSession.remember(channels, provider = provider)
            providerShows.take(3).forEach { show ->
                IptvZapPreloader.warmChannel(
                    provider,
                    IptvLiveSession.Channel(id = show.id, name = show.title, logo = show.poster),
                )
            }
            if (UserPreferences.currentProvider?.name == provider.name) {
                IptvZapPreloader.warmNeighbors(provider, radius = 2)
            }
        }
    }

    /** Side-nav Back / focus handoff. */
    fun requestContentFocus(): Boolean {
        val grid = _binding?.vgvLiveHub ?: return false
        if (!grid.isAttachedToWindow || (grid.adapter?.itemCount ?: 0) == 0) return false
        grid.requestFocus()
        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
