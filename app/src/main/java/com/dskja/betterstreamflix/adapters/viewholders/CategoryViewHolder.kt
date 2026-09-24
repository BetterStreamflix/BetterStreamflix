package com.dskja.betterstreamflix.adapters.viewholders

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.postDelayed
import androidx.core.view.children
import android.content.res.ColorStateList
import android.transition.AutoTransition
import android.transition.TransitionManager
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import androidx.viewpager2.widget.ViewPager2
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.databinding.ContentCategorySwiperMobileBinding
import com.dskja.betterstreamflix.databinding.ContentCategorySwiperTvBinding
import com.dskja.betterstreamflix.databinding.ItemCategoryMobileBinding
import com.dskja.betterstreamflix.databinding.ItemCategoryTvBinding
import com.dskja.betterstreamflix.fragments.home.HomeMobileFragment
import com.dskja.betterstreamflix.fragments.home.HomeTvFragment
import com.dskja.betterstreamflix.fragments.home.HomeTvFragmentDirections
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Show
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.logo.FeaturedLogoEnrich
import com.dskja.betterstreamflix.logo.TmdbLogoBinder
import com.dskja.betterstreamflix.ui.FeaturedAdvancePolicy
import com.dskja.betterstreamflix.ui.FeaturedHeroController
import com.dskja.betterstreamflix.ui.FeaturedProviderSwitch
import com.dskja.betterstreamflix.ui.FeaturedSwiperChrome
import com.dskja.betterstreamflix.ui.FeaturedTvRotation
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.HomeCatalogPipeline
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.format
import com.dskja.betterstreamflix.utils.getCurrentFragment
import com.dskja.betterstreamflix.utils.toActivity
import java.util.Locale
import kotlinx.coroutines.Job

class CategoryViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private lateinit var category: Category
    private var swiperHandler: Handler? = null
    private var swiperPageCallback: ViewPager2.OnPageChangeCallback? = null
    private var swiperProgressAnimator: android.animation.ObjectAnimator? = null
    private var featuredListStateJob: Job? = null

    companion object {
        /** Cap Featured dots so ll_dots_indicator never balloons on long shelves. */
        const val MAX_VISIBLE_FEATURED_DOTS = 7
    }

    /** Stop auto-advance + page callbacks so recycled holders cannot touch a torn-down NavHost. */
    fun clearSwiper() {
        val callback = swiperPageCallback
        if (callback != null) {
            val binding = _binding
            if (binding is ContentCategorySwiperMobileBinding) {
                runCatching { binding.vpCategorySwiper.unregisterOnPageChangeCallback(callback) }
            }
        }
        swiperPageCallback = null
        swiperProgressAnimator?.cancel()
        swiperProgressAnimator = null
        swiperHandler?.removeCallbacksAndMessages(null)
        swiperHandler = null
        featuredListStateJob?.cancel()
        featuredListStateJob = null
        when (val binding = _binding) {
            is ContentCategorySwiperMobileBinding -> {
                val rv = binding.vpCategorySwiper.getChildAt(0) as? RecyclerView
                rv?.let { recycler ->
                    for (i in 0 until recycler.childCount) {
                        val child = recycler.getChildAt(i) ?: continue
                        TmdbLogoBinder.cancel(child)
                    }
                }
                FeaturedLogoEnrich.cancel(binding.root)
            }
            is ContentCategorySwiperTvBinding -> {
                FeaturedSwiperChrome.cancel(binding)
            }
            else -> Unit
        }
    }

    val childRecyclerView: RecyclerView?
        get() = when (_binding) {
            is ItemCategoryMobileBinding -> _binding.rvCategory
            is ItemCategoryTvBinding -> _binding.hgvCategory
            is ContentCategorySwiperMobileBinding -> {
                // Avoid ViewPager2 private-field reflection (NoSuchFieldException: mRecyclerView
                // after R8/ProGuard or AndroidX updates) — caused release crashes on home.
                (_binding.vpCategorySwiper.getChildAt(0) as? RecyclerView)
            }
            else -> null
        }

    fun bind(
        category: Category,
        onMovieClick: ((Movie) -> Unit)? = null,
        onTvShowClick: ((TvShow) -> Unit)? = null,
        onMovieLongClick: ((Movie) -> Unit)? = null,
        onTvShowLongClick: ((TvShow) -> Unit)? = null,
    ) {
        this.category = category

        when (_binding) {
            is ItemCategoryMobileBinding -> displayMobileItem(_binding, onMovieClick, onTvShowClick, onMovieLongClick, onTvShowLongClick)
            is ItemCategoryTvBinding -> displayTvItem(_binding, onMovieClick, onTvShowClick, onMovieLongClick, onTvShowLongClick)
            is ContentCategorySwiperMobileBinding -> displayMobileSwiper(_binding, onMovieClick, onTvShowClick, onMovieLongClick, onTvShowLongClick)
            is ContentCategorySwiperTvBinding -> displayTvSwiper(_binding)
        }
    }

    private fun displayMobileItem(
        binding: ItemCategoryMobileBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?,
        onMovieLongClick: ((Movie) -> Unit)?,
        onTvShowLongClick: ((TvShow) -> Unit)?,
    ) {
        binding.tvCategoryTitle.text = category.name

        if (ExperimentalMobileDesign.enabled()) {
            binding.tvCategoryTitle.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
            binding.tvCategoryTitle.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.tvCategoryTitle,
                    androidx.appcompat.R.attr.colorPrimary,
                ),
            )
            val density = binding.root.resources.displayMetrics.density
            binding.tvCategoryTitle.layoutParams =
                binding.tvCategoryTitle.layoutParams.apply {
                    width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                }
            binding.tvCategoryTitle.setPadding(
                (14 * density).toInt(),
                (7 * density).toInt(),
                (14 * density).toInt(),
                (7 * density).toInt(),
            )
            with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                binding.tvCategoryTitle.applyExpPress()
            }
            binding.tvCategoryTitle.setOnClickListener {
                ExpMotion.hapticTap(it)
            }
            binding.root.findViewById<View>(R.id.v_category_rule)?.visibility = View.VISIBLE
            binding.root.findViewById<View>(R.id.v_category_edge_fade_start)?.visibility = View.VISIBLE
            binding.root.findViewById<View>(R.id.v_category_edge_fade_end)?.visibility = View.VISIBLE
            val enterKey = category.name
            if (binding.root.getTag(R.id.exp_enter_animated_tag) != enterKey) {
                binding.root.setTag(R.id.exp_enter_animated_tag, enterKey)
                ExpMotion.revealHeader(
                    binding.tvCategoryTitle,
                    binding.root.findViewById(R.id.v_category_rule),
                )
                ExpMotion.pulseAccentRule(
                    binding.root.findViewById(R.id.v_category_rule),
                )
                ExpMotion.staggerFirstFill(binding.rvCategory)
            }
        }

        binding.rvCategory.apply {
            val categoryAdapter = (adapter as? AppAdapter) ?: AppAdapter().also { adapter = it }
            categoryAdapter.apply {
                this.onMovieClickListener = onMovieClick
                this.onTvShowClickListener = onTvShowClick
                this.onMovieLongClickListener = onMovieLongClick
                this.onTvShowLongClickListener = onTvShowLongClick
                submitList(category.list)
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(category.itemSpacing))
            }
        }
    }

    private fun displayTvItem(
        binding: ItemCategoryTvBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?,
        onMovieLongClick: ((Movie) -> Unit)?,
        onTvShowLongClick: ((TvShow) -> Unit)?,
    ) {
        binding.tvCategoryTitle.text = category.name

        binding.hgvCategory.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)

            val categoryAdapter = (adapter as? AppAdapter) ?: AppAdapter().also { adapter = it }
            categoryAdapter.apply {
                this.onMovieClickListener = onMovieClick
                this.onTvShowClickListener = onTvShowClick
                this.onMovieLongClickListener = onMovieLongClick
                this.onTvShowLongClickListener = onTvShowLongClick
                submitList(category.list)
            }
            setItemSpacing(category.itemSpacing)

            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            nextFocusLeftId = R.id.nav_main
            // First shelf tile → side nav (focusOutFront alone is unreliable on some Fire OS builds).
            if (getTag(R.id.tv_shelf_nav_focus_listener_tag) != true) {
                setTag(R.id.tv_shelf_nav_focus_listener_tag, true)
                addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) {
                        val pos = getChildAdapterPosition(view)
                        view.nextFocusLeftId = if (pos == 0) R.id.nav_main else View.NO_ID
                    }

                    override fun onChildViewDetachedFromWindow(view: View) = Unit
                })
            }
        }
    }

    private fun displayMobileSwiper(
        binding: ContentCategorySwiperMobileBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?,
        onMovieLongClick: ((Movie) -> Unit)?,
        onTvShowLongClick: ((TvShow) -> Unit)?,
    ) {
        val featuredLabel = if (Category.isFeaturedName(category.name)) {
            binding.root.resources.getString(R.string.home_featured_title)
        } else {
            category.name
        }
        binding.tvCategoryTitle.text = featuredLabel
        // Featured is edge-to-edge artwork; a label above it would break the bleed.
        binding.tvCategoryTitle.visibility = View.GONE

        clearSwiper()
        val handler = Handler(Looper.getMainLooper())
        swiperHandler = handler

        val source = category.list
        val useLoop = source.size > 1
        val items = if (useLoop) {
            val loopHead = HomeCatalogPipeline.cloneShowItems(listOfNotNull(source.lastOrNull()))
            val loopTail = HomeCatalogPipeline.cloneShowItems(listOfNotNull(source.firstOrNull()))
            loopHead + source + loopTail
        } else {
            source
        }
        // Loop clones historically dropped lateinit itemType → crash in AppAdapter.submitList
        // (BETTERSTREAMFLIX-1P). Re-stamp every page including head/tail.
        HomeCatalogPipeline.stampFeaturedSwiperTypes(
            items,
            movieType = AppAdapter.Type.MOVIE_SWIPER_MOBILE_ITEM,
            tvShowType = AppAdapter.Type.TV_SHOW_SWIPER_MOBILE_ITEM,
        )

        var userDragging = false

        // Warm logos for the first neighbors on Wi‑Fi (idle enrich).
        FeaturedLogoEnrich.enrichUpcoming(
            context = context,
            anchor = binding.root,
            items = source,
            fromIndex = 0,
            count = 2,
            wifiOnly = true,
        )

        fun restartAutoProgress() {
            if (!ExperimentalMobileDesign.enabled()) return
            if (DeviceCapabilities.shouldReduceHomeEffects(binding.root.context)) return
            val bar = binding.root.findViewById<android.widget.ProgressBar>(R.id.pb_swiper_auto_progress)
                ?: return
            bar.visibility = View.VISIBLE
            swiperProgressAnimator?.cancel()
            bar.progress = 0
            swiperProgressAnimator = android.animation.ObjectAnimator.ofInt(bar, "progress", 0, 1000)
                .setDuration(8_000L)
                .also { it.start() }
        }
        fun scheduleAdvance() {
            if (!useLoop) return
            if (!FeaturedAdvancePolicy.shouldAutoAdvance(context) || userDragging) return
            if (swiperHandler !== handler) return
            if (bindingAdapterPosition == RecyclerView.NO_POSITION) return
            if (!itemView.isAttachedToWindow) return
            restartAutoProgress()
            handler.postDelayed(8_000) {
                if (swiperHandler !== handler) return@postDelayed
                if (bindingAdapterPosition == RecyclerView.NO_POSITION) return@postDelayed
                if (!itemView.isAttachedToWindow) return@postDelayed
                if (!FeaturedAdvancePolicy.shouldAutoAdvance(context) || userDragging) return@postDelayed
                runCatching { binding.vpCategorySwiper.currentItem += 1 }
            }
        }

        val pagerAdapter = AppAdapter().apply {
            this.onMovieClickListener = onMovieClick
            this.onTvShowClickListener = onTvShowClick
            this.onMovieLongClickListener = onMovieLongClick
            this.onTvShowLongClickListener = onTvShowLongClick
        }
        binding.vpCategorySwiper.apply {
            offscreenPageLimit = 2
            adapter = pagerAdapter
            // Single submit with loop pages — dual submitList raced DiffUtil vs setCurrentItem.
            pagerAdapter.submitList(items)
            if (source.isNotEmpty()) {
                val startIndex = FeaturedTvRotation.coerceIndex(
                    category.selectedIndex,
                    source.size,
                )
                category.selectedIndex = startIndex
                setCurrentItem(if (useLoop) startIndex + 1 else startIndex, false)
                FeaturedLogoEnrich.prefetchBanner(
                    context,
                    FeaturedHeroController.bannerUrl(
                        source.getOrNull((startIndex + 1) % source.size),
                    ),
                )
                FeaturedHeroController.recordImpression(source.getOrNull(startIndex) as? Show)
            }
            // Defense: ViewPager2 requires match_parent page roots (BETTERSTREAMFLIX-13).
            post {
                for (i in 0 until childCount) {
                    getChildAt(i)?.let { child ->
                        child.layoutParams = (child.layoutParams
                            ?: ViewGroup.LayoutParams(0, 0)).apply {
                            width = ViewGroup.LayoutParams.MATCH_PARENT
                            height = ViewGroup.LayoutParams.MATCH_PARENT
                        }
                    }
                }
            }
        }

        scheduleAdvance()

        val exp = ExperimentalMobileDesign.enabled()
        if (exp) {
            applyExperimentalSwiperChrome(binding)
        }

        binding.llDotsIndicator.apply {
            removeAllViews()
            if (source.isEmpty()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                bindFeaturedPageIndicator(
                    container = this,
                    total = source.size,
                    selected = FeaturedTvRotation.coerceIndex(category.selectedIndex, source.size),
                    exp = exp,
                    leanbackFocusable = false,
                    onSelect = { index ->
                        val target = if (useLoop) index + 1 else index
                        binding.vpCategorySwiper.setCurrentItem(target, true)
                    },
                )
            }
            if (exp && source.isNotEmpty() && getTag(R.id.exp_enter_animated_tag) != true) {
                setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.popIn(this)
            }
        }

        val callback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (swiperHandler !== handler || bindingAdapterPosition == RecyclerView.NO_POSITION) {
                    return
                }
                val indicatorPosition = if (!useLoop) {
                    position.coerceIn(0, (source.lastIndex).coerceAtLeast(0))
                } else {
                    when (position) {
                        0 -> source.lastIndex
                        items.lastIndex -> 0
                        else -> position - 1
                    }
                }
                category.selectedIndex = indicatorPosition
                val currentShow = source.getOrNull(indicatorPosition) as? Show
                FeaturedHeroController.recordImpression(currentShow)
                val nextBanner = if (source.isNotEmpty()) {
                    FeaturedHeroController.bannerUrl(
                        source.getOrNull((indicatorPosition + 1) % source.size),
                    )
                } else {
                    null
                }
                FeaturedLogoEnrich.prefetchBanner(context, nextBanner)
                FeaturedLogoEnrich.enrichUpcoming(
                    context = context,
                    anchor = binding.root,
                    items = source,
                    fromIndex = indicatorPosition,
                    count = 2,
                    wifiOnly = true,
                )
                if (exp) {
                    updateExpDots(binding, indicatorPosition)
                    if (FeaturedAdvancePolicy.shouldHapticOnPageChange(context)) {
                        ExpMotion.hapticTap(binding.vpCategorySwiper)
                    }
                    // Cancel logo work on off-screen Featured pages; prefetch next logos (Wi‑Fi).
                    val rv = binding.vpCategorySwiper.getChildAt(0) as? RecyclerView
                    rv?.let { recycler ->
                        for (i in 0 until recycler.childCount) {
                            val child = recycler.getChildAt(i) ?: continue
                            val adapterPos = recycler.getChildAdapterPosition(child)
                            if (adapterPos != binding.vpCategorySwiper.currentItem) {
                                TmdbLogoBinder.cancel(child)
                            }
                        }
                    }
                    listOf(indicatorPosition + 1, indicatorPosition + 2).forEach { idx ->
                        val show = source.getOrNull(idx.coerceIn(0, source.lastIndex)) as? Show
                        val logo = when (show) {
                            is Movie -> show.logo
                            is TvShow -> show.logo
                            else -> null
                        }
                        if (!logo.isNullOrBlank()) {
                            com.dskja.betterstreamflix.logo.TmdbLogoGlide.prefetch(
                                context = context,
                                logoUrl = logo,
                                wifiOnly = true,
                            )
                        }
                    }
                    currentShow?.let { show ->
                        val activity = context.toActivity()
                        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                            (activity.getCurrentFragment() as? HomeMobileFragment)
                                ?.updateExperimentalHeroArt(show)
                        }
                        FeaturedTvRotation.titleOf(show)?.let { title ->
                            binding.vpCategorySwiper.announceForAccessibility(
                                context.getString(R.string.home_featured_page, title)
                            )
                        }
                    }
                    if (FeaturedAdvancePolicy.shouldPlayPageMotion(context)) {
                        binding.vpCategorySwiper.getChildAt(0)
                            ?.let { it as? RecyclerView }
                            ?.findViewHolderForAdapterPosition(binding.vpCategorySwiper.currentItem)
                            ?.itemView
                            ?.let { page ->
                                val logoView = page.findViewById<View>(R.id.iv_swiper_logo)
                                logoView?.let {
                                    if (it.visibility == View.VISIBLE) ExpMotion.revealHeader(it)
                                }
                                page.findViewById<View>(R.id.tv_swiper_title)?.let {
                                    if (it.visibility == View.VISIBLE) ExpMotion.revealHeader(it)
                                }
                                page.findViewById<View>(R.id.btn_swiper_watch_now)?.let { btn ->
                                    with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                                        btn.applyExpPress()
                                    }
                                    ExpMotion.popIn(btn)
                                }
                                page.findViewById<View>(R.id.btn_swiper_add_to_list)?.let { btn ->
                                    with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                                        btn.applyExpPress()
                                    }
                                    ExpMotion.popIn(btn)
                                }
                            }
                    }
                } else {
                    updateMobilePageIndicator(binding, indicatorPosition, source.size)
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (swiperHandler !== handler || bindingAdapterPosition == RecyclerView.NO_POSITION) {
                    return
                }
                when (state) {
                    ViewPager2.SCROLL_STATE_DRAGGING,
                    ViewPager2.SCROLL_STATE_SETTLING -> {
                        userDragging = true
                        handler.removeCallbacksAndMessages(null)
                        swiperProgressAnimator?.cancel()
                    }
                    ViewPager2.SCROLL_STATE_IDLE -> {
                        userDragging = false
                        if (useLoop) {
                            when (binding.vpCategorySwiper.currentItem) {
                                0 -> binding.vpCategorySwiper.setCurrentItem(
                                    items.lastIndex - 1,
                                    false,
                                )
                                items.lastIndex -> binding.vpCategorySwiper.setCurrentItem(
                                    1,
                                    false,
                                )
                            }
                        }
                        scheduleAdvance()
                    }
                }
            }
        }
        swiperPageCallback = callback
        binding.vpCategorySwiper.registerOnPageChangeCallback(callback)
        if (source.isNotEmpty()) {
            val start = FeaturedTvRotation.coerceIndex(category.selectedIndex, source.size)
            if (exp) {
                updateExpDots(binding, start)
            } else {
                updateMobilePageIndicator(binding, start, source.size)
            }
        }
    }

    /** Resume auto-advance after Home returns to foreground (no full rebind). */
    fun resumeSwiper() {
        val binding = _binding as? ContentCategorySwiperMobileBinding ?: return
        val handler = swiperHandler ?: return
        if (!itemView.isAttachedToWindow) return
        if (!::category.isInitialized || category.list.size <= 1) return
        if (!FeaturedAdvancePolicy.shouldAutoAdvance(context)) return
        handler.removeCallbacksAndMessages(null)
        // Restart progress chrome the same way scheduleAdvance does after page changes.
        if (ExperimentalMobileDesign.enabled() &&
            !DeviceCapabilities.shouldReduceHomeEffects(binding.root.context)
        ) {
            val bar = binding.root.findViewById<android.widget.ProgressBar>(R.id.pb_swiper_auto_progress)
            if (bar != null) {
                bar.visibility = View.VISIBLE
                swiperProgressAnimator?.cancel()
                bar.progress = 0
                swiperProgressAnimator =
                    android.animation.ObjectAnimator.ofInt(bar, "progress", 0, 1000)
                        .setDuration(8_000L)
                        .also { it.start() }
            }
        }
        handler.postDelayed(8_000) {
            if (swiperHandler !== handler) return@postDelayed
            if (bindingAdapterPosition == RecyclerView.NO_POSITION) return@postDelayed
            if (!itemView.isAttachedToWindow) return@postDelayed
            if (!FeaturedAdvancePolicy.shouldAutoAdvance(context)) return@postDelayed
            runCatching { binding.vpCategorySwiper.currentItem += 1 }
        }
    }

    /** Soft-pause auto-advance (keep ViewPager + page callback alive). */
    fun pauseSwiper() {
        swiperProgressAnimator?.cancel()
        swiperHandler?.removeCallbacksAndMessages(null)
    }

    private val expDotInactive: Int
        get() = androidx.core.graphics.ColorUtils.setAlphaComponent(
            MaterialColors.getColor(
                context, com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF.toInt(),
            ),
            0x59,
        )

    private fun applyExperimentalSwiperChrome(binding: ContentCategorySwiperMobileBinding) {
        binding.tvCategoryTitle.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
        binding.tvCategoryTitle.setTextColor(
            MaterialColors.getColor(
                binding.tvCategoryTitle,
                androidx.appcompat.R.attr.colorPrimary,
            ),
        )
        with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
            binding.tvCategoryTitle.applyExpPress()
        }
        binding.tvCategoryTitle.setOnClickListener {
            ExpMotion.hapticTap(it)
        }
        binding.llDotsIndicator.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
        if (binding.root.getTag(R.id.exp_enter_animated_tag) != true) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.revealHeader(binding.tvCategoryTitle)
            ExpMotion.popIn(binding.llDotsIndicator)
        }
            // Soft parallax only — keep CTAs fully opaque (no mid-swipe disabled look).
        binding.vpCategorySwiper.setPageTransformer { page, position ->
            if (!FeaturedAdvancePolicy.shouldPlayPageMotion(page.context)) {
                page.findViewById<View>(R.id.iv_swiper_background)?.apply {
                    translationX = 0f
                    scaleX = 1f
                    scaleY = 1f
                }
                listOf(
                    R.id.tv_swiper_title,
                    R.id.iv_swiper_logo,
                    R.id.ll_swiper_actions,
                ).forEach { id ->
                    page.findViewById<View>(id)?.apply {
                        alpha = 1f
                        translationY = 0f
                    }
                }
                return@setPageTransformer
            }
            val clamped = abs(position).coerceAtMost(1f)
            page.findViewById<View>(R.id.iv_swiper_background)?.apply {
                translationX = -position * page.width * 0.12f
                scaleX = 1f
                scaleY = 1f
            }
            val titleAlpha = (1f - clamped * 1.2f).coerceAtLeast(0f)
            listOf(
                R.id.tv_swiper_title,
                R.id.iv_swiper_logo,
            ).forEach { id ->
                page.findViewById<View>(id)?.apply {
                    alpha = titleAlpha
                    translationY = 0f
                }
            }
            page.findViewById<View>(R.id.ll_swiper_actions)?.apply {
                alpha = 1f
                translationY = 0f
            }
        }
    }

    private fun updateMobilePageIndicator(
        binding: ContentCategorySwiperMobileBinding,
        selected: Int,
        total: Int,
    ) {
        val child = binding.llDotsIndicator.getChildAt(0)
        if (child is TextView) {
            val pageCount = total.coerceAtLeast(1)
            val page = (selected + 1).coerceIn(1, pageCount)
            child.text = context.getString(R.string.home_featured_page_index, page, pageCount)
            child.contentDescription = child.text
            return
        }
        binding.llDotsIndicator.children.forEachIndexed { index, view ->
            view.isSelected = selected == index
        }
    }

    private fun updateExpDots(
        binding: ContentCategorySwiperMobileBinding,
        selected: Int,
    ) {
        val total = binding.llDotsIndicator.childCount
        if (total == 1 && binding.llDotsIndicator.getChildAt(0) is TextView) {
            val label = binding.llDotsIndicator.getChildAt(0) as TextView
            val pageCount = category.list.size.coerceAtLeast(1)
            val page = (selected + 1).coerceIn(1, pageCount)
            label.text = context.getString(R.string.home_featured_page_index, page, pageCount)
            label.contentDescription = label.text
            return
        }
        val activeColor = MaterialColors.getColor(
            context, androidx.appcompat.R.attr.colorPrimary, 0xFFFFFFFF.toInt(),
        )
        val inactive = expDotInactive
        val activeWidth = 20.dp(context)
        val dotSize = 6.dp(context)
        TransitionManager.beginDelayedTransition(
            binding.llDotsIndicator,
            AutoTransition().setDuration(180),
        )
        binding.llDotsIndicator.children.forEachIndexed { index, view ->
            val isActive = index == selected
            view.layoutParams = (view.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (isActive) activeWidth else dotSize
            }
            view.backgroundTintList = ColorStateList.valueOf(if (isActive) activeColor else inactive)
            view.animate().cancel()
            if (isActive) {
                view.scaleX = 0.7f
                view.scaleY = 0.7f
                view.alpha = 0.55f
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(220L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.2f))
                    .start()
            } else {
                view.scaleX = 1f
                view.scaleY = 1f
                view.alpha = 0.85f
            }
        }
    }

    /**
     * Featured page chrome: at most [MAX_VISIBLE_FEATURED_DOTS] dots, otherwise a compact
     * "3/12" label. On Leanback, dots are focusable/clickable to jump the index.
     */
    private fun bindFeaturedPageIndicator(
        container: LinearLayout,
        total: Int,
        selected: Int,
        exp: Boolean,
        leanbackFocusable: Boolean,
        onSelect: (Int) -> Unit,
    ) {
        container.removeAllViews()
        if (total <= 0) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val safeSelected = selected.coerceIn(0, total - 1)
        if (total > MAX_VISIBLE_FEATURED_DOTS) {
            val label = TextView(context).apply {
                text = context.getString(
                    R.string.home_featured_page_index,
                    safeSelected + 1,
                    total,
                )
                contentDescription = text
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 14f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            container.addView(label)
            return
        }
        val activeColor = if (exp) {
            MaterialColors.getColor(
                context, androidx.appcompat.R.attr.colorPrimary, 0xFFFFFFFF.toInt(),
            )
        } else {
            0xFFFFFFFF.toInt()
        }
        val inactiveColor = if (exp) {
            MaterialColors.getColor(
                context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0x66FFFFFF,
            )
        } else {
            0x66FFFFFF
        }
        val dotSize = if (exp) 6.dp(context) else 15
        val activeWidth = if (exp) 28 else 15
        val margin = if (exp) 5.dp(context) else 10
        repeat(total) { index ->
            val isActive = safeSelected == index
            val view = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    if (isActive) activeWidth else dotSize,
                    if (exp) 6.dp(context) else 15,
                ).apply {
                    setMargins(margin, 0, margin, 0)
                }
                setBackgroundResource(
                    if (exp) R.drawable.bg_exp_dot else R.drawable.bg_dot_indicator,
                )
                backgroundTintList = ColorStateList.valueOf(
                    if (isActive) activeColor else inactiveColor,
                )
                isSelected = isActive
                isClickable = true
                contentDescription = context.getString(
                    R.string.home_featured_page_index,
                    index + 1,
                    total,
                )
                if (leanbackFocusable) {
                    isFocusable = true
                    isFocusableInTouchMode = false
                    setOnFocusChangeListener { _, hasFocus ->
                        (context.toActivity()?.getCurrentFragment() as? HomeTvFragment)
                            ?.setFeaturedChromeFocused(hasFocus)
                    }
                }
                setOnClickListener { onSelect(index) }
            }
            container.addView(view)
        }
    }

    /** Lightweight TV Featured rotation — refresh chrome without cancelling in-flight logo work. */
    fun bindFeaturedRotatePayload() {
        val binding = _binding as? ContentCategorySwiperTvBinding ?: return
        if (!::category.isInitialized) return
        category.selectedIndex = FeaturedTvRotation.coerceIndex(
            category.selectedIndex,
            category.list.size,
        )
        val selected = category.list.getOrNull(category.selectedIndex) as? Show ?: return
        featuredListStateJob?.cancel()
        featuredListStateJob = null
        bindTvSwiperSelected(
            binding,
            selected,
            updateBackground = false,
            resetSchedule = false,
            announce = false,
            rebuildPageIndicator = false,
        )
    }

    private fun displayTvSwiper(binding: ContentCategorySwiperTvBinding) {
        featuredListStateJob?.cancel()
        featuredListStateJob = null
        FeaturedSwiperChrome.cancel(binding)

        if (Category.isFeaturedName(category.name)) {
            binding.tvCategoryTitle.visibility = View.GONE
        } else {
            binding.tvCategoryTitle.visibility = View.VISIBLE
            binding.tvCategoryTitle.text = category.name
        }
        category.selectedIndex = FeaturedTvRotation.coerceIndex(
            category.selectedIndex,
            category.list.size,
        )
        val selected = category.list.getOrNull(category.selectedIndex) as? Show
        if (selected == null) {
            binding.tvSwiperTitle.text = ""
            binding.ivSwiperLogo.visibility = View.INVISIBLE
            return
        }

        if (ExperimentalMobileDesign.enabled()) {
            applyExperimentalTvSwiperChrome(binding)
        }

        bindTvSwiperSelected(
            binding,
            selected,
            updateBackground = true,
            resetSchedule = true,
            announce = true,
        )
    }

    private fun bindTvSwiperSelected(
        binding: ContentCategorySwiperTvBinding,
        selected: Show,
        updateBackground: Boolean,
        resetSchedule: Boolean,
        announce: Boolean = true,
        rebuildPageIndicator: Boolean = true,
    ) {
        fun advanceFeatured() {
            when (val fragment = context.toActivity()?.getCurrentFragment()) {
                is HomeTvFragment -> fragment.resetSwiperSchedule()
            }
            if (category.list.isEmpty()) return
            category.selectedIndex = FeaturedTvRotation.nextIndex(
                category.selectedIndex,
                category.list.size,
            )
            // User-initiated DPAD advance: announce here. PAYLOAD_ROTATE must not
            // announce (auto-rotate would spam TalkBack).
            val next = category.list.getOrNull(category.selectedIndex) as? Show
            FeaturedTvRotation.titleOf(next)?.let { title ->
                binding.root.announceForAccessibility(
                    context.getString(R.string.home_featured_page, title),
                )
            }
            val banner = FeaturedTvRotation.bannerOf(next)
            when (val fragment = context.toActivity()?.getCurrentFragment()) {
                is HomeTvFragment -> if (banner != null) {
                    fragment.updateBackground(banner, true)
                }
            }
            bindingAdapter?.let { adapter ->
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    adapter.notifyItemChanged(pos, FeaturedHeroController.PAYLOAD_ROTATE)
                }
            }
        }

        if (updateBackground || resetSchedule) {
            val poster = FeaturedTvRotation.bannerOf(selected)
            when (val fragment = context.toActivity()?.getCurrentFragment()) {
                is HomeTvFragment -> {
                    if (updateBackground && poster != null) {
                        fragment.updateBackground(poster, false)
                    }
                    if (resetSchedule &&
                        category.selectedIndex == category.list.indexOf(selected)
                    ) {
                        fragment.resetSwiperSchedule()
                    }
                }
            }
        }

        when (selected) {
            is Movie -> FeaturedSwiperChrome.resolveAndBindLogo(binding, selected)
            is TvShow -> FeaturedSwiperChrome.resolveAndBindLogo(binding, selected)
        }

        FeaturedLogoEnrich.enrichUpcoming(
            context = context,
            anchor = binding.root,
            items = category.list,
            fromIndex = category.selectedIndex,
            count = 2,
            wifiOnly = true,
        )

        FeaturedHeroController.recordImpression(selected)
        if (announce) {
            FeaturedTvRotation.titleOf(selected)?.let { title ->
                binding.root.announceForAccessibility(
                    context.getString(R.string.home_featured_page, title),
                )
            }
        }

        binding.tvSwiperTvShowLastEpisode.apply {
            text = when (selected) {
                is TvShow -> FeaturedHeroController.episodeMeta(context, selected)
                    ?: context.getString(R.string.tv_show_item_type)
                else -> context.getString(R.string.movie_item_type)
            }
        }

        binding.tvSwiperQuality.apply {
            text = when (selected) {
                is Movie -> selected.quality
                is TvShow -> selected.quality
            }
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvSwiperReleased.apply {
            text = when (selected) {
                is Movie -> selected.released?.format("yyyy")
                is TvShow -> selected.released?.format("yyyy")
            }
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvSwiperRating.apply {
            text = when (selected) {
                is Movie -> com.dskja.betterstreamflix.ui.DetailRating.format(selected.rating)
                is TvShow -> com.dskja.betterstreamflix.ui.DetailRating.format(selected.rating)
            }
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.ivSwiperRatingIcon.visibility = binding.tvSwiperRating.visibility

        binding.tvSwiperOverview.text = when (selected) {
            is Movie -> selected.overview
            is TvShow -> selected.overview
        }

        binding.btnSwiperWatchNow.apply {
            FeaturedSwiperChrome.wireWatchButton(this)
            text = FeaturedHeroController.watchCtaLabel(context, selected)
            // DPAD: up to profile chip; down into the next home shelf via parent VerticalGridView.
            (context.toActivity()?.findViewById<View>(R.id.tv_home_profile_chip))?.let { chip ->
                nextFocusUpId = chip.id
            }
            (binding.root.parent as? View)?.let { parent ->
                // Leanback VerticalGridView focus search handles the next shelf row.
                nextFocusDownId = parent.id
            }
            if (ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
                setTextColor(
                    MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorOnPrimary,
                    ),
                )
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { applyExpPress() }
            }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                FeaturedProviderSwitch.runWithProvider(selected) {
                    findNavController().navigate(
                        when (selected) {
                            is Movie -> HomeTvFragmentDirections.actionHomeToMovie(selected.id)
                            is TvShow -> HomeTvFragmentDirections.actionHomeToTvShow(
                                id = selected.id,
                                poster = selected.poster,
                                banner = selected.banner,
                            )
                        }
                    )
                }
            }
            setOnKeyListener { _, _, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                ) {
                    // Prefer focus to My List when present; otherwise advance.
                    if (binding.btnSwiperAddToList.visibility == View.VISIBLE) {
                        binding.btnSwiperAddToList.requestFocus()
                    } else {
                        advanceFeatured()
                    }
                    return@setOnKeyListener true
                }
                false
            }
            setOnFocusChangeListener { _, hasFocus ->
                (context.toActivity()?.getCurrentFragment() as? HomeTvFragment)
                    ?.setFeaturedChromeFocused(hasFocus)
            }
        }

        val inList = when (selected) {
            is Movie -> selected.isFavorite
            is TvShow -> selected.isFavorite
        }
        FeaturedSwiperChrome.bindListButton(binding.btnSwiperAddToList, inList)
        binding.btnSwiperAddToList.apply {
            (context.toActivity()?.findViewById<View>(R.id.tv_home_profile_chip))?.let { chip ->
                nextFocusUpId = chip.id
            }
            (binding.root.parent as? View)?.let { parent ->
                nextFocusDownId = parent.id
            }
            if (ExperimentalMobileDesign.enabled()) {
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { applyExpPress() }
            }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                when (selected) {
                    is Movie -> FeaturedSwiperChrome.toggleMovieFavorite(
                        anchor = binding.root,
                        button = this,
                        movie = selected,
                    )
                    is TvShow -> FeaturedSwiperChrome.toggleTvShowFavorite(
                        anchor = binding.root,
                        button = this,
                        tvShow = selected,
                    )
                }
            }
            setOnKeyListener { _, _, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                ) {
                    advanceFeatured()
                    return@setOnKeyListener true
                }
                false
            }
            setOnFocusChangeListener { _, hasFocus ->
                (context.toActivity()?.getCurrentFragment() as? HomeTvFragment)
                    ?.setFeaturedChromeFocused(hasFocus)
            }
        }
        featuredListStateJob = when (selected) {
            is Movie -> FeaturedSwiperChrome.observeListState(
                anchor = binding.root,
                button = binding.btnSwiperAddToList,
                movieId = selected.id,
            ) { favorite ->
                if (selected.id != (category.list.getOrNull(category.selectedIndex) as? Movie)?.id) {
                    return@observeListState
                }
                selected.isFavorite = favorite
                FeaturedSwiperChrome.bindListButton(binding.btnSwiperAddToList, favorite)
            }
            is TvShow -> FeaturedSwiperChrome.observeListStateTv(
                anchor = binding.root,
                button = binding.btnSwiperAddToList,
                tvShowId = selected.id,
            ) { favorite ->
                if (selected.id != (category.list.getOrNull(category.selectedIndex) as? TvShow)?.id) {
                    return@observeListStateTv
                }
                selected.isFavorite = favorite
                FeaturedSwiperChrome.bindListButton(binding.btnSwiperAddToList, favorite)
            }
            else -> null
        }

        binding.pbSwiperProgress.apply {
            val watch = FeaturedHeroController.watchProgress(selected)
            progress = watch.percent
            visibility = when {
                watch.history != null -> View.VISIBLE
                else -> View.GONE
            }
            if (ExperimentalMobileDesign.enabled() && watch.history != null) {
                val primary = MaterialColors.getColor(
                    this, androidx.appcompat.R.attr.colorPrimary,
                )
                progressTintList = ColorStateList.valueOf(primary)
            }
        }

        binding.llDotsIndicator.apply {
            val exp = ExperimentalMobileDesign.enabled()
            if (exp) {
                setBackgroundResource(ExperimentalMobileDesign.chipBackground())
                val pad = (10 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, pad / 2)
            } else {
                setBackgroundResource(0)
                setPadding(0, 0, 0, 0)
            }
            val leanback = DeviceCapabilities.isLeanbackDevice(context)
            val total = category.list.size
            val selectedIndex = category.selectedIndex
            if (!rebuildPageIndicator &&
                updateTvPageIndicatorSelection(this, selectedIndex, total, exp)
            ) {
                return@apply
            }
            bindFeaturedPageIndicator(
                container = this,
                total = total,
                selected = selectedIndex,
                exp = exp,
                leanbackFocusable = leanback,
                onSelect = { index ->
                    if (category.selectedIndex == index) return@bindFeaturedPageIndicator
                    category.selectedIndex = index
                    val jumped = category.list.getOrNull(index) as? Show ?: return@bindFeaturedPageIndicator
                    FeaturedTvRotation.titleOf(jumped)?.let { title ->
                        binding.root.announceForAccessibility(
                            context.getString(R.string.home_featured_page, title),
                        )
                    }
                    val banner = FeaturedTvRotation.bannerOf(jumped)
                    when (val fragment = context.toActivity()?.getCurrentFragment()) {
                        is HomeTvFragment -> {
                            if (banner != null) fragment.updateBackground(banner, true)
                            fragment.resetSwiperSchedule()
                        }
                    }
                    bindingAdapter?.let { adapter ->
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            adapter.notifyItemChanged(pos, FeaturedHeroController.PAYLOAD_ROTATE)
                        }
                    }
                },
            )
        }
    }

    /**
     * In-place indicator update for [FeaturedHeroController.PAYLOAD_ROTATE] — avoids
     * `removeAllViews` so Leanback focus on dots is not destroyed every 8s.
     * @return true when the existing chrome was updated; false if a full rebuild is needed.
     */
    private fun updateTvPageIndicatorSelection(
        container: LinearLayout,
        selected: Int,
        total: Int,
        exp: Boolean,
    ): Boolean {
        if (total <= 0) {
            container.visibility = View.GONE
            return true
        }
        if (total > MAX_VISIBLE_FEATURED_DOTS) {
            val label = container.getChildAt(0) as? TextView ?: return false
            if (container.childCount != 1) return false
            val page = (selected + 1).coerceIn(1, total)
            label.text = context.getString(R.string.home_featured_page_index, page, total)
            label.contentDescription = label.text
            container.visibility = View.VISIBLE
            return true
        }
        if (container.childCount != total) return false
        val activeColor = if (exp) {
            MaterialColors.getColor(
                context, androidx.appcompat.R.attr.colorPrimary, 0xFFFFFFFF.toInt(),
            )
        } else {
            0xFFFFFFFF.toInt()
        }
        val inactiveColor = if (exp) {
            MaterialColors.getColor(
                context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0x66FFFFFF,
            )
        } else {
            0x66FFFFFF
        }
        val dotSize = if (exp) 6.dp(context) else 15
        val activeWidth = if (exp) 28 else 15
        val safeSelected = selected.coerceIn(0, total - 1)
        container.children.forEachIndexed { index, view ->
            val isActive = safeSelected == index
            view.isSelected = isActive
            view.backgroundTintList = ColorStateList.valueOf(
                if (isActive) activeColor else inactiveColor,
            )
            (view.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = if (isActive) activeWidth else dotSize
                view.layoutParams = lp
            }
        }
        container.visibility = View.VISIBLE
        return true
    }

    private fun applyExperimentalTvSwiperChrome(binding: ContentCategorySwiperTvBinding) {
        binding.tvCategoryTitle.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
        binding.tvCategoryTitle.setTextColor(
            MaterialColors.getColor(
                binding.tvCategoryTitle,
                androidx.appcompat.R.attr.colorPrimary,
            ),
        )
        val density = binding.root.resources.displayMetrics.density
        binding.tvCategoryTitle.setPadding(
            (16 * density).toInt(),
            (8 * density).toInt(),
            (16 * density).toInt(),
            (8 * density).toInt(),
        )
        with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
            binding.tvCategoryTitle.applyExpPress()
        }
        val onSurface = MaterialColors.getColor(
            binding.tvSwiperTitle, com.google.android.material.R.attr.colorOnSurface,
        )
        val onVariant = MaterialColors.getColor(
            binding.tvSwiperOverview, com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
        binding.tvSwiperTitle.setTextColor(onSurface)
        binding.tvSwiperOverview.setTextColor(onVariant)
        listOf(
            binding.tvSwiperTvShowLastEpisode,
            binding.tvSwiperQuality,
            binding.tvSwiperReleased,
            binding.tvSwiperRating,
        ).forEach { meta ->
            meta.setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
            meta.setTextColor(onSurface)
            val padH = (12 * density).toInt()
            val padV = (6 * density).toInt()
            meta.setPadding(padH, padV, padH, padV)
        }
        binding.ivSwiperRatingIcon.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(
                binding.ivSwiperRatingIcon, androidx.appcompat.R.attr.colorPrimary,
            ),
        )
        if (binding.root.getTag(R.id.exp_enter_animated_tag) != true) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.revealHeader(binding.tvCategoryTitle, binding.tvSwiperTitle)
            ExpMotion.popIn(binding.btnSwiperWatchNow)
            ExpMotion.popIn(binding.llDotsIndicator)
        }
    }
}
