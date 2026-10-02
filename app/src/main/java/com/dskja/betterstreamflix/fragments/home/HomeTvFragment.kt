package com.dskja.betterstreamflix.fragments.home

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.dskja.betterstreamflix.ui.FeaturedAdvancePolicy
import com.dskja.betterstreamflix.ui.FeaturedHeroController
import com.dskja.betterstreamflix.ui.FeaturedTvRotation
import com.dskja.betterstreamflix.logo.FeaturedLogoEnrich

class HomeTvFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentHomeTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by lazy {
        ViewModelProvider(this)[HomeViewModel::class.java]
    }

    private val appAdapter = AppAdapter()

    private val swiperHandler = Handler(Looper.getMainLooper())
    private var isBackgroundPinned = false
    var featuredChromeFocused = false
        private set

    fun setFeaturedChromeFocused(focused: Boolean) {
        featuredChromeFocused = focused
        if (!focused) resetSwiperSchedule()
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
                            HomeViewModel.State.Loading -> binding.isLoading.apply {
                                root.visibility = View.VISIBLE
                                root.isFocusable = true
                                root.isClickable = true
                                com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                                gIsLoadingRetry.visibility = View.GONE
                                hideCatalogWarning()
                            }
                            is HomeViewModel.State.SuccessLoading -> {
                                displayHome(state.categories)
                                binding.vgvHome.visibility = View.VISIBLE
                                binding.isLoading.root.apply {
                                    visibility = View.GONE
                                    // GONE alone is not enough on some Fire OS builds — a
                                    // lingering focusable full-screen loader blocks Home DPAD.
                                    isFocusable = false
                                    isFocusableInTouchMode = false
                                    isClickable = false
                                }
                                com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(
                                    binding.isLoading.root, false,
                                )
                                showCatalogWarning(state.providerWarning)
                                focusHomeContent()
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
    }

    override fun onStop() {
        super.onStop()
        swiperHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
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
        banner.nextFocusDownId = binding.vgvHome.id
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
        val chip = _binding?.root?.findViewById<View>(R.id.tv_home_profile_chip) ?: return
        val profile = com.dskja.betterstreamflix.profiles.ProfileManager.activeProfile()
        val name = profile?.displayName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.profile_default)
        chip.findViewById<android.widget.TextView>(R.id.tv_home_profile_name)?.text = name
        chip.findViewById<com.dskja.betterstreamflix.profiles.ProfileAvatarView>(R.id.pav_home_profile)
            ?.bind(
                avatarKey = profile?.avatarKey
                    ?: com.dskja.betterstreamflix.profiles.ProfileManager.avatarKeys.first(),
                displayName = name,
                textSizeSp = 12f,
            )
        chip.visibility = View.VISIBLE
        chip.isFocusable = true
        chip.isFocusableInTouchMode = true
        chip.isClickable = true
        chip.nextFocusDownId = binding.vgvHome.id
        chip.setOnClickListener {
            ExpMotion.hapticTap(it)
            com.dskja.betterstreamflix.fragments.settings.ProfilesSettingsController.showSwitchDialog(this) {
                requireActivity().apply {
                    finish()
                    startActivity(intent)
                }
            }
        }
    }

    private var swiperHasLastFocus: Boolean = false
    fun updateBackground(uri: String?, swiperHasFocus: Boolean? = false) {
        if (swiperHasFocus == null && isBackgroundPinned) return
        if (swiperHasFocus == null && !swiperHasLastFocus) return
        val target = _binding?.ivHomeBackground ?: return
        if (!target.isAttachedToWindow) return

        var request = Glide.with(target)
            .load(uri)
            .centerCrop()
            .thumbnail(0.25f)
        if (!DeviceCapabilities.shouldReduceHomeEffects(target.context)) {
            request = request.transition(DrawableTransitionOptions.withCrossFade(180))
        }
        request.into(target)
        swiperHasLastFocus = swiperHasFocus ?: swiperHasLastFocus
    }

    fun pinBackground(uri: String?) {
        isBackgroundPinned = true
        val target = _binding?.ivHomeBackground ?: return
        if (!target.isAttachedToWindow) return
        var request = Glide.with(target)
            .load(uri)
            .centerCrop()
            .thumbnail(0.25f)
        if (!DeviceCapabilities.shouldReduceHomeEffects(target.context)) {
            request = request.transition(DrawableTransitionOptions.withCrossFade(180))
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
            setItemSpacing(resources.getDimension(R.dimen.home_spacing).toInt() * 2)
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
            when {
                featuredWatch != null &&
                    featuredWatch.visibility == View.VISIBLE &&
                    featuredWatch.isFocusable -> featuredWatch.requestFocus()
                else -> b.vgvHome.requestFocus()
            }
        }
    }

    private fun displayHome(categories: List<Category>) {
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
            .find { it.name == Category.RECENTLY_WATCHED }
            ?.also {
                it.name = getString(R.string.home_recently_watched)
            }

        categories
            .find { it.name == Category.FAVORITE_MOVIES }
            ?.also { it.name = getString(R.string.home_favorite_movies) }

        categories
            .find { it.name == Category.FAVORITE_TV_SHOWS }
            ?.also { it.name = getString(R.string.home_favorite_tv_shows) }

        appAdapter.onSupportBannerClickListener = {
            runCatching { findNavController().navigate(R.id.support) }
        }
        appAdapter.onSupportBannerDismissListener = {
            UserPreferences.homeSupportCardDismissed = true
            val withoutBanner = appAdapter.items.filterNot {
                it is com.dskja.betterstreamflix.support.SupportBannerItem
            }
            appAdapter.submitList(withoutBanner)
        }

        val homeItems = mutableListOf<AppAdapter.Item>()
        val hasContinueWatching = categories.any {
            it.name == getString(R.string.home_continue_watching) && it.list.isNotEmpty()
        }
        val visibleCategories = HomeCatalogPipeline.isolateFeatured(categories)
        visibleCategories
            .onEach { category ->
                if (!Category.isFeaturedName(category.name) &&
                    category.name != getString(R.string.home_continue_watching)
                ) {
                    category.list.forEach { show ->
                        when (show) {
                            is Episode -> show.itemType = AppAdapter.Type.EPISODE_TV_ITEM
                            is Movie -> show.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                            is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                        }
                    }
                }
                category.itemSpacing = resources.getDimension(R.dimen.home_spacing).toInt()
                category.itemType = when {
                    Category.isFeaturedName(category.name) -> AppAdapter.Type.CATEGORY_TV_SWIPER
                    else -> AppAdapter.Type.CATEGORY_TV_ITEM
                }
            }
        visibleCategories
            .forEach { category ->
                homeItems.add(category)
                // CW was already renamed to the localized title; don't use the English constant.
                val insertAfter = category.name == getString(R.string.home_continue_watching) ||
                    (Category.isFeaturedName(category.name) && !hasContinueWatching)
                if (insertAfter &&
                    !UserPreferences.homeSupportCardDismissed &&
                    homeItems.none { it is com.dskja.betterstreamflix.support.SupportBannerItem }
                ) {
                    homeItems.add(
                        com.dskja.betterstreamflix.support.SupportBannerItem().apply {
                            itemType = AppAdapter.Type.SUPPORT_BANNER_TV_ITEM
                        }
                    )
                }
            }

        if (!UserPreferences.homeSupportCardDismissed &&
            homeItems.none { it is com.dskja.betterstreamflix.support.SupportBannerItem } &&
            homeItems.isNotEmpty()
        ) {
            homeItems.add(
                1.coerceAtMost(homeItems.size),
                com.dskja.betterstreamflix.support.SupportBannerItem().apply {
                    itemType = AppAdapter.Type.SUPPORT_BANNER_TV_ITEM
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
            onCtaClick = { runCatching { findNavController().navigate(R.id.providers) } },
        )
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
        swiperHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isAdded) return
                val ctx2 = context ?: return
                if (!FeaturedAdvancePolicy.shouldAutoAdvance(ctx2)) {
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
