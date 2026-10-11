package com.dskja.betterstreamflix.fragments.home

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
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.databinding.FragmentHomeMobileBinding
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.ui.HomeProfileChip
import com.dskja.betterstreamflix.ui.FeaturedTvRotation
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.Http409CacheGuard
import com.dskja.betterstreamflix.utils.ExpAmbientGlow
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpNavAutoHide
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.HomeCatalogPipeline
import com.dskja.betterstreamflix.utils.LoggingUtils
import com.dskja.betterstreamflix.utils.ProviderChangeNotifier
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

        initializeHome()

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
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                        hideCatalogWarning()
                    }
                    is HomeViewModel.State.SuccessLoading -> {
                        displayHome(state.categories, state.watchlistDigestEpisodes)
                        ExpMotion.fadeOutAndHide(binding.isLoading.root)
                        showCatalogWarning(state.providerWarning)
                    }
                    is HomeViewModel.State.FailedLoading -> {
                        if (http409Guard.handle(requireContext(), state.error) { viewModel.getHome() }) {
                                return@collect
                            }
                        if (!ExperimentalMobileDesign.enabled()) {
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
                            val doRetry = { viewModel.getHome() }
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

    override fun onResume() {
        super.onResume()
        refreshProviderLogo()
        refreshProfileChip()
        // Soft-resume featured auto-advance — avoid notifyItemChanged (full rebind glitches).
        _binding?.rvHome?.let { appAdapter.resumeCategorySwipers(it) }
        // Clean Exit: kill any leaked player/trailer audio on Home.
        com.dskja.betterstreamflix.ui.TrailerPlaybackController.silenceAllActive()
        com.dskja.betterstreamflix.player.PlaybackLifecycleGuard.stopActivePlayback()
        com.dskja.betterstreamflix.player.PlayerCleanExit.consumeHomeFocusRestore()
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
        _binding?.let { appAdapter.onSaveInstanceState(it.rvHome) }
        _binding = null
        super.onDestroyView()
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
        binding.root.findViewById<View>(R.id.btn_home_remix)?.setOnClickListener {
            ExpMotion.hapticTap(it)
            com.dskja.betterstreamflix.home.HomeRemixUi.show(this) {
                homeBindSignature = null
                viewModel.getHome()
            }
        }
        binding.ivProviderLogo.apply {
            isClickable = true
            isFocusable = true
            if (ExperimentalMobileDesign.enabled()) {
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { applyExpPress() }
            }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                findNavController().navigate(R.id.providers)
            }
        }
        
        // Default shell hides the background; experimental keeps a full-bleed hero plane.
        binding.ivHomeBackground.visibility =
            if (ExperimentalMobileDesign.enabled()) View.VISIBLE else View.GONE

        if (ExperimentalMobileDesign.enabled()) {
            if (ExperimentalMobileDesign.heroParallax()) {
                applyExperimentalParallax()
            }
            ExpNavAutoHide.attach(binding.root)
        com.dskja.betterstreamflix.utils.ExpPressEffects.wireLoadingRetry(binding.isLoading.root)
            refreshProviderChip()
            ExpMotion.brandReveal(
                binding.ivProviderLogo,
                binding.root.findViewById(R.id.tv_home_brand),
                binding.root.findViewById(R.id.tv_home_tagline),
                binding.root.findViewById(R.id.v_home_brand_rule),
            )
            ExpMotion.enterScreen(binding.root)
            ExperimentalMobileDesign.applyReducedGlass(binding.root)
            ExpMotion.kenBurns(
                binding.ivHomeBackground,
                drift = !ExperimentalMobileDesign.heroParallax(),
            )
            ExpMotion.staggerFirstFill(binding.rvHome)
        }
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
        refreshProviderChip()
    }

    private fun refreshProviderChip() {
        // Ink Lock: provider identity is the logo only — keep the hero uncluttered.
        if (!ExperimentalMobileDesign.enabled()) return
        _binding?.root?.findViewById<android.widget.TextView>(R.id.tv_home_provider_chip)
            ?.visibility = View.GONE
    }

    private fun refreshProfileChip() {
        HomeProfileChip.refresh(this, _binding?.root, showName = true)
    }

    private var heroScrollOffset = 0

    private fun applyExperimentalParallax() {
        binding.rvHome.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                heroScrollOffset += dy
                // Hero drifts up slower than content, glow/veil follow it.
                val parallax = (heroScrollOffset * 0.38f).coerceIn(0f, 900f)
                binding.ivHomeBackground.translationY = -parallax
                binding.root.findViewById<View>(R.id.v_home_atmosphere)
                    ?.translationY = -parallax
                val brandDrift = -parallax * 0.5f
                val brandAlpha = (1f - parallax / 340f).coerceIn(0f, 1f)
                listOf(
                    R.id.tv_home_brand,
                    R.id.tv_home_tagline,
                    R.id.v_home_brand_rule,
                    R.id.iv_provider_logo,
                    R.id.tv_home_profile_chip,
                ).forEach { id ->
                    binding.root.findViewById<View>(id)?.apply {
                        translationY = brandDrift
                        alpha = brandAlpha
                    }
                }
            }
        })
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
        if (ExperimentalMobileDesign.enabled()) {
            banner.setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
            val density = banner.resources.displayMetrics.density
            banner.setPadding(
                (14 * density).toInt(),
                (10 * density).toInt(),
                (14 * density).toInt(),
                (10 * density).toInt(),
            )
            with(com.dskja.betterstreamflix.utils.ExpPressEffects) { banner.applyExpPress() }
            if (banner.visibility != View.VISIBLE) {
                banner.visibility = View.VISIBLE
                ExpMotion.popIn(banner)
            } else {
                banner.visibility = View.VISIBLE
            }
        } else {
            banner.visibility = View.VISIBLE
        }
        banner.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.getHome()
        }
    }

    private fun hideCatalogWarning() {
        _binding?.root?.findViewById<android.widget.TextView>(R.id.tv_home_catalog_warning)?.apply {
            if (ExperimentalMobileDesign.enabled() && visibility == View.VISIBLE) {
                ExpMotion.fadeOutAndHide(this)
            } else {
                visibility = View.GONE
            }
            setOnClickListener(null)
        }
    }

    private fun displayHome(
        categories: List<Category>,
        watchlistDigestEpisodes: List<Episode> = emptyList(),
    ) {
        if (ExperimentalMobileDesign.enabled()) {
            updateExperimentalHero(categories)
        }

        categories
            .find { Category.isFeaturedName(it.name) }
            ?.also {
                it.list.forEach { show ->
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
            com.dskja.betterstreamflix.support.SupportPromptPolicy.openHomeCard()
            runCatching { findNavController().navigate(R.id.support) }
        }
        appAdapter.onSupportBannerDismissListener = {
            com.dskja.betterstreamflix.support.SupportPromptPolicy.dismissHomeCard()
            val withoutBanner = appAdapter.items.filterNot { it is com.dskja.betterstreamflix.support.SupportBannerItem }
            appAdapter.submitList(withoutBanner)
        }
        appAdapter.onWatchlistDigestClickListener = { banner ->
            viewLifecycleOwner.lifecycleScope.launch {
                val result = com.dskja.betterstreamflix.watchlist.WatchlistDigestQueue.apply(
                    requireContext(),
                    banner.episodes,
                )
                val first = result.firstEpisode
                if (first != null) {
                    com.dskja.betterstreamflix.watchlist.WatchlistDigestQueue.navigateToEpisode(
                        findNavController(),
                        requireContext(),
                        first,
                    )
                }
                val without = appAdapter.items.filterNot {
                    it is com.dskja.betterstreamflix.watchlist.WatchlistDigestBannerItem
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
            com.dskja.betterstreamflix.watchlist.WatchlistDigestPolicy.dismiss()
            val without = appAdapter.items.filterNot {
                it is com.dskja.betterstreamflix.watchlist.WatchlistDigestBannerItem
            }
            appAdapter.submitList(without)
        }
        appAdapter.onProviderOutageBannerClickListener = {
            com.dskja.betterstreamflix.fragments.settings.SettingsDeepLink.openProviderTrust()
            runCatching { findNavController().navigate(R.id.settings) }
        }
        appAdapter.onProviderOutageBannerDismissListener = {
            com.dskja.betterstreamflix.providers.ProviderOutagePolicy.dismiss(requireContext())
            val without = appAdapter.items.filterNot {
                it is com.dskja.betterstreamflix.providers.ProviderOutageBannerItem
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
        val showDigest = com.dskja.betterstreamflix.watchlist.WatchlistDigestPolicy.shouldShow(
            watchlistDigestEpisodes,
        )
        val trustSnapshot = com.dskja.betterstreamflix.providers.ProviderTrustLayer.snapshot()
        val showOutage = com.dskja.betterstreamflix.providers.ProviderOutagePolicy.shouldShow(
            trustSnapshot,
            requireContext(),
        )
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
                        it is com.dskja.betterstreamflix.providers.ProviderOutageBannerItem
                    }
                ) {
                    homeItems.add(
                        com.dskja.betterstreamflix.providers.ProviderOutageBannerItem(
                            providerNames = trustSnapshot.outageProviderNames,
                            level = trustSnapshot.outageLevel,
                        ).apply {
                            itemType = AppAdapter.Type.PROVIDER_OUTAGE_BANNER_MOBILE_ITEM
                        }
                    )
                }
                if (showDigest &&
                    homeItems.none { it is com.dskja.betterstreamflix.watchlist.WatchlistDigestBannerItem }
                ) {
                    homeItems.add(
                        com.dskja.betterstreamflix.watchlist.WatchlistDigestBannerItem(
                            episodes = watchlistDigestEpisodes,
                        ).apply {
                            itemType = AppAdapter.Type.WATCHLIST_DIGEST_MOBILE_ITEM
                        }
                    )
                }
                if (com.dskja.betterstreamflix.support.SupportPromptPolicy.shouldShowHomeCard() &&
                    homeItems.none { it is com.dskja.betterstreamflix.support.SupportBannerItem }
                ) {
                    homeItems.add(
                        com.dskja.betterstreamflix.support.SupportBannerItem().apply {
                            itemType = AppAdapter.Type.SUPPORT_BANNER_MOBILE_ITEM
                        }
                    )
                }
            }
        }

        // Fallback: if no featured/continue rows, append near the top after first category.
        if (showOutage &&
            homeItems.none {
                it is com.dskja.betterstreamflix.providers.ProviderOutageBannerItem
            } &&
            homeItems.isNotEmpty()
        ) {
            homeItems.add(
                1.coerceAtMost(homeItems.size),
                com.dskja.betterstreamflix.providers.ProviderOutageBannerItem(
                    providerNames = trustSnapshot.outageProviderNames,
                    level = trustSnapshot.outageLevel,
                ).apply {
                    itemType = AppAdapter.Type.PROVIDER_OUTAGE_BANNER_MOBILE_ITEM
                }
            )
        }
        if (showDigest &&
            homeItems.none { it is com.dskja.betterstreamflix.watchlist.WatchlistDigestBannerItem } &&
            homeItems.isNotEmpty()
        ) {
            val insertAt = when {
                homeItems.any {
                    it is com.dskja.betterstreamflix.providers.ProviderOutageBannerItem
                } ->
                    homeItems.indexOfFirst {
                        it is com.dskja.betterstreamflix.providers.ProviderOutageBannerItem
                    } + 1
                else -> 1.coerceAtMost(homeItems.size)
            }
            homeItems.add(
                insertAt.coerceAtMost(homeItems.size),
                com.dskja.betterstreamflix.watchlist.WatchlistDigestBannerItem(
                    episodes = watchlistDigestEpisodes,
                ).apply {
                    itemType = AppAdapter.Type.WATCHLIST_DIGEST_MOBILE_ITEM
                }
            )
        }
        if (com.dskja.betterstreamflix.support.SupportPromptPolicy.shouldShowHomeCard() &&
            homeItems.none { it is com.dskja.betterstreamflix.support.SupportBannerItem } &&
            homeItems.isNotEmpty()
        ) {
            val insertAt = when {
                homeItems.any { it is com.dskja.betterstreamflix.watchlist.WatchlistDigestBannerItem } ->
                    homeItems.indexOfFirst {
                        it is com.dskja.betterstreamflix.watchlist.WatchlistDigestBannerItem
                    } + 1
                homeItems.any {
                    it is com.dskja.betterstreamflix.providers.ProviderOutageBannerItem
                } ->
                    homeItems.indexOfFirst {
                        it is com.dskja.betterstreamflix.providers.ProviderOutageBannerItem
                    } + 1
                else -> 1.coerceAtMost(homeItems.size)
            }
            homeItems.add(
                insertAt.coerceAtMost(homeItems.size),
                com.dskja.betterstreamflix.support.SupportBannerItem().apply {
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

        if (ExperimentalMobileDesign.enabled()) {
            // One-shot enter only — skip continuous kenburns on the hero (expensive on mid devices).
            ExpMotion.startAnimation(binding.rvHome, R.anim.exp_fade_slide_up)
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_home_brand_rule))
        }
    }

    /** Logo-only Room merges reuse the bound shelves. Returns true when submit can be skipped. */
    private fun homeUnchanged(
        categories: List<Category>,
        watchlistDigestEpisodes: List<Episode> = emptyList(),
    ): Boolean {
        val digestIds = watchlistDigestEpisodes.joinToString(",") { it.id }
        val trustSnap = com.dskja.betterstreamflix.providers.ProviderTrustLayer.snapshot()
        val signature = HomeCatalogPipeline.homeBindSignature(categories) +
            "\n" + (UserPreferences.currentProvider?.name ?: "") +
            "\n" + com.dskja.betterstreamflix.support.SupportPromptPolicy.shouldShowHomeCard() +
            "\n" + UserPreferences.supportEpisodesWatchedCount +
            "\n" + UserPreferences.homeShelfBlockOrder +
            "\n" + com.dskja.betterstreamflix.watchlist.WatchlistDigestPolicy.shouldShow(
                watchlistDigestEpisodes,
            ) +
            "\n" + digestIds +
            "\n" + com.dskja.betterstreamflix.providers.ProviderOutagePolicy.shouldShow(
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
            if (pos == RecyclerView.NO_POSITION || pos >= count - 1) return
            val item = (parent.adapter as? AppAdapter)?.items?.getOrNull(pos)
            val featured = item is Category && Category.isFeaturedName(item.name)
            outRect.bottom = if (featured) 0 else gapPx
        }
    }

    private var currentHeroArt: String? = null

    private fun updateExperimentalHero(categories: List<Category>) {
        val featuredCat = categories.find { Category.isFeaturedName(it.name) }
        val index = FeaturedTvRotation.coerceIndex(
            featuredCat?.selectedIndex ?: 0,
            featuredCat?.list?.size ?: 0,
        )
        val featured = featuredCat?.list?.getOrNull(index)
        updateExperimentalHeroArt(featured as? com.dskja.betterstreamflix.models.Show)
    }

    /** Keeps the hero backdrop + ambient glow in sync with the featured swiper. */
    fun updateExperimentalHeroArt(show: com.dskja.betterstreamflix.models.Show?) {
        if (_binding == null || !ExperimentalMobileDesign.enabled()) return
        val art = when (show) {
            is Movie -> com.dskja.betterstreamflix.utils.ArtworkUrls
                .featuredBannerOrPoster(show.banner, show.poster)
            is TvShow -> com.dskja.betterstreamflix.utils.ArtworkUrls
                .featuredBannerOrPoster(show.banner, show.poster)
            else -> null
        }
        if (art == currentHeroArt) return
        currentHeroArt = art
        if (!art.isNullOrBlank()) {
            val (w, h) = com.dskja.betterstreamflix.ui.FeaturedSwiperChrome
                .artworkOverride(binding.ivHomeBackground)
            Glide.with(binding.ivHomeBackground)
                .load(art)
                .override(w, h)
                .transition(DrawableTransitionOptions.withCrossFade(450))
                .centerCrop()
                .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                    override fun onLoadFailed(
                        e: com.bumptech.glide.load.engine.GlideException?,
                        model: Any?,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                        isFirstResource: Boolean,
                    ) = false

                    override fun onResourceReady(
                        resource: android.graphics.drawable.Drawable,
                        model: Any,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                        dataSource: com.bumptech.glide.load.DataSource,
                        isFirstResource: Boolean,
                    ): Boolean {
                        val root = _binding?.root ?: return false
                        val bitmap = (resource as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        root.findViewById<View>(R.id.v_home_glow)?.let { glow ->
                            // Soft static tint only — avoid re-triggering glow fade on every swipe.
                            if (glow.tag != art) {
                                glow.tag = art
                                ExpAmbientGlow.apply(bitmap, glow)
                            }
                        }
                        return false
                    }
                })
                .into(binding.ivHomeBackground)
            // Kenburns removed: continuous scale animation caused jank on home scroll.
        } else {
            binding.ivHomeBackground.setImageResource(R.drawable.bg_exp_lumina_sky)
            binding.root.findViewById<View>(R.id.v_home_glow)?.let { glow ->
                glow.tag = null
                val primary = com.google.android.material.color.MaterialColors.getColor(
                    glow,
                    androidx.appcompat.R.attr.colorPrimary,
                )
                ExpAmbientGlow.applyColor(primary, glow)
            }
        }
    }
}
