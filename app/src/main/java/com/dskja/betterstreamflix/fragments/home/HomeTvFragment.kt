package com.dskja.betterstreamflix.fragments.home

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.databinding.FragmentHomeTvBinding
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.Http409CacheGuard
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider
import com.dskja.betterstreamflix.utils.LoggingUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.ProviderChangeNotifier
import com.dskja.betterstreamflix.utils.HomeCatalogPipeline
import com.dskja.betterstreamflix.ui.CinemaRoomController
import com.dskja.betterstreamflix.ui.FeaturedAdvancePolicy
import com.dskja.betterstreamflix.ui.FeaturedHeroController
import com.dskja.betterstreamflix.ui.FeaturedSwiperChrome
import com.dskja.betterstreamflix.ui.FeaturedTvRotation
import com.dskja.betterstreamflix.logo.FeaturedLogoEnrich
import com.dskja.betterstreamflix.utils.ArtworkUrls

class HomeTvFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentHomeTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by lazy {
        ViewModelProvider(this)[HomeViewModel::class.java]
    }

    private val appAdapter = AppAdapter()
    private var homeBindSignature: String? = null
    /** Cancels in-flight staggered home submitList posts when a newer catalog arrives. */
    private var homeSubmitToken: Any? = null
    private var cinemaRoomController: CinemaRoomController? = null

    /** True while Cinema Room Ambient owns the Home canvas (pauses Featured rotate). */
    fun isAmbientActive(): Boolean = cinemaRoomController?.isAmbientActive() == true

    /** Reset idle / exit ambient — called from [MainTvActivity] key path. */
    fun onAmbientUserActivity() {
        cinemaRoomController?.onUserActivity()
    }

    /** Non-key interaction idle reset (does not exit an active Ambient session). */
    fun onAmbientIdleClockReset() {
        cinemaRoomController?.resetIdleClock()
    }

    /** Pref / Live Command / Stick Energy flipped while Ambient may be active. */
    fun onAmbientGatesChanged() {
        cinemaRoomController?.recheckGatesWhileActive()
    }

    /**
     * @return true if Ambient fully handled the key (e.g. BACK while active).
     */
    fun onAmbientKeyEvent(event: KeyEvent): Boolean =
        cinemaRoomController?.onKeyEvent(event) == true

    /** Dialogs / loading that must block Ambient enter. */
    fun isAmbientModalBlocking(): Boolean {
        val act = activity as? com.dskja.betterstreamflix.activities.main.MainTvActivity
        if (act?.isAmbientModalBlocking() == true) return true
        val loading = _binding?.isLoading?.root
        if (loading != null && loading.visibility == View.VISIBLE) return true
        return false
    }

    /** Shared pool for nested shelf HorizontalGridViews (parity with mobile shelfPool). */
    val shelfPool: RecyclerView.RecycledViewPool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(AppAdapter.Type.MOVIE_TV_ITEM.ordinal, 24)
        setMaxRecycledViews(AppAdapter.Type.TV_SHOW_TV_ITEM.ordinal, 24)
        setMaxRecycledViews(AppAdapter.Type.EPISODE_TV_ITEM.ordinal, 12)
        setMaxRecycledViews(AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM.ordinal, 12)
        setMaxRecycledViews(AppAdapter.Type.EPISODE_CONTINUE_WATCHING_TV_ITEM.ordinal, 12)
        setMaxRecycledViews(AppAdapter.Type.CATEGORY_TV_ITEM.ordinal, 6)
    }

    private val swiperHandler = Handler(Looper.getMainLooper())
    private var isBackgroundPinned = false
    var featuredChromeFocused = false
        private set

    fun setFeaturedChromeFocused(focused: Boolean) {
        featuredChromeFocused = focused
        if (!focused) resetSwiperSchedule()
    }

    fun isFeaturedChromeFocused(): Boolean = featuredChromeFocused

    /** Public handoff for splash / side-nav / Back → Featured Watch. */
    fun requestContentFocus(): Boolean {
        val grid = _binding?.vgvHome ?: return false
        if (!grid.isAttachedToWindow || (grid.adapter?.itemCount ?: 0) == 0) return false
        focusHomeContent()
        return true
    }

    /**
     * Clean Exit: restore the exact Home tile that launched the player.
     * Falls back to Featured Watch / first shelf when the token is stale.
     */
    fun restoreCleanExitFocus(): Boolean {
        val grid = _binding?.vgvHome ?: return false
        if (!grid.isAttachedToWindow || (grid.adapter?.itemCount ?: 0) == 0) return false
        if (!com.dskja.betterstreamflix.player.PlayerCleanExit.shouldRestoreHomeFocus()) return false
        val token = com.dskja.betterstreamflix.player.PlayerCleanExit.peekHomeFocusToken()
            ?: return false
        grid.post {
            // FA-010: search ambient overlay + Home grid for Clean Exit tags.
            val ambient = _binding?.root?.findViewById<View>(R.id.ambient_overlay)
                ?: _binding?.root?.findViewById<View>(R.id.ambient_root)
            val homeRoot = _binding?.vgvHome
            val landed = (
                ambient != null &&
                    com.dskja.betterstreamflix.player.PlayerCleanExit
                        .restoreHomeFocus(ambient, token)
                ) || (
                homeRoot != null &&
                    com.dskja.betterstreamflix.player.PlayerCleanExit
                        .restoreHomeFocus(homeRoot, token)
                )
            com.dskja.betterstreamflix.player.PlayerCleanExit.consumeHomeFocusRestore()
            if (!landed) focusHomeContent()
        }
        return true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeHome()
        refreshProfileChip()
        cinemaRoomController = CinemaRoomController(
            fragment = this,
            root = binding.root,
            homeContent = binding.vgvHome,
            homeChrome = listOfNotNull(
                binding.root.findViewById(R.id.tv_home_profile_chip),
                binding.root.findViewById(R.id.tv_home_catalog_warning),
            ),
            modalBlocking = { isAmbientModalBlocking() },
        )

        // Single HomeViewModel (no provider key). ViewModel owns getHome on provider change;
        // this collector only binds UI and resets Featured timers when the active provider flips.
        viewLifecycleOwner.lifecycleScope.launch {
            merge(
                flowOf(Unit),
                ProviderChangeNotifier.providerChangeFlow,
            ).flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collectLatest {
                    // Drop pending Featured advances from the previous provider/load.
                    swiperHandler.removeCallbacksAndMessages(null)
                    viewModel.state.collect { state ->
                        when (state) {
                            HomeViewModel.State.Loading -> {
                                com.dskja.betterstreamflix.utils.TvLoadingOverlay.show(binding.isLoading.root)
                                binding.isLoading.gIsLoadingRetry.visibility = View.GONE
                                hideCatalogWarning()
                            }
                            is HomeViewModel.State.SuccessLoading -> {
                                val hadCatalog = (binding.vgvHome.adapter?.itemCount ?: 0) > 0
                                val focusInsideHome = binding.vgvHome.hasFocus() ||
                                    binding.vgvHome.findFocus() != null
                                displayHome(state.categories, state.watchlistDigestEpisodes)
                                binding.vgvHome.visibility = View.VISIBLE
                                com.dskja.betterstreamflix.utils.TvLoadingOverlay.hide(binding.isLoading.root)
                                showCatalogWarning(state.providerWarning)
                                // Logo-only Room merges rematerialize SuccessLoading — don't
                                // yank DPAD mid-scroll when focus is already on Home content.
                                // FA-009: Ambient strip focus lives outside vgvHome — never steal it.
                                if (!isAmbientActive() && (!hadCatalog || !focusInsideHome)) {

                                    focusHomeContent()
                                }
                            }
                            is HomeViewModel.State.FailedLoading -> {
                                if (http409Guard.handle(requireContext(), state.error) { viewModel.getHome() }) {
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
                                    btnIsLoadingRetry.setOnClickListener { viewModel.getHome() }
                                    btnIsLoadingClearCache.setOnClickListener {
                                        CacheUtils.clearAppCache(requireContext())
                                        com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(requireContext(), getString(com.dskja.betterstreamflix.R.string.clear_cache_done), com.dskja.betterstreamflix.R.string.loading_error_clear_cache)
                                        viewModel.getHome()
                                    }
                                    btnIsLoadingErrorDetails.setOnClickListener {
                                        LoggingUtils.showErrorDialog(requireContext(), state.error)
                                    }
                                    binding.vgvHome.visibility = View.GONE
                                    btnIsLoadingRetry.requestFocus()
                                }
                            }
                        }
                    }
                }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshProfileChip()
        com.dskja.betterstreamflix.ui.TrailerPlaybackController.silenceAllActive()
        // FA-021: Clean Exit restore is owned solely by MainTvActivity destination
        // listener (focusHomeAfterCleanExit). Ambient exit still calls
        // restoreCleanExitFocus() when leaving Ambient. Do not race here.
        // Returning from detail often leaves DPAD on side nav; restore content focus.
        val grid = _binding?.vgvHome
        val contentVisible = grid != null &&
            grid.visibility == View.VISIBLE &&
            (grid.adapter?.itemCount ?: 0) > 0 &&
            _binding?.isLoading?.root?.visibility != View.VISIBLE
        val navHasFocus = activity?.findViewById<View>(R.id.nav_main)?.hasFocus() == true ||
            activity?.findViewById<View>(R.id.nav_main)?.findFocus() != null
        if (contentVisible && !navHasFocus && grid?.hasFocus() != true && grid?.findFocus() == null) {
            requestContentFocus()
        }
    }
    
    // Restart the carousel when data is already loaded and the fragment is visible.
    override fun onStart() {
        super.onStart()
        appAdapter.items
            .filterIsInstance<Category>()
            .firstOrNull { Category.isFeaturedName(it.name) }
            ?.let {
                resetSwiperSchedule()
            }
        cinemaRoomController?.onStart()
    }

    override fun onPause() {
        // Series Fast-Forward: commit visit watermark only when leaving Home
        // (not when opening Movie/TV Detail — that was emptying FF shelves).
        if (!isPausedForDetailNavigation()) {
            viewModel.commitHomeVisit()
        }
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

    override fun onStop() {
        cinemaRoomController?.onStop()
        super.onStop()
        swiperHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        cinemaRoomController?.destroy()
        cinemaRoomController = null
        swiperHandler.removeCallbacksAndMessages(null)
        _binding?.let { appAdapter.onSaveInstanceState(it.vgvHome) }
        _binding = null
        super.onDestroyView()
    }

    private fun showCatalogWarning(warning: String?) {
        val banner = _binding?.tvHomeCatalogWarning ?: return
        val text = warning?.takeIf { it.isNotBlank() }
        if (text == null) {
            hideCatalogWarning()
            return
        }
        banner.visibility = View.VISIBLE
        banner.isFocusable = true
        banner.isFocusableInTouchMode = true
        banner.isClickable = true
        banner.nextFocusDownId = R.id.btn_swiper_watch_now
        banner.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
                return@setOnKeyListener false
            }
            focusHomeContent()
            true
        }
        banner.nextFocusRightId = R.id.tv_home_profile_chip
        banner.text = text
        banner.contentDescription = getString(R.string.home_catalog_warning_tap_retry)
        banner.setOnClickListener { viewModel.getHome() }
    }

    private fun hideCatalogWarning() {
        _binding?.tvHomeCatalogWarning?.apply {
            if (isFocused) clearFocus()
            isFocusable = false
            isFocusableInTouchMode = false
            visibility = View.GONE
            setOnClickListener(null)
        }
    }

    private fun refreshProfileChip() {
        com.dskja.betterstreamflix.ui.HomeProfileChip.refresh(
            fragment = this,
            root = _binding?.root,
            showName = true,
        )
        _binding?.root?.findViewById<View>(R.id.tv_home_profile_chip)?.let { chip ->
            chip.isFocusableInTouchMode = true
            // Watch is created with the Featured row. Point at it by id so DPAD
            // down does not land on the grid container and die there.
            chip.nextFocusDownId = R.id.btn_swiper_watch_now
            chip.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
                    return@setOnKeyListener false
                }
                focusHomeContent()
                true
            }
        }
    }

    private var swiperHasLastFocus: Boolean = false
    fun updateBackground(uri: String?, swiperHasFocus: Boolean? = false) {
        if (swiperHasFocus == null && isBackgroundPinned) return
        if (swiperHasFocus == null && !swiperHasLastFocus) return
        val target = _binding?.ivHomeBackground ?: return
        if (!target.isAttachedToWindow) return
        loadFeaturedBackdrop(target, uri)
        swiperHasLastFocus = swiperHasFocus ?: swiperHasLastFocus
    }

    fun pinBackground(uri: String?) {
        isBackgroundPinned = true
        val target = _binding?.ivHomeBackground ?: return
        if (!target.isAttachedToWindow) return
        loadFeaturedBackdrop(target, uri)
    }

    private fun loadFeaturedBackdrop(target: android.widget.ImageView, uri: String?) {
        val url = ArtworkUrls.preferFeatured(uri) ?: uri
        val (w, h) = FeaturedSwiperChrome.tvBackdropOverride(target.context)
        var request = Glide.with(target)
            .load(url)
            .override(w, h)
            .centerCrop()
            .error(R.drawable.glide_fallback_cover)
        if (!DeviceCapabilities.shouldReduceHomeEffects(target.context)) {
            request = request.transition(DrawableTransitionOptions.withCrossFade(180))
        } else {
            request = request.dontAnimate()
        }
        request.into(target)
    }

    fun releasePinnedBackground() {
        if (!isBackgroundPinned) return
        isBackgroundPinned = false
        syncFeaturedBackground()
    }

    private fun initializeHome() {
        com.dskja.betterstreamflix.utils.ExpPressEffects.wireLoadingRetry(binding.isLoading.root)
        binding.vgvHome.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            itemAnimator = null
            // Fewer off-screen category rows retained → fewer nested HGV inflates during scroll.
            setItemViewCacheSize(3)
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setItemSpacing(
                com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.dpToPx(
                    this,
                    com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.itemSpacingDp(),
                ),
            )
            // Do not requestFocus while the adapter is empty — Leanback then loses the
            // focus target and DPAD stays trapped on the side nav until Movies/etc.
        }
    }

    /**
     * After shelves bind, land DPAD on Featured Watch (or the first shelf tile).
     * Home is a nested VerticalGridView of rows (unlike Movies' flat poster grid),
     * so geometric focus search from [nav_main] often fails without an explicit handoff.
     */
    private fun focusHomeContent() {
        val grid = _binding?.vgvHome ?: return
        if (!grid.isAttachedToWindow || grid.adapter?.itemCount == 0) return
        grid.post {
            val b = _binding ?: return@post
            if (b.isLoading.root.visibility == View.VISIBLE) return@post
            val featuredWatch = b.vgvHome.findViewById<View>(R.id.btn_swiper_watch_now)
            val landed = when {
                featuredWatch != null &&
                    featuredWatch.isShown &&
                    featuredWatch.isFocusable -> featuredWatch.requestFocus()
                else -> false
            }
            if (!landed) {
                val shelf = firstShelfFocusable(b.vgvHome)
                if (shelf != null) shelf.requestFocus() else b.vgvHome.requestFocus()
            }
        }
    }

    private fun firstShelfFocusable(root: ViewGroup): View? {
        val grids = ArrayList<View>()
        fun walk(view: View) {
            if (view.id == R.id.hgv_category && view.isShown) grids.add(view)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        for (grid in grids) {
            val child = if (grid is ViewGroup && grid.childCount > 0) grid.getChildAt(0) else grid
            if (child.isShown && child.isFocusable) return child
        }
        val outageCta = root.findViewById<View>(R.id.btn_provider_outage_cta)
        if (outageCta != null && outageCta.isShown && outageCta.isFocusable) return outageCta
        val digestCta = root.findViewById<View>(R.id.btn_watchlist_digest_cta)
        if (digestCta != null && digestCta.isShown && digestCta.isFocusable) return digestCta
        val banner = root.findViewById<View>(R.id.btn_support_banner_cta)
        if (banner != null && banner.isShown && banner.isFocusable) return banner
        return null
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
            "\n" + UserPreferences.homeShelfPinsJson +
            "\n" + com.dskja.betterstreamflix.providers.ShelfWeather.healthSmokeDigest() +
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

    private fun displayHome(
        categories: List<Category>,
        watchlistDigestEpisodes: List<Episode> = emptyList(),
    ) {
        categories
            .find { Category.isFeaturedName(it.name) }
            ?.also { featured ->
                val previous = appAdapter.items
                    .filterIsInstance<Category>()
                    .find { item -> Category.isFeaturedName(item.name) }
                val index = previous?.selectedIndex ?: 0
                featured.selectedIndex = FeaturedTvRotation.coerceIndex(index, featured.list.size)

                // Logo-only Room merges rematerialize SuccessLoading; don't restart the
                // Featured timer or thrash the background unless the shelf identity changed.
                val previousIds = previous?.list?.map(::featuredItemKey)
                val nextIds = featured.list.map(::featuredItemKey)
                val shelfIdentityChanged = previousIds != nextIds
                val poster = FeaturedTvRotation.bannerOf(featured.list.getOrNull(featured.selectedIndex))
                val previousPoster = FeaturedTvRotation.bannerOf(
                    previous?.list?.getOrNull(previous.selectedIndex),
                )
                if (poster != null && (shelfIdentityChanged || poster != previousPoster)) {
                    updateBackground(poster, null)
                }
                if (shelfIdentityChanged) {
                    resetSwiperSchedule()
                }
            }

        categories
            .find { it.name == Category.CONTINUE_WATCHING }
            ?.also {
                it.name = getString(R.string.home_continue_watching)
                it.list.forEach { show ->
                    when (show) {
                        is Episode -> show.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_TV_ITEM
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.NEXT_EPISODES }
            ?.also {
                it.name = getString(R.string.home_next_episodes)
                it.list.forEach { show ->
                    if (show is Episode) {
                        show.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_TV_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.NEW_THIS_WEEK }
            ?.also {
                it.name = getString(R.string.home_new_this_week)
                it.list.forEach { show ->
                    if (show is Episode) {
                        show.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_TV_ITEM
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
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                        is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
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
            val withoutBanner = appAdapter.items.filterNot {
                it is com.dskja.betterstreamflix.support.SupportBannerItem
            }
            appAdapter.submitList(withoutBanner)
        }
        appAdapter.onWatchlistDigestClickListener = { banner ->
            viewLifecycleOwner.lifecycleScope.launch {
                val result = com.dskja.betterstreamflix.watchlist.WatchlistDigestQueue.apply(
                    requireContext(),
                    banner.episodes,
                )
                val first = result.firstEpisode
                val navigated = if (first != null) {
                    com.dskja.betterstreamflix.watchlist.WatchlistDigestQueue.navigateToEpisode(
                        findNavController(),
                        requireContext(),
                        first,
                    )
                } else {
                    false
                }
                if (result.seededCount <= 0 && !navigated) return@launch
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
        val hasContinueWatching = categories.any {
            it.name == getString(R.string.home_continue_watching) && it.list.isNotEmpty()
        }
        val visibleCategories = HomeCatalogPipeline.isolateFeatured(categories)
        val resumeShelfTitles = setOf(
            getString(R.string.home_continue_watching),
            getString(R.string.home_next_episodes),
            getString(R.string.home_new_this_week),
        )
        visibleCategories
            .onEach { category ->
                if (!Category.isFeaturedName(category.name) &&
                    category.name !in resumeShelfTitles
                ) {
                    category.list.forEach { show ->
                        when (show) {
                            is Episode -> show.itemType = AppAdapter.Type.EPISODE_TV_ITEM
                            is Movie -> show.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                            is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                        }
                    }
                }
                category.itemSpacing = com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.dpToPx(
                    binding.root,
                    com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.itemSpacingDp(),
                )
                category.itemType = when {
                    Category.isFeaturedName(category.name) -> AppAdapter.Type.CATEGORY_TV_SWIPER
                    else -> AppAdapter.Type.CATEGORY_TV_ITEM
                }
            }
        cinemaRoomController?.onCategoriesBound(visibleCategories)
        if (homeUnchanged(visibleCategories, watchlistDigestEpisodes)) return
        val showDigest = com.dskja.betterstreamflix.watchlist.WatchlistDigestPolicy.shouldShow(
            watchlistDigestEpisodes,
        )
        val trustSnapshot = com.dskja.betterstreamflix.providers.ProviderTrustLayer.snapshot()
        val showOutage = com.dskja.betterstreamflix.providers.ProviderOutagePolicy.shouldShow(
            trustSnapshot,
            requireContext(),
        )
        val outageNames = com.dskja.betterstreamflix.providers.ProviderOutagePolicy
            .relevantOutageNames(trustSnapshot)
        var digestRecorded = false
        visibleCategories
            .forEach { category ->
                homeItems.add(category)
                // CW was already renamed to the localized title; don't use the English constant.
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
                                providerNames = outageNames.ifEmpty { trustSnapshot.outageProviderNames },
                                level = trustSnapshot.outageLevel,
                            ).apply {
                                itemType = AppAdapter.Type.PROVIDER_OUTAGE_BANNER_TV_ITEM
                            }
                        )
                    }
                    if (showDigest &&
                        homeItems.none {
                            it is com.dskja.betterstreamflix.watchlist.WatchlistDigestBannerItem
                        }
                    ) {
                        homeItems.add(
                            com.dskja.betterstreamflix.watchlist.WatchlistDigestBannerItem(
                                episodes = watchlistDigestEpisodes,
                            ).apply {
                                itemType = AppAdapter.Type.WATCHLIST_DIGEST_TV_ITEM
                            }
                        )
                        if (!digestRecorded) {
                            com.dskja.betterstreamflix.watchlist.WatchlistDigestPolicy.recordShown()
                            digestRecorded = true
                        }
                    }
                    if (com.dskja.betterstreamflix.support.SupportPromptPolicy.shouldShowHomeCard() &&
                        homeItems.none { it is com.dskja.betterstreamflix.support.SupportBannerItem }
                    ) {
                        homeItems.add(
                            com.dskja.betterstreamflix.support.SupportBannerItem().apply {
                                itemType = AppAdapter.Type.SUPPORT_BANNER_TV_ITEM
                            }
                        )
                    }
                }
            }

        if (showOutage &&
            homeItems.none {
                it is com.dskja.betterstreamflix.providers.ProviderOutageBannerItem
            } &&
            homeItems.isNotEmpty()
        ) {
            homeItems.add(
                1.coerceAtMost(homeItems.size),
                com.dskja.betterstreamflix.providers.ProviderOutageBannerItem(
                    providerNames = outageNames.ifEmpty { trustSnapshot.outageProviderNames },
                    level = trustSnapshot.outageLevel,
                ).apply {
                    itemType = AppAdapter.Type.PROVIDER_OUTAGE_BANNER_TV_ITEM
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
                    itemType = AppAdapter.Type.WATCHLIST_DIGEST_TV_ITEM
                }
            )
            if (!digestRecorded) {
                com.dskja.betterstreamflix.watchlist.WatchlistDigestPolicy.recordShown()
                digestRecorded = true
            }
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
                    itemType = AppAdapter.Type.SUPPORT_BANNER_TV_ITEM
                }
            )
        }

        // Stagger VerticalGridView category-row inflate: Featured (+ banners) + a couple
        // of shelves first, then the rest on the next frame (BETTERSTREAMFLIX-3S/20).
        val token = Any()
        homeSubmitToken = token
        val initialRows = homeItems.take(HOME_INITIAL_ROWS)
        appAdapter.submitList(initialRows)
        if (homeItems.size > HOME_INITIAL_ROWS) {
            binding.vgvHome.post {
                if (homeSubmitToken !== token) return@post
                if (!isAdded || _binding == null) return@post
                appAdapter.submitList(homeItems)
            }
        }

        val hasCatalogRows = homeItems.any { it is Category }
        ExpEmptyChrome.bind(
            emptyView = binding.root.findViewById(R.id.tv_home_empty),
            emptyRule = binding.root.findViewById(R.id.v_home_empty_rule),
            emptyCta = binding.root.findViewById(R.id.btn_home_empty_cta),
            visible = !hasCatalogRows,
            tintOnSurfaceVariant = false,
            onCtaClick = { runCatching { findNavController().navigate(R.id.providers) } },
        )
    }

    companion object {
        /** First-pass Home rows (Featured + CW/banners + ~2 shelves) before full catalog. */
        private const val HOME_INITIAL_ROWS = 4
    }

    private fun featuredItemKey(item: AppAdapter.Item): String = when (item) {
        is Movie -> "movie:${item.id}"
        is TvShow -> "tv:${item.id}"
        else -> item.javaClass.name
    }

    fun resetSwiperSchedule() {
        swiperHandler.removeCallbacksAndMessages(null)
        if (!isAdded) return
        val ctx = context ?: return
        if (!FeaturedAdvancePolicy.shouldAutoAdvance(ctx)) return
        if (isAmbientActive()) return
        swiperHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isAdded) return
                val ctx2 = context ?: return
                if (!FeaturedAdvancePolicy.shouldAutoAdvance(ctx2)) {
                    swiperHandler.removeCallbacksAndMessages(null)
                    return
                }
                if (isAmbientActive()) {
                    swiperHandler.removeCallbacksAndMessages(null)
                    return
                }
                if (isBackgroundPinned || featuredChromeFocused) {
                    swiperHandler.postDelayed(this, 8_000)
                    return
                }

                val position = appAdapter.items
                    .filterIsInstance<Category>()
                    .find { Category.isFeaturedName(it.name) }
                    ?.let { category ->
                        if (category.list.isEmpty()) {
                            return@let null
                        }
                        category.selectedIndex = FeaturedTvRotation.nextIndex(
                            category.selectedIndex,
                            category.list.size,
                        )

                        // Update background when swiper rotates automatically
                        val poster = FeaturedTvRotation.bannerOf(
                            category.list.getOrNull(category.selectedIndex),
                        )
                        // Update background if it's not null
                        if (poster != null) {
                            updateBackground(poster, null)
                        }

                        // Prefetch the following banner so rotation doesn't flash.
                        val nextPoster = FeaturedTvRotation.bannerOf(
                            category.list.getOrNull(
                                FeaturedTvRotation.nextIndex(
                                    category.selectedIndex,
                                    category.list.size,
                                ),
                            ),
                        )
                        if (nextPoster != null) {
                            FeaturedLogoEnrich.prefetchBanner(requireContext(), nextPoster)
                        }

                        appAdapter.items.indexOf(category)
                    }
                    ?.takeIf { it != -1 }

                if (position == null) {
                    swiperHandler.removeCallbacksAndMessages(null)
                    return
                }

                appAdapter.notifyItemChanged(position, FeaturedHeroController.PAYLOAD_ROTATE)
                swiperHandler.postDelayed(this, 8_000)
            }
        }, 8_000)
    }

    private fun syncFeaturedBackground() {
        val featured = appAdapter.items
            .filterIsInstance<Category>()
            .find { Category.isFeaturedName(it.name) }
            ?: return

        val poster = FeaturedTvRotation.bannerOf(
            featured.list.getOrNull(featured.selectedIndex),
        )

        if (poster != null) {
            updateBackground(poster, null)
        }
    }
}
