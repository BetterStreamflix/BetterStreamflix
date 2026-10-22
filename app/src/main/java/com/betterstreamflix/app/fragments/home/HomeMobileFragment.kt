package com.betterstreamflix.app.fragments.home

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.betterstreamflix.app.R
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.databinding.FragmentHomeMobileBinding
import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.ui.HomeProfileChip
import com.betterstreamflix.app.ui.FeaturedTvRotation
import com.betterstreamflix.app.utils.UserPreferences
import com.betterstreamflix.app.utils.dp
import com.betterstreamflix.app.utils.CacheUtils
import com.betterstreamflix.app.utils.Http409CacheGuard
import com.betterstreamflix.app.utils.ExpEmptyChrome
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.utils.HomeCatalogPipeline
import com.betterstreamflix.app.utils.LoggingUtils
import com.betterstreamflix.app.utils.ProviderChangeNotifier
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView

class HomeMobileFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentHomeMobileBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by lazy {
        ViewModelProvider(this)[HomeViewModel::class.java]
    }

    private val appAdapter = AppAdapter()

    /** Vertical rv_home scroll state, kept across view recreation (back-nav). */
    private var homeListState: android.os.Parcelable? = null
    /** Provider+profile owning [homeListState]; a swap discards the stale offset. */
    private var homeListStateOwner: String? = null
    private var homeBindSignature: String? = null

    val shelfPool: RecyclerView.RecycledViewPool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(AppAdapter.Type.MOVIE_MOBILE_ITEM.ordinal, 16)
        setMaxRecycledViews(AppAdapter.Type.TV_SHOW_MOBILE_ITEM.ordinal, 16)
        setMaxRecycledViews(AppAdapter.Type.EPISODE_MOBILE_ITEM.ordinal, 8)
        setMaxRecycledViews(AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM.ordinal, 8)
        setMaxRecycledViews(AppAdapter.Type.EPISODE_CONTINUE_WATCHING_MOBILE_ITEM.ordinal, 8)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layoutRes = R.layout.fragment_home_mobile
        val root = inflater.inflate(layoutRes, container, false)
        _binding = FragmentHomeMobileBinding.bind(root)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedInstanceState?.let {
            homeListState = homeListState ?: androidx.core.os.BundleCompat.getParcelable(
                it,
                KEY_HOME_LIST_STATE,
                android.os.Parcelable::class.java,
            )
            homeListStateOwner = homeListStateOwner ?: it.getString(KEY_HOME_LIST_OWNER)
        }

        initializeHome()

        binding.srlHome.apply {
            setColorSchemeColors(
                androidx.core.content.ContextCompat.getColor(
                    requireContext(), R.color.profile_accent,
                ),
            )
            setProgressBackgroundColorSchemeColor(
                androidx.core.content.ContextCompat.getColor(
                    requireContext(), R.color.cinema_elevated,
                ),
            )
            setOnRefreshListener { viewModel.getHome() }
        }

        // Provider changes: ViewModel.getHome is the single caller; fragment only refreshes chrome.
        viewLifecycleOwner.lifecycleScope.launch {
            ProviderChangeNotifier.providerChangeFlow.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect {
                refreshProviderLogo()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    HomeViewModel.State.Loading -> binding.isLoading.apply {
                        ExpMotion.fadeInAndShow(root)
                        com.betterstreamflix.app.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                        hideCatalogWarning()
                    }
                    is HomeViewModel.State.SuccessLoading -> {
                        binding.srlHome.isRefreshing = false
                        displayHome(state.categories, state.watchlistDigestEpisodes)
                        ExpMotion.fadeOutAndHide(binding.isLoading.root)
                        showCatalogWarning(state.providerWarning)
                    }
                    is HomeViewModel.State.FailedLoading -> {
                        binding.srlHome.isRefreshing = false
                        if (http409Guard.handle(requireContext(), state.error) { viewModel.getHome() }) {
                                return@collect
                            }
                            Toast.makeText(
                                requireContext(),
                                state.error.message ?: "",
                                Toast.LENGTH_SHORT
                            ).show()
                        binding.isLoading.apply {
                            com.betterstreamflix.app.utils.ExpPressEffects.showLoadingSkeleton(root, false)
                            gIsLoadingRetry.visibility = View.VISIBLE
                            val doRetry = { viewModel.getHome() }
                            btnIsLoadingRetry.setOnClickListener { doRetry() }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                com.betterstreamflix.app.utils.ExpDialogChrome.notify(requireContext(), getString(com.betterstreamflix.app.R.string.clear_cache_done), com.betterstreamflix.app.R.string.loading_error_clear_cache)
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

    override fun onResume() {
        super.onResume()
        refreshProviderLogo()
        refreshProfileChip()
        // Soft-resume featured auto-advance — avoid notifyItemChanged (full rebind glitches).
        _binding?.rvHome?.let { appAdapter.resumeCategorySwipers(it) }
        // Clean Exit: kill any leaked player/trailer audio on Home.
        com.betterstreamflix.app.ui.TrailerPlaybackController.silenceAllActive()
        com.betterstreamflix.app.player.PlaybackLifecycleGuard.stopActivePlayback()
        com.betterstreamflix.app.player.PlayerCleanExit.consumeHomeFocusRestore()
        // Soft-refresh library/history (same path as UserDataNotifier).
        viewModel.softRefreshUserData()
    }

    override fun onPause() {
        // Series Fast-Forward: commit visit watermark only when leaving Home
        // (not when opening Movie/TV Detail — that was emptying FF shelves).
        if (!isPausedForDetailNavigation()) {
            viewModel.commitHomeVisit()
        }
        // Soft-pause featured auto-advance while Home is under the detail back stack
        // (prevents BETTERSTREAMFLIX-1 NavHost NPEs from delayed page callbacks).
        _binding?.rvHome?.let { appAdapter.pauseCategorySwipers(it) }
        super.onPause()
    }

    /** Shelf chip toggle for “new since last visit”. */
    fun setSeriesFastForwardEnabled(enabled: Boolean) {
        viewModel.setSeriesFastForwardEnabled(enabled)
    }

    /** True when Home paused because Movie/TV Detail was pushed on the nav stack. */
    private fun isPausedForDetailNavigation(): Boolean {
        val destId = runCatching { findNavController().currentDestination?.id }.getOrNull()
            ?: return false
        return destId == R.id.movie || destId == R.id.tv_show
    }

    override fun onDestroyView() {
        _binding?.let {
            appAdapter.onSaveInstanceState(it.rvHome)
            homeListState = it.rvHome.layoutManager?.onSaveInstanceState()
            homeListStateOwner = homeDataOwner()
        }
        _binding = null
        super.onDestroyView()
    }

    private fun homeDataOwner(): String =
        (UserPreferences.currentProvider?.name ?: "") +
            "|" + com.betterstreamflix.app.profiles.ProfileManager.activeProfileId

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Keep the offset through process death — onSaveInstanceState precedes
        // onDestroyView, so read the live LayoutManager state with the field fallback.
        val state = homeListState ?: _binding?.rvHome?.layoutManager?.onSaveInstanceState()
        if (state != null) {
            outState.putParcelable(KEY_HOME_LIST_STATE, state)
            outState.putString(KEY_HOME_LIST_OWNER, homeListStateOwner ?: homeDataOwner())
        }
    }

    /**
     * Restores the pre-navigation scroll offset once, only while the same
     * provider/profile owns the catalog — a dataset swap falls back to top.
     */
    private fun restoreHomeScrollIfPending() {
        val state = homeListState ?: return
        homeListState = null
        if (homeListStateOwner != homeDataOwner()) return
        binding.rvHome.post {
            _binding?.rvHome?.layoutManager?.onRestoreInstanceState(state)
        }
    }

    private fun initializeHome() {
        binding.rvHome.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setHasFixedSize(true)
            setItemViewCacheSize(8)
            itemAnimator = null
            recycledViewPool.setMaxRecycledViews(AppAdapter.Type.CATEGORY_MOBILE_ITEM.ordinal, 6)
            recycledViewPool.setMaxRecycledViews(AppAdapter.Type.CATEGORY_MOBILE_SWIPER.ordinal, 2)
            // No gap under Featured — the window color reads as a black band there.
            addItemDecoration(HomeRowSpacing(8.dp(requireContext())))
        }

        refreshProviderLogo()
        refreshProfileChip()
        binding.ivProviderLogo.apply {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                ExpMotion.hapticTap(it)
                findNavController().navigate(R.id.providers)
            }
        }
        
        // Default shell hides the background; experimental keeps a full-bleed hero plane.
        binding.ivHomeBackground.visibility = View.GONE

        ExpMotion.enterScreen(binding.root)
        ExpMotion.staggerFirstFill(binding.rvHome)
    }

    private fun refreshProviderLogo() {
        val logoView = _binding?.ivProviderLogo ?: return
        val context = logoView.context
        Glide.with(context)
            .load(UserPreferences.currentProvider?.logo?.takeIf { it.isNotEmpty() }
                ?: R.drawable.ic_provider_default_logo)
            .error(R.drawable.ic_provider_default_logo)
            .fitCenter()
            .into(logoView)
    }

    private fun refreshProfileChip() {
        HomeProfileChip.refresh(this, _binding?.root, showName = true)
    }

    private fun showCatalogWarning(warning: String?) {
        val banner = _binding?.root?.findViewById<android.widget.TextView>(R.id.tv_home_catalog_warning)
            ?: return
        val text = warning?.takeIf { it.isNotBlank() }
        if (text == null) {
            banner.visibility = View.GONE
            banner.setOnClickListener(null)
            return
        }
        banner.text = text
        banner.contentDescription = getString(R.string.home_catalog_warning_tap_retry)
        banner.visibility = View.VISIBLE
        banner.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.getHome()
        }
    }

    private fun hideCatalogWarning() {
        _binding?.root?.findViewById<android.widget.TextView>(R.id.tv_home_catalog_warning)?.apply {
            visibility = View.GONE
            setOnClickListener(null)
        }
    }

    private fun displayHome(
        categories: List<Category>,
        watchlistDigestEpisodes: List<Episode> = emptyList(),
    ) {

        categories
            .find { Category.isFeaturedName(it.name) }
            ?.also { featured ->
                // Restore Featured rotation index across pin/remix rematerialize (parity with TV).
                val previous = appAdapter.items
                    .filterIsInstance<Category>()
                    .find { item -> Category.isFeaturedName(item.name) }
                featured.selectedIndex = FeaturedTvRotation.coerceIndex(
                    previous?.selectedIndex ?: featured.selectedIndex,
                    featured.list.size,
                )
                featured.list.forEach { show ->
                    when (show) {
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_SWIPER_MOBILE_ITEM
                        is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_SWIPER_MOBILE_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.CONTINUE_WATCHING }
            ?.also {
                it.name = getString(R.string.home_continue_watching)
                it.list.forEach { show ->
                    when (show) {
                        is Episode -> show.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_MOBILE_ITEM
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.NEXT_EPISODES }
            ?.also {
                it.name = getString(R.string.home_next_episodes)
                it.list.forEach { show ->
                    if (show is Episode) {
                        show.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_MOBILE_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.NEW_THIS_WEEK }
            ?.also {
                it.name = getString(R.string.home_new_this_week)
                it.list.forEach { show ->
                    if (show is Episode) {
                        show.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_MOBILE_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.RECENTLY_WATCHED }
            ?.also {
                it.name = getString(R.string.home_recently_watched)
            }

        categories
            .find { it.name == Category.RECENTLY_ADDED }
            ?.also {
                it.name = getString(R.string.home_recently_added)
            }

        categories
            .find { it.name == Category.SMART_CHILL_EVENING }
            ?.also { it.name = getString(R.string.home_smart_chill_evening) }

        categories
            .find { it.name == Category.SMART_WITH_KIDS }
            ?.also { it.name = getString(R.string.home_smart_with_kids) }

        categories
            .find { it.name == Category.SMART_DE_AUDIO }
            ?.also { it.name = getString(R.string.home_smart_de_audio) }

        categories
            .find {
                it.name == Category.HOME_LIVE || it.identityKey == Category.HOME_LIVE_ID
            }
            ?.also { it.name = getString(R.string.home_live_shelf) }

        categories
            .find { it.name == Category.FAMILY_FRIDAY }
            ?.also { it.name = getString(R.string.home_family_friday) }

        categories
            .find {
                it.name == Category.DOWNLOADS || it.identityKey == Category.DOWNLOADS_ID
            }
            ?.also {
                it.name = getString(R.string.home_downloads_shelf)
                it.list.forEach { show ->
                    when (show) {
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                        is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.FAVORITE_MOVIES }
            ?.also { it.name = getString(R.string.home_favorite_movies) }

        categories
            .find { it.name == Category.FAVORITE_TV_SHOWS }
            ?.also { it.name = getString(R.string.home_favorite_tv_shows) }

        appAdapter.onSupportBannerClickListener = {
            com.betterstreamflix.app.support.SupportPromptPolicy.openHomeCard()
            runCatching { findNavController().navigate(R.id.support) }
        }
        appAdapter.onSupportBannerDismissListener = {
            com.betterstreamflix.app.support.SupportPromptPolicy.dismissHomeCard()
            val withoutBanner = appAdapter.items.filterNot { it is com.betterstreamflix.app.support.SupportBannerItem }
            appAdapter.submitList(withoutBanner)
        }
        appAdapter.onWatchlistDigestClickListener = { banner ->
            viewLifecycleOwner.lifecycleScope.launch {
                val result = com.betterstreamflix.app.watchlist.WatchlistDigestQueue.apply(
                    requireContext(),
                    banner.episodes,
                )
                val first = result.firstEpisode
                val navigated = if (first != null) {
                    com.betterstreamflix.app.watchlist.WatchlistDigestQueue.navigateToEpisode(
                        findNavController(),
                        requireContext(),
                        first,
                    )
                } else {
                    false
                }
                if (result.seededCount <= 0 && !navigated) return@launch
                val without = appAdapter.items.filterNot {
                    it is com.betterstreamflix.app.watchlist.WatchlistDigestBannerItem
                }
                appAdapter.submitList(without)
                if (result.seededCount > 0) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.watchlist_digest_queued_toast, result.seededCount),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
        appAdapter.onWatchlistDigestDismissListener = {
            com.betterstreamflix.app.watchlist.WatchlistDigestPolicy.dismiss()
            val without = appAdapter.items.filterNot {
                it is com.betterstreamflix.app.watchlist.WatchlistDigestBannerItem
            }
            appAdapter.submitList(without)
        }
        appAdapter.onProviderOutageBannerClickListener = {
            com.betterstreamflix.app.fragments.settings.SettingsDeepLink.openContentScreen()
            runCatching { findNavController().navigate(R.id.settings) }
        }
        appAdapter.onProviderOutageBannerDismissListener = {
            com.betterstreamflix.app.providers.ProviderOutagePolicy.dismiss(requireContext())
            val without = appAdapter.items.filterNot {
                it is com.betterstreamflix.app.providers.ProviderOutageBannerItem
            }
            appAdapter.submitList(without)
        }

        val homeItems = mutableListOf<AppAdapter.Item>()
        val visibleCategories = HomeCatalogPipeline.isolateFeatured(categories)
        val resumeShelfTitles = setOf(
            getString(R.string.home_continue_watching),
            getString(R.string.home_next_episodes),
            getString(R.string.home_new_this_week),
        )
        visibleCategories.onEach { category ->
            if (!Category.isFeaturedName(category.name) && category.name !in resumeShelfTitles) {
                category.list.onEach { show ->
                    when (show) {
                        is Episode -> show.itemType = AppAdapter.Type.EPISODE_MOBILE_ITEM
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                        is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                    }
                }
            }
            category.itemSpacing = 10.dp(requireContext())
            category.itemType = when {
                Category.isFeaturedName(category.name) -> AppAdapter.Type.CATEGORY_MOBILE_SWIPER
                else -> AppAdapter.Type.CATEGORY_MOBILE_ITEM
            }
        }
        // Restore before the unchanged-check: the adapter keeps its items across
        // view recreation, so the offset can be re-applied even when the dataset
        // signature skips the rebind below.
        restoreHomeScrollIfPending()
        if (homeUnchanged(visibleCategories, watchlistDigestEpisodes)) return

        // Stamp SWIPER types only on the isolated FEATURED clones — never on
        // original shelf rows (BETTERSTREAMFLIX-13).
        visibleCategories
            .filter { Category.isFeaturedName(it.name) }
            .flatMap { it.list }
            .forEach { show ->
                when (show) {
                    is Movie -> show.itemType = AppAdapter.Type.MOVIE_SWIPER_MOBILE_ITEM
                    is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_SWIPER_MOBILE_ITEM
                }
            }

        val hasContinueWatching = categories.any {
            it.name == getString(R.string.home_continue_watching) && it.list.isNotEmpty()
        }
        val showDigest = com.betterstreamflix.app.watchlist.WatchlistDigestPolicy.shouldShow(
            watchlistDigestEpisodes,
        )
        val trustSnapshot = com.betterstreamflix.app.providers.ProviderTrustLayer.snapshot()
        val showOutage = com.betterstreamflix.app.providers.ProviderOutagePolicy.shouldShow(
            trustSnapshot,
            requireContext(),
        )
        val outageNames = com.betterstreamflix.app.providers.ProviderOutagePolicy
            .relevantOutageNames(trustSnapshot)
        var digestRecorded = false
        visibleCategories.forEachIndexed { index, category ->
            homeItems.add(category)
            // Place digest + support cards after featured / continue watching — never first.
            // CW was already renamed to the localized title above; do not check the
            // English Category.CONTINUE_WATCHING constant here.
            val insertAfter = category.name == getString(R.string.home_continue_watching) ||
                (Category.isFeaturedName(category.name) && !hasContinueWatching)
            if (insertAfter) {
                if (showOutage &&
                    homeItems.none {
                        it is com.betterstreamflix.app.providers.ProviderOutageBannerItem
                    }
                ) {
                    homeItems.add(
                        com.betterstreamflix.app.providers.ProviderOutageBannerItem(
                            providerNames = outageNames.ifEmpty { trustSnapshot.outageProviderNames },
                            level = trustSnapshot.outageLevel,
                        ).apply {
                            itemType = AppAdapter.Type.PROVIDER_OUTAGE_BANNER_MOBILE_ITEM
                        }
                    )
                }
                if (showDigest &&
                    homeItems.none { it is com.betterstreamflix.app.watchlist.WatchlistDigestBannerItem }
                ) {
                    homeItems.add(
                        com.betterstreamflix.app.watchlist.WatchlistDigestBannerItem(
                            episodes = watchlistDigestEpisodes,
                        ).apply {
                            itemType = AppAdapter.Type.WATCHLIST_DIGEST_MOBILE_ITEM
                        }
                    )
                    if (!digestRecorded) {
                        com.betterstreamflix.app.watchlist.WatchlistDigestPolicy.recordShown()
                        digestRecorded = true
                    }
                }
                if (com.betterstreamflix.app.support.SupportPromptPolicy.shouldShowHomeCard() &&
                    homeItems.none { it is com.betterstreamflix.app.support.SupportBannerItem }
                ) {
                    homeItems.add(
                        com.betterstreamflix.app.support.SupportBannerItem().apply {
                            itemType = AppAdapter.Type.SUPPORT_BANNER_MOBILE_ITEM
                        }
                    )
                }
            }
        }

        // Fallback: if no featured/continue rows, append near the top after first category.
        if (showOutage &&
            homeItems.none {
                it is com.betterstreamflix.app.providers.ProviderOutageBannerItem
            } &&
            homeItems.isNotEmpty()
        ) {
            homeItems.add(
                1.coerceAtMost(homeItems.size),
                com.betterstreamflix.app.providers.ProviderOutageBannerItem(
                    providerNames = outageNames.ifEmpty { trustSnapshot.outageProviderNames },
                    level = trustSnapshot.outageLevel,
                ).apply {
                    itemType = AppAdapter.Type.PROVIDER_OUTAGE_BANNER_MOBILE_ITEM
                }
            )
        }
        if (showDigest &&
            homeItems.none { it is com.betterstreamflix.app.watchlist.WatchlistDigestBannerItem } &&
            homeItems.isNotEmpty()
        ) {
            val insertAt = when {
                homeItems.any {
                    it is com.betterstreamflix.app.providers.ProviderOutageBannerItem
                } ->
                    homeItems.indexOfFirst {
                        it is com.betterstreamflix.app.providers.ProviderOutageBannerItem
                    } + 1
                else -> 1.coerceAtMost(homeItems.size)
            }
            homeItems.add(
                insertAt.coerceAtMost(homeItems.size),
                com.betterstreamflix.app.watchlist.WatchlistDigestBannerItem(
                    episodes = watchlistDigestEpisodes,
                ).apply {
                    itemType = AppAdapter.Type.WATCHLIST_DIGEST_MOBILE_ITEM
                }
            )
            if (!digestRecorded) {
                com.betterstreamflix.app.watchlist.WatchlistDigestPolicy.recordShown()
                digestRecorded = true
            }
        }
        if (com.betterstreamflix.app.support.SupportPromptPolicy.shouldShowHomeCard() &&
            homeItems.none { it is com.betterstreamflix.app.support.SupportBannerItem } &&
            homeItems.isNotEmpty()
        ) {
            val insertAt = when {
                homeItems.any { it is com.betterstreamflix.app.watchlist.WatchlistDigestBannerItem } ->
                    homeItems.indexOfFirst {
                        it is com.betterstreamflix.app.watchlist.WatchlistDigestBannerItem
                    } + 1
                homeItems.any {
                    it is com.betterstreamflix.app.providers.ProviderOutageBannerItem
                } ->
                    homeItems.indexOfFirst {
                        it is com.betterstreamflix.app.providers.ProviderOutageBannerItem
                    } + 1
                else -> 1.coerceAtMost(homeItems.size)
            }
            homeItems.add(
                insertAt.coerceAtMost(homeItems.size),
                com.betterstreamflix.app.support.SupportBannerItem().apply {
                    itemType = AppAdapter.Type.SUPPORT_BANNER_MOBILE_ITEM
                }
            )
        }

        appAdapter.submitList(homeItems)

        val hasCatalogRows = homeItems.any { it is Category }
        ExpEmptyChrome.bind(
            emptyView = binding.root.findViewById(R.id.tv_home_empty),
            emptyRule = binding.root.findViewById(R.id.v_home_empty_rule),
            emptyCta = binding.root.findViewById(R.id.btn_home_empty_cta),
            visible = !hasCatalogRows,
            tintOnSurfaceVariant = false,
            onCtaClick = { findNavController().navigate(R.id.providers) },
        )

    }

    /** Logo-only Room merges reuse the bound shelves. Returns true when submit can be skipped. */
    private fun homeUnchanged(
        categories: List<Category>,
        watchlistDigestEpisodes: List<Episode> = emptyList(),
    ): Boolean {
        val digestIds = watchlistDigestEpisodes.joinToString(",") { it.id }
        val trustSnap = com.betterstreamflix.app.providers.ProviderTrustLayer.snapshot()
        val signature = HomeCatalogPipeline.homeBindSignature(categories) +
            "\n" + (UserPreferences.currentProvider?.name ?: "") +
            "\n" + com.betterstreamflix.app.support.SupportPromptPolicy.shouldShowHomeCard() +
            "\n" + UserPreferences.supportEpisodesWatchedCount +
            "\n" + UserPreferences.homeShelfBlockOrder +
            "\n" + UserPreferences.homeShelfPinsJson +
            "\n" + com.betterstreamflix.app.providers.ShelfWeather.healthSmokeDigest() +
            "\n" + com.betterstreamflix.app.watchlist.WatchlistDigestPolicy.shouldShow(
                watchlistDigestEpisodes,
            ) +
            "\n" + digestIds +
            "\n" + com.betterstreamflix.app.providers.ProviderOutagePolicy.shouldShow(
                trustSnap,
                requireContext(),
            ) +
            "\n" + trustSnap.outageProviderNames.joinToString() +
            "\n" + trustSnap.outageLevel
        if (signature == homeBindSignature && appAdapter.items.isNotEmpty()) return true
        homeBindSignature = signature
        return false
    }

    private class HomeRowSpacing(
        private val gapPx: Int,
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val pos = parent.getChildAdapterPosition(view)
            val count = parent.adapter?.itemCount ?: return
            if (pos == RecyclerView.NO_POSITION) return
            val items = (parent.adapter as? AppAdapter)?.items
            val item = items?.getOrNull(pos)
            val featured = item is Category && Category.isFeaturedName(item.name)
            if (pos < count - 1) {
                outRect.bottom = if (featured) 0 else gapPx
            }
            // The shelf directly after the Featured hero needs real breathing
            // room — its title row would otherwise hug the hero's bottom scrim
            // and read as clipped/overlapping ("Latest Episodes" inside Featured).
            val prev = items?.getOrNull(pos - 1)
            if (prev is Category && Category.isFeaturedName(prev.name)) {
                outRect.top = gapPx * 3
            }
        }
    }

    private companion object {
        const val KEY_HOME_LIST_STATE = "home_list_state"
        const val KEY_HOME_LIST_OWNER = "home_list_owner"
    }
}
