package com.dskja.betterstreamflix.adapters.viewholders

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.postDelayed
import androidx.core.view.children
import android.content.res.ColorStateList
import android.transition.AutoTransition
import android.transition.TransitionManager
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import androidx.core.os.bundleOf
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
import com.dskja.betterstreamflix.home.HomeShelfLayout
import com.dskja.betterstreamflix.home.HomeShelfPinUi
import com.dskja.betterstreamflix.home.SeriesFastForward
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Show
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.logo.FeaturedLogoEnrich
import com.dskja.betterstreamflix.logo.TmdbLogoBinder
import com.dskja.betterstreamflix.models.TrailerCatalog
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.providers.ShelfWeather
import com.dskja.betterstreamflix.ui.FeaturedAdvancePolicy
import com.dskja.betterstreamflix.ui.FeaturedHeroController
import com.dskja.betterstreamflix.ui.FeaturedProviderSwitch
import com.dskja.betterstreamflix.ui.FeaturedSwiperChrome
import com.dskja.betterstreamflix.ui.FeaturedTvRotation
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.ui.TrailerPlaybackController
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.HomeCatalogPipeline
import com.dskja.betterstreamflix.utils.TmdbUtils
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.format
import com.dskja.betterstreamflix.utils.getCurrentFragment
import com.dskja.betterstreamflix.utils.toActivity
import java.util.Locale
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var lastMobileDotIndex = Int.MIN_VALUE

    companion object {
        /** Cap Featured dots so ll_dots_indicator never balloons on long shelves. */
        const val MAX_VISIBLE_FEATURED_DOTS = 7
        /** First-pass nested shelf bind size — rest fills in chunked posts. */
        private const val TV_SHELF_INITIAL_BIND = 4
        /** Extra posters per subsequent frame (keeps CreateViewHolder off one long frame). */
        private const val TV_SHELF_BIND_CHUNK = 4
        /** Nested prefetch during parent VerticalGridView measure. */
        private const val TV_SHELF_PREFETCH = 2
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
        lastMobileDotIndex = Int.MIN_VALUE
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

    /** Map stable English Category constants to locale strings when they still leak into UI. */
    private fun localizedCategoryTitle(name: String): String {
        val res = itemView.resources
        val key = category.identityKey?.trim().takeIf { !it.isNullOrEmpty() } ?: name
        return when {
            Category.isFeaturedName(key) || Category.isFeaturedName(name) ->
                res.getString(R.string.home_featured_title)
            key == Category.CONTINUE_WATCHING -> res.getString(R.string.home_continue_watching)
            key == Category.NEXT_EPISODES -> res.getString(R.string.home_next_episodes)
            key == Category.NEW_THIS_WEEK -> res.getString(R.string.home_new_this_week)
            key == Category.FAMILY_FRIDAY -> res.getString(R.string.home_family_friday)
            key == Category.RECENTLY_WATCHED -> res.getString(R.string.home_recently_watched)
            key == Category.RECENTLY_ADDED -> res.getString(R.string.home_recently_added)
            key == Category.HOME_LIVE || key == Category.HOME_LIVE_ID ->
                res.getString(R.string.home_live_shelf)
            key == Category.DOWNLOADS || key == Category.DOWNLOADS_ID ->
                res.getString(R.string.home_downloads_shelf)
            key == Category.SMART_CHILL_EVENING -> res.getString(R.string.home_smart_chill_evening)
            key == Category.SMART_WITH_KIDS -> res.getString(R.string.home_smart_with_kids)
            key == Category.SMART_DE_AUDIO -> res.getString(R.string.home_smart_de_audio)
            key == Category.FAVORITE_MOVIES -> res.getString(R.string.home_favorite_movies)
            key == Category.FAVORITE_TV_SHOWS -> res.getString(R.string.home_favorite_tv_shows)
            else -> name
        }
    }

    private fun isHomeShelfContext(): Boolean {
        val fragment = context.toActivity()?.getCurrentFragment()
        return fragment is HomeMobileFragment || fragment is HomeTvFragment
    }

    private fun bindShelfPin(button: ImageButton?) {
        val btn = button ?: return
        if (!isHomeShelfContext() ||
            ProfileManager.isKidsActive() ||
            ProfileManager.isGuestActive() ||
            !HomeShelfLayout.isPinnable(category)
        ) {
            btn.visibility = View.GONE
            btn.setOnClickListener(null)
            return
        }
        val shelfId = HomeShelfLayout.shelfId(category)
        val pinned = HomeShelfLayout.isPinned(shelfId)
        btn.visibility = View.VISIBLE
        btn.isSelected = pinned
        btn.alpha = if (pinned) 1f else 0.78f
        val tint = if (pinned) {
            MaterialColors.getColor(btn, androidx.appcompat.R.attr.colorPrimary)
        } else {
            context.getColor(R.color.cinema_text_muted)
        }
        btn.imageTintList = ColorStateList.valueOf(tint)
        btn.contentDescription = if (pinned) {
            context.getString(R.string.home_shelf_unpin_cd)
        } else {
            context.getString(R.string.home_shelf_pin_cd)
        }
        btn.setOnClickListener {
            ExpMotion.hapticTap(it)
            HomeShelfPinUi.applyToggle(context, shelfId)
            bindShelfPin(btn)
        }
    }

    /**
     * Shelf Weather: dim the poster row and show a source-health pill when
     * [ProviderHealth] / [ProviderSmoke] mark the stamped providers unhealthy.
     */
    private fun bindShelfWeather(
        titleView: TextView,
        contentView: View,
        pill: TextView,
    ) {
        if (ProfileManager.isKidsActive() || ProfileManager.isGuestActive()) {
            clearShelfWeather(titleView, contentView, pill)
            return
        }
        val forecast = ShelfWeather.forecast(category)
        titleView.alpha = forecast.titleAlpha
        contentView.alpha = forecast.contentAlpha
        if (!forecast.shouldWarn) {
            pill.visibility = View.GONE
            pill.text = null
            pill.contentDescription = null
            return
        }
        val reason = forecast.reason ?: ShelfWeather.Reason.FLAKY
        val labelRes = when (reason) {
            ShelfWeather.Reason.QUARANTINED -> R.string.shelf_weather_quarantined
            ShelfWeather.Reason.PARKED -> R.string.shelf_weather_parked
            ShelfWeather.Reason.CIRCUIT_OPEN -> R.string.shelf_weather_circuit
            ShelfWeather.Reason.FLAKY -> R.string.shelf_weather_flaky
        }
        val cdRes = when (forecast.level) {
            ShelfWeather.Level.STORM -> R.string.shelf_weather_cd_storm
            else -> R.string.shelf_weather_cd_haze
        }
        val provider = forecast.providerName.orEmpty()
        val wasVisible = pill.visibility == View.VISIBLE
        val previousLevel = pill.getTag(R.id.tv_shelf_weather) as? ShelfWeather.Level
        pill.text = context.getString(labelRes)
        val cd = context.getString(cdRes, provider)
        pill.contentDescription = cd
        pill.visibility = View.VISIBLE
        pill.setTag(R.id.tv_shelf_weather, forecast.level)
        if (!wasVisible) ExpMotion.popIn(pill)
        // Announce when forecast becomes stormy (or first warn bind).
        if (forecast.level == ShelfWeather.Level.STORM &&
            previousLevel != ShelfWeather.Level.STORM
        ) {
            pill.announceForAccessibility(cd)
        }
    }

    private fun clearShelfWeather(
        titleView: TextView,
        contentView: View,
        pill: TextView,
    ) {
        titleView.alpha = ShelfWeather.CLEAR_ALPHA
        contentView.alpha = ShelfWeather.CLEAR_ALPHA
        pill.visibility = View.GONE
        pill.text = null
        pill.contentDescription = null
    }

    private fun bindSeriesFastForwardChip(
        chip: TextView,
        emptyHint: TextView,
        listEmpty: Boolean,
    ) {
        if (!SeriesFastForward.supportsShelf(category)) {
            chip.visibility = View.GONE
            chip.setOnClickListener(null)
            chip.setOnKeyListener(null)
            emptyHint.visibility = View.GONE
            return
        }
        val enabled = SeriesFastForward.isEnabled()
        chip.visibility = View.VISIBLE
        chip.isSelected = enabled
        chip.contentDescription = chip.resources.getString(R.string.home_series_fast_forward_cd)
        chip.setTextColor(
            chip.resources.getColor(
                if (enabled) R.color.cinema_text else R.color.cinema_text_soft,
                chip.context.theme,
            ),
        )
        emptyHint.visibility =
            if (enabled && listEmpty) View.VISIBLE else View.GONE

        val toggle = View.OnClickListener {
            ExpMotion.hapticTap(it)
            val next = !SeriesFastForward.isEnabled()
            it.isSelected = next
            it.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
            val home = context.toActivity()?.getCurrentFragment()
            when (home) {
                is HomeMobileFragment -> home.setSeriesFastForwardEnabled(next)
                is HomeTvFragment -> home.setSeriesFastForwardEnabled(next)
                else -> {
                    SeriesFastForward.setEnabled(next)
                }
            }
        }
        chip.setOnClickListener(toggle)
        chip.setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    toggle.onClick(v)
                    true
                }
                else -> false
            }
        }
    }

    private fun displayMobileItem(
        binding: ItemCategoryMobileBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?,
        onMovieLongClick: ((Movie) -> Unit)?,
        onTvShowLongClick: ((TvShow) -> Unit)?,
    ) {
        binding.tvCategoryTitle.text = localizedCategoryTitle(category.name)
        bindSeriesFastForwardChip(
            chip = binding.chipSeriesFastForward,
            emptyHint = binding.tvSeriesFastForwardEmpty,
            listEmpty = category.list.isEmpty(),
        )
        bindShelfPin(binding.btnShelfPin)

        // Empty shelves: hide the horizontal list (parity with TV empty-shelf defense).
        // Home usually filters empties before submit; this guards Search / edge rebinds.
        // Series Fast-Forward keeps the row visible with chip + empty hint.
        if (category.list.isEmpty()) {
            binding.root.visibility = View.VISIBLE
            binding.rvCategory.visibility = View.GONE
            binding.rvCategory.isNestedScrollingEnabled = false
            (binding.rvCategory.adapter as? AppAdapter)?.submitList(emptyList())
            clearShelfWeather(binding.tvCategoryTitle, binding.rvCategory, binding.tvShelfWeather)
            return
        }
        binding.root.visibility = View.VISIBLE
        binding.rvCategory.visibility = View.VISIBLE
        bindShelfWeather(binding.tvCategoryTitle, binding.rvCategory, binding.tvShelfWeather)

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
            (context.toActivity()?.getCurrentFragment() as? HomeMobileFragment)?.let { home ->
                setRecycledViewPool(home.shelfPool)
                itemAnimator = null
                setItemViewCacheSize(8)
                isNestedScrollingEnabled = false
            }
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
        binding.tvCategoryTitle.text = localizedCategoryTitle(category.name)
        bindSeriesFastForwardChip(
            chip = binding.chipSeriesFastForward,
            emptyHint = binding.tvSeriesFastForwardEmpty,
            listEmpty = category.list.isEmpty(),
        )
        bindShelfPin(binding.btnShelfPin)
        // LD-037: Compact density shrinks shelf pin chrome.
        if (binding.btnShelfPin.visibility == View.VISIBLE) {
            com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.applyChromeIconSize(binding.btnShelfPin)
        }

        // Never keep empty CATEGORY shelves on screen — title-only rows still trap Leanback.
        // Search Global omits empty Success shelves; Home should not submit empties either.
        // Series Fast-Forward is the exception: keep title + focusable chip so the filter
        // can be turned off when the filtered list is empty.
        if (category.list.isEmpty()) {
            val keepForFastForward = SeriesFastForward.supportsShelf(category)
            if (!keepForFastForward) {
                binding.root.visibility = View.GONE
                binding.root.isFocusable = false
                binding.root.isFocusableInTouchMode = false
                binding.root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            } else {
                binding.root.visibility = View.VISIBLE
                binding.root.isFocusable = false
                binding.root.isFocusableInTouchMode = false
                binding.root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
            binding.hgvCategory.apply {
                // Clear DPAD focus before emptying. Never set adapter = null: Fire OS
                // Leanback throws IllegalStateException when a nested HorizontalGridView
                // loses its adapter while the parent VerticalGridView is laying out.
                if (hasFocus() || findFocus() != null) {
                    findFocus()?.clearFocus()
                    clearFocus()
                }
                (adapter as? AppAdapter)?.submitList(emptyList())
                isFocusable = false
                isFocusableInTouchMode = false
                visibility = View.GONE
            }
            // P0: empty FF shelf must not leave chip.nextFocusDown → GONE hgv_category.
            // Loop Down to self; pin (if focusable) is preferred when shown.
            if (keepForFastForward) {
                val chip = binding.chipSeriesFastForward
                val pin = binding.btnShelfPin
                val downTarget = when {
                    pin.visibility == View.VISIBLE && pin.isFocusable -> pin.id
                    else -> chip.id
                }
                chip.nextFocusDownId = downTarget
                chip.nextFocusUpId = chip.nextFocusUpId.takeIf { it != View.NO_ID } ?: chip.id
            }
            clearShelfWeather(binding.tvCategoryTitle, binding.hgvCategory, binding.tvShelfWeather)
            return
        }
        binding.root.visibility = View.VISIBLE
        binding.root.isFocusable = false
        binding.root.isFocusableInTouchMode = false
        binding.root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        bindShelfWeather(binding.tvCategoryTitle, binding.hgvCategory, binding.tvShelfWeather)

        // Non-empty: restore chip Down into the shelf grid (XML default).
        if (SeriesFastForward.supportsShelf(category.name)) {
            binding.chipSeriesFastForward.nextFocusDownId = R.id.hgv_category
        }
        binding.hgvCategory.apply {
            visibility = View.VISIBLE
            // FA-028: Cap Home shelf row height (CW → Ambient strip; else poster + title meta).
            // Tag stores density mode so recreate/density changes re-apply; avoid every-bind
            // setRowHeight while the parent VerticalGridView is measuring (Leanback SO).
            val densMode = com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.mode()
            val continueWatching = category.list.any { item ->
                when (item.itemTypeOrNull()) {
                    AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM,
                    AppAdapter.Type.EPISODE_CONTINUE_WATCHING_TV_ITEM,
                    -> true
                    else -> false
                }
            }
            val heightDp = if (continueWatching) {
                com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.ambientContinueStripHeightDp(densMode)
            } else {
                com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.shelfPosterHeightDp(densMode) +
                    com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.shelfTitleMetaBudgetDp(densMode)
            }
            val heightTag = densMode to continueWatching
            if (getTag(R.id.tv_shelf_row_height_tag) != heightTag) {
                setTag(R.id.tv_shelf_row_height_tag, heightTag)
                setRowHeight(
                    com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.dpToPx(this, heightDp),
                )
            }

            (context.toActivity()?.getCurrentFragment() as? HomeTvFragment)?.let { home ->
                setRecycledViewPool(home.shelfPool)
                itemAnimator = null
                setItemViewCacheSize(6)
                // Prefetch fewer nested posters during parent measure (ANR on CreateViewHolder).
                (layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                    ?.initialPrefetchItemCount = TV_SHELF_PREFETCH
            }
            // Row height is fixed above — stop nested wrap_content remeasure storms.
            setHasFixedSize(true)
            val categoryAdapter = (adapter as? AppAdapter) ?: AppAdapter().also { adapter = it }
            categoryAdapter.apply {
                this.onMovieClickListener = onMovieClick
                this.onTvShowClickListener = onTvShowClick
                this.onMovieLongClickListener = onMovieLongClick
                this.onTvShowLongClickListener = onTvShowLongClick
            }
            // Chunk nested submitList so VerticalGridView measure is not blocked by
            // inflating an entire shelf of posters in one frame (BETTERSTREAMFLIX-3S/20).
            val fullList = category.list
            val listSig = buildString {
                append(fullList.size)
                append('|')
                fullList.take(3).forEach { item ->
                    append(item.javaClass.simpleName)
                    append(':')
                    append(
                        when (item) {
                            is com.dskja.betterstreamflix.models.Movie -> item.id
                            is com.dskja.betterstreamflix.models.TvShow -> item.id
                            is com.dskja.betterstreamflix.models.Episode -> item.id
                            else -> item.hashCode()
                        },
                    )
                    append(';')
                }
            }
            if (getTag(R.id.tv_shelf_list_sig_tag) == listSig &&
                categoryAdapter.itemCount == fullList.size
            ) {
                // Same shelf content already fully bound — skip inflate storm on rebind.
                setItemSpacing(category.itemSpacing)
            } else {
                setTag(R.id.tv_shelf_list_sig_tag, listSig)
                val bindToken = Any()
                setTag(R.id.tv_shelf_bind_token_tag, bindToken)
                fun submitPrefix(count: Int) {
                    if (getTag(R.id.tv_shelf_bind_token_tag) !== bindToken) return
                    if (!isAttachedToWindow) return
                    val n = count.coerceAtMost(fullList.size)
                    categoryAdapter.submitList(if (n >= fullList.size) fullList else fullList.take(n))
                    if (n < fullList.size) {
                        post {
                            submitPrefix(n + TV_SHELF_BIND_CHUNK)
                        }
                    }
                }
                submitPrefix(TV_SHELF_INITIAL_BIND)
                setItemSpacing(category.itemSpacing)
            }

            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            nextFocusLeftId = R.id.nav_main
            // First *visible* shelf tile → side nav (focusOutFront alone is unreliable on Fire OS).
            if (getTag(R.id.tv_shelf_nav_focus_listener_tag) != true) {
                setTag(R.id.tv_shelf_nav_focus_listener_tag, true)
                fun rewireLeftmostNav() {
                    val lm = layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                    val firstVisible = lm?.findFirstVisibleItemPosition()
                        ?: (0 until childCount).mapNotNull { getChildAt(it) }
                            .minOfOrNull { getChildAdapterPosition(it) }
                            ?.takeIf { it != RecyclerView.NO_POSITION }
                        ?: 0
                    for (i in 0 until childCount) {
                        val child = getChildAt(i) ?: continue
                        val pos = getChildAdapterPosition(child)
                        child.nextFocusLeftId =
                            if (pos == firstVisible || pos == 0) R.id.nav_main else View.NO_ID
                    }
                }
                addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) {
                        rewireLeftmostNav()
                    }

                    override fun onChildViewDetachedFromWindow(view: View) = Unit
                })
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        if (newState == RecyclerView.SCROLL_STATE_IDLE) rewireLeftmostNav()
                    }
                })
                setOnChildViewHolderSelectedListener(
                    object : androidx.leanback.widget.OnChildViewHolderSelectedListener() {
                        override fun onChildViewHolderSelected(
                            parent: RecyclerView,
                            child: RecyclerView.ViewHolder?,
                            position: Int,
                            subposition: Int,
                        ) {
                            rewireLeftmostNav()
                        }
                    },
                )
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
            // One neighbor is enough for the loop clone; two full-bleed originals
            // stall mid-range phones while the hero is on screen.
            offscreenPageLimit = 1
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
                (getChildAt(0) as? RecyclerView)?.apply {
                    isNestedScrollingEnabled = false
                    itemAnimator = null
                }
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
            private fun indicatorForPager(position: Int): Int {
                return if (!useLoop) {
                    position.coerceIn(0, (source.lastIndex).coerceAtLeast(0))
                } else {
                    when (position) {
                        0 -> source.lastIndex
                        items.lastIndex -> 0
                        else -> (position - 1).coerceIn(0, source.lastIndex)
                    }
                }
            }

            private fun syncDots(indicatorPosition: Int) {
                if (indicatorPosition == lastMobileDotIndex) return
                lastMobileDotIndex = indicatorPosition
                category.selectedIndex = indicatorPosition
                if (exp) {
                    updateExpDots(binding, indicatorPosition)
                } else {
                    updateMobilePageIndicator(binding, indicatorPosition, source.size)
                }
            }

            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int,
            ) {
                if (swiperHandler !== handler || bindingAdapterPosition == RecyclerView.NO_POSITION) {
                    return
                }
                if (source.isEmpty()) return
                // Prefer the page that owns most of the viewport so dots track mid-swipe.
                val owning = if (positionOffset >= 0.5f) {
                    (position + 1).coerceAtMost(items.lastIndex)
                } else {
                    position
                }
                syncDots(indicatorForPager(owning))
            }

            override fun onPageSelected(position: Int) {
                if (swiperHandler !== handler || bindingAdapterPosition == RecyclerView.NO_POSITION) {
                    return
                }
                val indicatorPosition = indicatorForPager(position)
                syncDots(indicatorPosition)
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
        get() = androidx.core.content.ContextCompat.getColor(
            context,
            R.color.cinema_glass_bright,
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
            val isActive = selected == index
            view.isSelected = isActive
            val lp = view.layoutParams as? LinearLayout.LayoutParams
            if (lp != null) {
                val activeWidth = 15
                val dotSize = 15
                // Non-exp uses equal size; still refresh tint via selected state.
                lp.width = dotSize
                view.layoutParams = lp
            }
            view.requestLayout()
        }
        binding.llDotsIndicator.requestLayout()
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
        val activeColor = androidx.core.content.ContextCompat.getColor(
            context,
            R.color.cinema_text,
        )
        val inactive = expDotInactive
        val activeWidth = 20.dp(context)
        val dotSize = 6.dp(context)
        if (!DeviceCapabilities.shouldReduceHomeEffects(context)) {
            TransitionManager.beginDelayedTransition(
                binding.llDotsIndicator,
                AutoTransition().setDuration(180),
            )
        }
        binding.llDotsIndicator.children.forEachIndexed { index, view ->
            val isActive = index == selected
            view.layoutParams = (view.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (isActive) activeWidth else dotSize
            }
            view.requestLayout()
            view.backgroundTintList = ColorStateList.valueOf(if (isActive) activeColor else inactive)
            view.isSelected = isActive
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
        binding.llDotsIndicator.requestLayout()
    }

    /**
     * Featured page chrome: at most [MAX_VISIBLE_FEATURED_DOTS] dots, otherwise a compact
     * "3/12" label. On Leanback, dots are focusable/clickable to jump the index.
     */
    private fun bindFeaturedPageIndicator(
        container: LinearLayout,
        total: Int,
        selected: Int,
        @Suppress("UNUSED_PARAMETER") exp: Boolean,
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
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        context,
                        R.color.cinema_text_soft,
                    ),
                )
                textSize = 14f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            container.addView(label)
            return
        }
        val activeColor = androidx.core.content.ContextCompat.getColor(
            context,
            R.color.cinema_text,
        )
        val inactiveColor = androidx.core.content.ContextCompat.getColor(
            context,
            R.color.cinema_glass_bright,
        )
        val density = context.resources.displayMetrics.density
        val inactiveSize = (7 * density).toInt()
        val activeWidth = (22 * density).toInt()
        val activeHeight = (7 * density).toInt()
        val margin = (5 * density).toInt()
        repeat(total) { index ->
            val isActive = safeSelected == index
            val view = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    if (isActive) activeWidth else inactiveSize,
                    if (isActive) activeHeight else inactiveSize,
                ).apply {
                    setMargins(margin, 0, margin, 0)
                }
                setBackgroundResource(
                    if (isActive) R.drawable.bg_featured_dot_active
                    else R.drawable.bg_featured_dot_inactive,
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
            binding.tvCategoryTitle.text = localizedCategoryTitle(category.name)
        }
        category.selectedIndex = FeaturedTvRotation.coerceIndex(
            category.selectedIndex,
            category.list.size,
        )
        val selected = category.list.getOrNull(category.selectedIndex) as? Show
        if (selected == null) {
            binding.tvSwiperTitle.text = ""
            binding.ivSwiperLogo.visibility = View.INVISIBLE
            // Empty Featured must not leave focusable CTAs that trap DPAD on Home.
            binding.btnSwiperWatchNow.visibility = View.GONE
            binding.btnSwiperAddToList.visibility = View.GONE
            binding.llDotsIndicator.visibility = View.GONE
            return
        }
        binding.btnSwiperWatchNow.visibility = View.VISIBLE
        // My List visibility is owned by FeaturedSwiperChrome.bindListButton below.

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

        binding.tvSwiperOverview.apply {
            text = when (selected) {
                is Movie -> selected.overview
                is TvShow -> selected.overview
            }
            // Empty overview used to reserve five lines of dead space over the backdrop.
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        val featuredTitle = when (selected) {
            is Movie -> selected.title
            is TvShow -> selected.title
            else -> context.getString(R.string.a11y_untitled)
        }
        binding.btnSwiperWatchNow.apply {
            FeaturedSwiperChrome.wireWatchButton(this)
            text = FeaturedHeroController.watchCtaLabel(context, selected)
            contentDescription = context.getString(R.string.a11y_featured_watch, featuredTitle)
            // DPAD: left → side nav (parity with shelf tiles); up → profile chip;
            // down stays unset so VerticalGridView focus search lands on the next shelf
            // (forcing parent.id traps DPAD-down / freezes remote near bottom).
            nextFocusLeftId = R.id.nav_main
            (context.toActivity()?.findViewById<View>(R.id.tv_home_profile_chip))?.let { chip ->
                nextFocusUpId = chip.id
            }
            // Prefer Support CTA / first shelf; never leave DOWN as NO_ID near overscan.
            nextFocusDownId = R.id.btn_support_banner_cta
            post {
                val support = context.toActivity()?.findViewById<View>(R.id.btn_support_banner_cta)
                val downId = when {
                    support != null && support.isShown && support.isFocusable -> support.id
                    else -> View.NO_ID
                }
                nextFocusDownId = downId
                binding.btnSwiperAddToList.nextFocusDownId = downId
            }
            if (ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
                // Keep Cinema CTA ink — Material onPrimary would wipe cinema_cta_ink.
                FeaturedSwiperChrome.wireWatchButton(this)
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { applyExpPress() }
            }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                FeaturedProviderSwitch.runWithProvider(selected, context) {
                    // Global actions work from Home / Movies / TV Shows / Search
                    // (home-only Safe Args crashed after provider switch — BETTERSTREAMFLIX-2Z).
                    val nav = findNavController()
                    when (selected) {
                        is Movie -> nav.navigate(
                            R.id.action_global_movie,
                            bundleOf("id" to selected.id),
                        )
                        is TvShow -> nav.navigate(
                            R.id.action_global_tv_show,
                            bundleOf(
                                "id" to selected.id,
                                "poster" to selected.poster,
                                "banner" to selected.banner,
                            ),
                        )
                    }
                }
            }
            // Long-press Watch → trailer (parity with mobile Featured).
            setOnLongClickListener { view ->
                playFeaturedTrailer(selected, view)
            }
            setOnKeyListener { _, _, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                ) {
                    // RIGHT moves focus to My List — do not rotate the carousel.
                    if (binding.btnSwiperAddToList.visibility == View.VISIBLE &&
                        binding.btnSwiperAddToList.isFocusable
                    ) {
                        binding.btnSwiperAddToList.requestFocus()
                        return@setOnKeyListener true
                    }
                    return@setOnKeyListener false
                }
                // Menu / long-press remote: play trailer when available.
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (event.keyCode == KeyEvent.KEYCODE_MENU ||
                        event.keyCode == KeyEvent.KEYCODE_INFO)
                ) {
                    return@setOnKeyListener playFeaturedTrailer(selected, this)
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
        FeaturedSwiperChrome.bindListButton(binding.btnSwiperAddToList, inList, title = featuredTitle)
        binding.btnSwiperAddToList.apply {
            nextFocusLeftId = R.id.btn_swiper_watch_now
            (context.toActivity()?.findViewById<View>(R.id.tv_home_profile_chip))?.let { chip ->
                nextFocusUpId = chip.id
            }
            nextFocusDownId = View.NO_ID
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
                // RIGHT from My List does not rotate — leave focus search / no-op.
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
                FeaturedSwiperChrome.bindListButton(
                    binding.btnSwiperAddToList,
                    favorite,
                    title = featuredTitle,
                )
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
                FeaturedSwiperChrome.bindListButton(
                    binding.btnSwiperAddToList,
                    favorite,
                    title = featuredTitle,
                )
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
                // Dots are decorative page chrome — keep them out of the DPAD chain on TV.
                leanbackFocusable = false,
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
        val activeColor = androidx.core.content.ContextCompat.getColor(
            context,
            R.color.cinema_text,
        )
        val inactiveColor = androidx.core.content.ContextCompat.getColor(
            context,
            R.color.cinema_glass_bright,
        )
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

    /**
     * Featured TV trailer path (parity with mobile long-press on Watch).
     * @return true when a trailer play/lookup was started.
     */
    private fun playFeaturedTrailer(selected: Show, anchor: View): Boolean {
        fun play(url: String) {
            ExpMotion.hapticTap(anchor)
            val activity = context.toActivity() as? androidx.fragment.app.FragmentActivity
            TrailerPlaybackController.play(
                context = anchor.context,
                activity = activity,
                trailerUrl = url,
            )
        }
        when (selected) {
            is Movie -> {
                val existing = selected.trailer
                if (!existing.isNullOrBlank()) {
                    play(existing)
                    return true
                }
                val year = selected.released?.format("yyyy")?.toIntOrNull()
                if (!TmdbUtils.hasTrailerLookupKeys(
                        tmdbId = selected.tmdbId,
                        imdbId = selected.imdbId,
                        title = selected.title,
                        year = year,
                    )
                ) {
                    return false
                }
                ExpMotion.hapticTap(anchor)
                itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                    val remote = withContext(Dispatchers.IO) {
                        TmdbUtils.listYoutubeTrailers(
                            tmdbId = selected.tmdbId,
                            isTv = false,
                            title = selected.title,
                            year = year,
                            imdbId = selected.imdbId,
                        )
                    }
                    val url = TrailerCatalog.preferredPlayableUrl(remote) ?: return@launch
                    if (selected.trailer.isNullOrBlank()) selected.trailer = url
                    play(url)
                }
                return true
            }
            is TvShow -> {
                val existing = selected.trailer
                if (!existing.isNullOrBlank()) {
                    play(existing)
                    return true
                }
                val year = selected.released?.format("yyyy")?.toIntOrNull()
                if (!TmdbUtils.hasTrailerLookupKeys(
                        tmdbId = selected.tmdbId,
                        imdbId = selected.imdbId,
                        title = selected.title,
                        year = year,
                    )
                ) {
                    return false
                }
                ExpMotion.hapticTap(anchor)
                itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                    val remote = withContext(Dispatchers.IO) {
                        TmdbUtils.listYoutubeTrailers(
                            tmdbId = selected.tmdbId,
                            isTv = true,
                            title = selected.title,
                            year = year,
                            imdbId = selected.imdbId,
                        )
                    }
                    val url = TrailerCatalog.preferredPlayableUrl(remote) ?: return@launch
                    if (selected.trailer.isNullOrBlank()) selected.trailer = url
                    play(url)
                }
                return true
            }
        }
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
