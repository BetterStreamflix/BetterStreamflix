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
import com.dskja.betterstreamflix.live.ChannelPinStore
import com.dskja.betterstreamflix.live.LiveCommandController
import com.dskja.betterstreamflix.live.LiveCommandMode
import com.dskja.betterstreamflix.live.LiveCommandPolicy
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.ui.HomeProfileChip
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.LiveHubCatalog
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
    private var command: LiveCommandController? = null
    private var lastHubCategories: List<Category> = emptyList()

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
        ExpMotion.enterScreen(binding.root)
        ExpMotion.staggerFirstFill(binding.rvLiveHub)
        HomeProfileChip.refresh(this, binding.root)
        com.dskja.betterstreamflix.utils.ExpPressEffects.wireLoadingRetry(binding.isLoading.root)

        command = LiveCommandController(
            fragment = this,
            root = binding.root,
            isTv = false,
            onVisibleChanged = { cats, _ -> display(cats) },
            onModeChanged = { mode ->
                if (mode == LiveCommandMode.SPORT) {
                    warmSportZapRing(lastHubCategories)
                }
            },
            focusPins = {
                val strip = activity?.findViewById<View>(R.id.channel_pin_strip)
                if (strip != null) {
                    strip.isVisible = true
                    strip.post { strip.requestFocus() }
                } else {
                    LiveCommandController.refreshPinStrip(activity)
                }
            },
        ).also { it.attach() }

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
                        lastHubCategories = emptyList()
                        command?.setRestricted(true)
                        command?.bindEmpty()
                        displayRestricted()
                    }
                    is LiveHubViewModel.State.Success -> {
                        command?.setRestricted(false)
                        lastHubCategories = state.categories
                        command?.bindHub(
                            categories = state.categories,
                            pinCount = ChannelPinStore.pinCount(requireContext()),
                            pipCandidates = pipCandidateCount(state.categories),
                        )
                        ExpMotion.fadeOutAndHide(binding.isLoading.root)
                        showWarning(state.warning)
                        if (command?.currentMode() == LiveCommandMode.SPORT) {
                            warmSportZapRing(state.categories)
                        }
                    }
                    is LiveHubViewModel.State.Empty -> {
                        command?.setRestricted(false)
                        lastHubCategories = emptyList()
                        command?.bindHub(emptyList(), pinCount = 0, pipCandidates = 0)
                        ExpMotion.fadeOutAndHide(binding.isLoading.root)
                        if (!state.warning.isNullOrBlank()) {
                            showWarning(state.warning)
                        } else {
                            hideWarning()
                        }
                    }
                    is LiveHubViewModel.State.Failed -> {
                        lastHubCategories = emptyList()
                        command?.bindEmpty()
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
        if (lastHubCategories.isNotEmpty()) {
            viewModel.refreshFavoritesShelf()
        }
    }

    private fun reload() {
        viewModel.load(
            sportLabel = getString(R.string.live_hub_section_sport),
            newsLabel = getString(R.string.live_hub_section_news),
            channelsLabel = getString(R.string.live_hub_section_channels),
            favoritesLabel = getString(R.string.live_hub_section_favorites),
        )
    }

    private fun display(categories: List<Category>) {
        val now = System.currentTimeMillis()
        val visible = categories.filter { it.list.isNotEmpty() }.map { category ->
            val shows = category.list.filterIsInstance<TvShow>().map { show ->
                show.copy().apply {
                    itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                    enrichCatchUp(this, now)
                }
            }
            Category(category.name, shows).also {
                it.identityKey = category.identityKey
                it.itemSpacing = 10.dp(requireContext())
                it.itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
            }
        }
        LiveHubSectionZap.bindCategories(
            if (command?.currentQuery().isNullOrBlank()) {
                val preferred = command?.currentMode()?.let {
                    LiveCommandPolicy.preferredZapSection(it)
                }
                if (preferred != null) {
                    visible.filter { it.identityKey == preferred }.ifEmpty { visible }
                } else {
                    visible
                }
            } else {
                visible
            },
        )
        val emptyForMode = visible.isEmpty() && lastHubCategories.isNotEmpty()
        bindEmptyChrome(visible = visible.isEmpty(), commandEmpty = emptyForMode)
        if (emptyForMode) {
            binding.tvLiveHubEmpty.text = emptyMessageForCommand()
        }
        binding.rvLiveHub.isVisible = visible.isNotEmpty()
        appAdapter.submitList(visible)
    }

    private fun enrichCatchUp(show: TvShow, nowMs: Long) {
        val hint = LiveCommandPolicy.catchUpHint(show, nowMs) ?: return
        when (hint.messageKey) {
            LiveCommandPolicy.CatchUpKind.STARTED_RECENTLY -> {
                if (show.programNext.isNullOrBlank()) {
                    show.programNext = getString(
                        R.string.live_command_catchup_started,
                        hint.detail.orEmpty(),
                    )
                }
            }
            LiveCommandPolicy.CatchUpKind.UP_NEXT -> {
                if (show.programNext.isNullOrBlank() && !hint.detail.isNullOrBlank()) {
                    show.programNext = getString(
                        R.string.live_command_catchup_up_next,
                        hint.detail,
                    )
                }
            }
            LiveCommandPolicy.CatchUpKind.LIVE_NOW -> Unit
        }
    }

    private fun emptyMessageForCommand(): String {
        val q = command?.currentQuery().orEmpty()
        if (q.isNotBlank()) return getString(R.string.live_command_empty_search)
        return when (command?.currentMode()) {
            LiveCommandMode.REMINDERS -> getString(R.string.live_command_empty_reminders)
            LiveCommandMode.FAVORITES -> getString(R.string.live_command_empty_favorites)
            LiveCommandMode.SPORT -> getString(R.string.live_command_empty_sport)
            LiveCommandMode.NEWS -> getString(R.string.live_command_empty_news)
            LiveCommandMode.CHANNELS -> getString(R.string.live_command_empty_channels)
            else -> getString(R.string.live_hub_empty)
        }
    }

    private fun bindEmptyChrome(visible: Boolean, commandEmpty: Boolean = false) {
        if (!commandEmpty) {
            binding.tvLiveHubEmpty.text = getString(R.string.live_hub_empty)
        }
        val ctaLabel = when {
            command?.currentQuery()?.isNotBlank() == true ->
                getString(R.string.exp_empty_clear_search)
            commandEmpty && command?.currentMode() != null &&
                command?.currentMode() != LiveCommandMode.ALL ->
                getString(R.string.exp_empty_clear_filter)
            else -> getString(R.string.exp_empty_try_channel)
        }
        binding.btnLiveHubEmptyCta.text = ctaLabel
        ExpEmptyChrome.bind(
            emptyView = binding.tvLiveHubEmpty,
            emptyRule = binding.vLiveHubEmptyRule,
            emptyCta = binding.btnLiveHubEmptyCta,
            visible = visible,
            onCtaClick = {
                when {
                    command?.currentQuery()?.isNotBlank() == true -> command?.clearQuery()
                    commandEmpty && command?.currentMode() != LiveCommandMode.ALL ->
                        command?.setMode(LiveCommandMode.ALL)
                    else -> reload()
                }
            },
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
        banner.contentDescription = getString(R.string.home_catalog_warning_tap_retry)
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

    private fun warmSportZapRing(categories: List<Category>) {
        val sport = categories.firstOrNull {
            it.identityKey == LiveHubCatalog.SECTION_SPORT
        }
        LiveHubSectionZap.warmSportRing(sport)
    }

    private fun pipCandidateCount(categories: List<Category>): Int {
        val pinKeys = ChannelPinStore.list(requireContext()).map { it.key }.toHashSet()
        val favKeys = categories
            .firstOrNull { it.identityKey == LiveHubCatalog.SECTION_FAVORITES }
            ?.list
            ?.filterIsInstance<TvShow>()
            ?.map { LiveHubCatalog.identityKey(it) }
            ?.toHashSet()
            .orEmpty()
        return (pinKeys + favKeys).size
    }

    override fun onDestroyView() {
        LiveHubSectionZap.clear()
        command?.detach()
        command = null
        super.onDestroyView()
        _binding = null
    }
}
