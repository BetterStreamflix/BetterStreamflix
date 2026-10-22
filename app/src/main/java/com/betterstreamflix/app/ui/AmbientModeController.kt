package com.betterstreamflix.app.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.leanback.widget.HorizontalGridView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.betterstreamflix.app.R
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.logo.FeaturedLogoEnrich
import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.player.PlayerCleanExit
import com.betterstreamflix.app.utils.ArtworkUrls
import com.betterstreamflix.app.utils.DeviceCapabilities
import com.betterstreamflix.app.utils.ExpAmbientGlow
import com.betterstreamflix.app.utils.UserPreferences

/**
 * TV Ambient Mode — idle soft poster cinemagraphs + Continue Watching strip.
 * Nested under Cinema Room ([CinemaRoomPolicy]); burn-in safe via rotation + pixel shift.
 */
class AmbientModeController(
    private val fragment: Fragment,
    private val root: View,
    private val homeContent: View,
    private val homeChrome: List<View>,
    private val modalBlocking: () -> Boolean = { false },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val stripAdapter = AppAdapter()

    // include android:id overrides the included root id → ambient_overlay.
    private val ambientRoot: View = root.findViewById(R.id.ambient_overlay)
        ?: root.findViewById(R.id.ambient_root)
    private val imageA: ImageView = ambientRoot.findViewById(R.id.iv_ambient_a)
    private val imageB: ImageView = ambientRoot.findViewById(R.id.iv_ambient_b)
    private val glowView: View? = ambientRoot.findViewById(R.id.v_ambient_glow)
    private val brandChrome: View? = ambientRoot.findViewById(R.id.ll_cinema_room_brand)
    private val brandLabel: TextView? = ambientRoot.findViewById(R.id.tv_cinema_room_brand)
    private val titleView: TextView = ambientRoot.findViewById(R.id.tv_ambient_title)
    private val strip: HorizontalGridView = ambientRoot.findViewById(R.id.hgv_ambient_continue)
    private var glowTarget: CustomTarget<Bitmap>? = null

    private var pool: List<AmbientModePolicy.AmbientImage> = emptyList()
    private var imageIndex = 0
    private var showingA = true
    private var burnTick = 0
    private var lastActivityAt = System.currentTimeMillis()
    private var active = false
    private var kenBurns: AnimatorSet? = null
    private var a11yManager: AccessibilityManager? = null
    private val a11yListener =
        AccessibilityManager.AccessibilityStateChangeListener { recheckGatesWhileActive() }
    private val touchExplorationListener =
        AccessibilityManager.TouchExplorationStateChangeListener { recheckGatesWhileActive() }

    private val idleCheck = object : Runnable {
        override fun run() {
            if (!fragment.isAdded) {
                scheduleIdleCheck()
                return
            }
            // While active: exit if prefs / a11y / Stick Energy / modal gates flip.
            if (active) {
                if (!enabled() || !highEndOk() || modalBlocking() || isModalBlocking()) {
                    exit(restoreFocus = true)
                } else {
                    scheduleIdleCheck()
                }
                return
            }
            if (!enabled()) {
                scheduleIdleCheck()
                return
            }
            val elapsed = System.currentTimeMillis() - lastActivityAt
            if (AmbientModePolicy.shouldEnter(
                    enabled = true,
                    highEndOk = highEndOk(),
                    idleElapsedMs = elapsed,
                    idleThresholdMs = UserPreferences.cinemaAmbientIdleMs,
                    poolSize = pool.size,
                    modalBlocking = modalBlocking() || isModalBlocking(),
                    alreadyActive = active,
                )
            ) {
                enter()
            } else {
                scheduleIdleCheck()
            }
        }
    }
    private val imageAdvance = object : Runnable {
        override fun run() {
            if (!active || !fragment.isAdded) return
            advanceImage()
            handler.postDelayed(this, AmbientModePolicy.IMAGE_HOLD_MS)
        }
    }
    private val burnInShift = object : Runnable {
        override fun run() {
            if (!active || !fragment.isAdded) return
            burnTick += 1
            val (x, y) = AmbientModePolicy.burnInOffsetPx(burnTick)
            val target = if (showingA) imageA else imageB
            target.animate().cancel()
            target.animate()
                .translationX(x)
                .translationY(y)
                .setDuration(1_800L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
            handler.postDelayed(this, AmbientModePolicy.BURN_IN_SHIFT_MS)
        }
    }

    init {
        strip.adapter = stripAdapter
        strip.setHasFixedSize(true)
        strip.itemAnimator = null
        strip.clipChildren = false
        strip.clipToPadding = false
        LeanbackDensityPolicy.applyAmbientContinueStripHeight(strip)
        ambientRoot.visibility = View.GONE
        ambientRoot.isFocusable = false
        ambientRoot.isClickable = false
        applyStripDensityHeight()
        brandLabel?.contentDescription =
            fragment.getString(R.string.cinema_room_brand_label)
    }

    fun isActive(): Boolean = active

    fun onUserActivity() {
        lastActivityAt = System.currentTimeMillis()
        if (active) {
            exit(restoreFocus = true)
        } else {
            scheduleIdleCheck()
        }
    }

    /** Reset idle clock without exiting an active Ambient session (scroll / touch). */
    fun resetIdleClock() {
        lastActivityAt = System.currentTimeMillis()
        if (!active) scheduleIdleCheck()
    }

    /**
     * Pref / Live Command / Stick Energy flipped — exit if gates no longer allow Ambient.
     */
    fun recheckGatesWhileActive() {
        if (!active) {
            scheduleIdleCheck()
            return
        }
        if (!enabled() || !highEndOk() || modalBlocking() || isModalBlocking()) {
            exit(restoreFocus = true)
        }
    }

    /**
     * @return true if the key was fully handled (ambient consumed / exited).
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            if (!active) lastActivityAt = System.currentTimeMillis()
            return false
        }
        if (!active) {
            lastActivityAt = System.currentTimeMillis()
            scheduleIdleCheck()
            return false
        }
        val stripFocused = strip.hasFocus() || strip.findFocus() != null
        // Activate: let the strip click fire, then exit without stealing focus back.
        if (stripFocused && AmbientModePolicy.isStripActivateKey(event.keyCode)) {
            handler.post {
                if (active) exit(restoreFocus = false)
            }
            return false
        }
        // FA-029: UP/DOWN while strip focused are strip-safe (no accidental exit).
        if (stripFocused &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            return false
        }
        if (AmbientModePolicy.shouldExitOnKey(event.keyCode, stripFocused)) {
            exit(restoreFocus = true)
            // Consume every wake key so restored Home does not also handle it.
            return true
        }
        return false
    }

    fun onCategoriesBound(categories: List<Category>) {
        val featured = categories.firstOrNull { Category.isFeaturedName(it.name) }?.list.orEmpty()
        val continueWatching = categories
            .firstOrNull { it.name == Category.CONTINUE_WATCHING ||
                it.name == fragment.getString(R.string.home_continue_watching) }
            ?.list
            .orEmpty()
        val favorites = categories
            .filter {
                it.name == Category.FAVORITE_MOVIES ||
                    it.name == Category.FAVORITE_TV_SHOWS ||
                    it.name == Category.RECENTLY_ADDED ||
                    it.name == fragment.getString(R.string.home_favorite_movies) ||
                    it.name == fragment.getString(R.string.home_favorite_tv_shows) ||
                    it.name == fragment.getString(R.string.home_recently_added)
            }
            .flatMap { it.list }
        pool = AmbientModePolicy.buildPool(featured, continueWatching, favorites)
        bindContinueStrip(continueWatching)
        if (active && pool.isEmpty()) {
            exit(restoreFocus = true)
        } else if (!active) {
            scheduleIdleCheck()
        }
    }

    fun onStart() {
        lastActivityAt = System.currentTimeMillis()
        applyStripDensityHeight()
        registerA11yListeners()
        scheduleIdleCheck()
    }

    fun onStop() {
        unregisterA11yListeners()
        handler.removeCallbacksAndMessages(null)
        if (active) exit(restoreFocus = false)
    }

    fun destroy() {
        unregisterA11yListeners()
        if (active) exit(restoreFocus = false)
        handler.removeCallbacksAndMessages(null)
        kenBurns?.cancel()
        kenBurns = null
        cancelViewAnimators()
        clearGlow()
        runCatching { Glide.with(imageA).clear(imageA) }
        runCatching { Glide.with(imageB).clear(imageB) }
        imageA.setImageDrawable(null)
        imageB.setImageDrawable(null)
        brandChrome?.visibility = View.GONE
        active = false
    }

    private fun enter() {
        if (active || pool.isEmpty() || !fragment.isAdded) return
        if (!enabled() || !highEndOk() || modalBlocking() || isModalBlocking()) return
        active = true
        imageIndex = 0
        burnTick = 0
        showingA = true
        applyStripDensityHeight()
        // Pause Featured auto-rotate while Ambient owns the canvas.
        (fragment as? com.betterstreamflix.app.fragments.home.HomeTvFragment)
            ?.resetSwiperSchedule()
        ambientRoot.visibility = View.VISIBLE
        ambientRoot.alpha = 0f
        ambientRoot.animate().alpha(1f).setDuration(480L).start()
        homeContent.animate().alpha(0f).setDuration(420L).withEndAction {
            if (active) homeContent.visibility = View.INVISIBLE
        }.start()
        homeChrome.forEach { it.animate().alpha(0f).setDuration(320L).start() }
        updateBrandChrome()
        showImage(pool[0], crossfade = false)
        scheduleImageLoop()
        handler.postDelayed(burnInShift, AmbientModePolicy.BURN_IN_SHIFT_MS)
        scheduleIdleCheck()
        strip.post {
            if (!active) return@post
            if (stripAdapter.itemCount > 0) {
                // FA-010: focus first strip child, not the HGV container.
                strip.setSelectedPosition(0)
                val child = strip.layoutManager?.findViewByPosition(0)
                    ?: strip.getChildAt(0)
                if (child != null) {
                    child.requestFocus()
                } else {
                    strip.requestFocus()
                }
            }
        }
    }

    private fun exit(restoreFocus: Boolean) {
        if (!active) return
        active = false
        handler.removeCallbacks(imageAdvance)
        handler.removeCallbacks(burnInShift)
        kenBurns?.cancel()
        kenBurns = null
        clearGlow()
        brandChrome?.visibility = View.GONE
        homeContent.visibility = View.VISIBLE
        homeContent.animate().alpha(1f).setDuration(280L).start()
        homeChrome.forEach { it.animate().alpha(1f).setDuration(280L).start() }
        ambientRoot.animate().alpha(0f).setDuration(240L).withEndAction {
            ambientRoot.visibility = View.GONE
            imageA.setImageDrawable(null)
            imageB.setImageDrawable(null)
            imageA.translationX = 0f
            imageA.translationY = 0f
            imageB.translationX = 0f
            imageB.translationY = 0f
        }.start()
        lastActivityAt = System.currentTimeMillis()
        scheduleIdleCheck()
        val home = fragment as? com.betterstreamflix.app.fragments.home.HomeTvFragment
        home?.resetSwiperSchedule()
        if (!fragment.isAdded) return
        when (
            CinemaRoomPolicy.focusRestorePriority(
                restoreFocus = restoreFocus,
                cleanExitPending = PlayerCleanExit.shouldRestoreHomeFocus(),
            )
        ) {
            CinemaRoomPolicy.FocusRestore.CLEAN_EXIT -> home?.restoreCleanExitFocus()
            CinemaRoomPolicy.FocusRestore.CONTENT -> home?.requestContentFocus()
            CinemaRoomPolicy.FocusRestore.NONE -> Unit
        }
    }

    private fun advanceImage() {
        if (pool.isEmpty()) return
        imageIndex = AmbientModePolicy.nextImageIndex(imageIndex, pool.size)
        val next = pool[imageIndex]
        showImage(next, crossfade = true)
        val prefetch = pool.getOrNull(AmbientModePolicy.nextImageIndex(imageIndex, pool.size))
        if (prefetch != null) {
            FeaturedLogoEnrich.prefetchBanner(fragment.requireContext(), prefetch.url)
        }
    }

    private fun showImage(image: AmbientModePolicy.AmbientImage, crossfade: Boolean) {
        val incoming = if (showingA) imageB else imageA
        val outgoing = if (showingA) imageA else imageB
        titleView.text = image.title
        titleView.contentDescription = image.title.ifBlank {
            fragment.getString(R.string.a11y_untitled)
        }
        val ctx = fragment.context ?: return
        val (w, h) = FeaturedSwiperChrome.tvBackdropOverride(ctx)
        val url = ArtworkUrls.preferFeatured(image.url) ?: image.url
        val crossfadeMs = CinemaRoomPolicy.ambientCrossfadeMs(UserPreferences.cinemaRoomEnabled)
        // View alpha crossfade only — avoid Glide transition + alpha double-dissolve.
        Glide.with(incoming)
            .load(url)
            .override(w, h)
            .centerCrop()
            .error(R.drawable.glide_fallback_cover)
            .dontAnimate()
            .into(incoming)
        if (crossfade) {
            incoming.alpha = 0f
            incoming.animate().alpha(1f).setDuration(crossfadeMs.toLong()).start()
            outgoing.animate().alpha(0f).setDuration(crossfadeMs.toLong()).start()
            showingA = !showingA
        } else {
            incoming.alpha = 1f
            outgoing.alpha = 0f
            showingA = incoming === imageA
        }
        startKenBurns(incoming)
        applyAmbientLightHint(url, w, h)
    }

    private fun startKenBurns(view: ImageView) {
        kenBurns?.cancel()
        view.animate().cancel()
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        val stickEnergy = fragment.context?.let { StickEnergyMode.isEnabled(it) } == true
        val end = AmbientModePolicy.kenBurnsScaleEnd(stickEnergy)
        val scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, end)
        val scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, end)
        kenBurns = AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = AmbientModePolicy.kenBurnsDurationMs(stickEnergy)
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun bindContinueStrip(items: List<AppAdapter.Item>) {
        LeanbackDensityPolicy.applyAmbientContinueStripHeight(strip)
        val stamped = items.take(AmbientModePolicy.MAX_CONTINUE).map { item ->
            when (item) {
                is Episode -> item.also {
                    it.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_TV_ITEM
                }
                is Movie -> item.also {
                    it.itemType = AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM
                }
                is com.betterstreamflix.app.models.TvShow -> item.also {
                    it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                }
                else -> item
            }
        }
        stripAdapter.submitList(stamped)
        strip.visibility = if (stamped.isEmpty()) View.GONE else View.VISIBLE
        ambientRoot.findViewById<View>(R.id.tv_ambient_label)?.visibility =
            if (stamped.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun applyStripDensityHeight() {
        if (fragment.context == null) return
        LeanbackDensityPolicy.applyAmbientContinueStripHeight(strip)
    }

    private fun scheduleIdleCheck() {
        handler.removeCallbacks(idleCheck)
        if (!fragment.isAdded) return
        if (active) {
            handler.postDelayed(idleCheck, AmbientModePolicy.ACTIVE_GATE_RECHECK_MS)
            return
        }
        if (!enabled() || !highEndOk() || pool.isEmpty() || modalBlocking() || isModalBlocking()) return
        val remaining = (UserPreferences.cinemaAmbientIdleMs -
            (System.currentTimeMillis() - lastActivityAt)).coerceAtLeast(1_000L)
        handler.postDelayed(idleCheck, remaining)
    }

    private fun scheduleImageLoop() {
        handler.removeCallbacks(imageAdvance)
        handler.postDelayed(imageAdvance, AmbientModePolicy.IMAGE_HOLD_MS)
    }

    private fun enabled(): Boolean = CinemaRoomPolicy.ambientAllowed(
        cinemaRoomEnabled = CinemaRoomPolicy.resolveEnabled(UserPreferences.cinemaRoomEnabled),
        ambientPrefEnabled = AmbientModePolicy.resolvePrefEnabled(
            UserPreferences.tvAmbientModeEnabled,
        ),
    )

    private fun highEndOk(): Boolean {
        val ctx = fragment.context ?: return false
        return AmbientModePolicy.highEndOk(
            hardReduceEffects = DeviceCapabilities.shouldHardReduceHomeEffects(ctx),
            touchExploration = FeaturedAdvancePolicy.isTouchExplorationEnabled(ctx),
            reduceMotion = DeviceCapabilities.isAnimatorDurationScaleOff(ctx),
            highContrast = UserPreferences.highContrast,
        )
    }

    /** FA-008 / FA-030: block Ambient while dialogs / known modals own the screen. */
    private fun isModalBlocking(): Boolean {
        val fm = fragment.activity?.supportFragmentManager ?: return false
        if (fm.fragments.any { it is androidx.fragment.app.DialogFragment && it.isVisible }) {
            return true
        }
        // Profile picker / remix / options often use AlertDialog on the activity window.
        val decor = fragment.activity?.window?.decorView
        if (decor is android.view.ViewGroup) {
            for (i in 0 until decor.childCount) {
                val child = decor.getChildAt(i)
                // Dialog windows sit as direct decor children with elevated elevation.
                if (child !== fragment.activity?.findViewById(android.R.id.content) &&
                    child.isShown &&
                    child.javaClass.name.contains("DecorView", ignoreCase = true).not() &&
                    child.elevation > 0f &&
                    child.findFocus() != null
                ) {
                    return true
                }
            }
        }
        return runCatching {
            fragment.requireActivity().supportFragmentManager
                .primaryNavigationFragment
            // Any shown DialogFragment already covered; also treat non-empty back stack of dialogs.
            fm.backStackEntryCount > 0 && fm.fragments.any {
                it.javaClass.simpleName.contains("Dialog", ignoreCase = true) && it.isVisible
            }
        }.getOrDefault(false)
    }

    private fun updateBrandChrome() {
        val show = CinemaRoomPolicy.brandChromeVisible(
            cinemaRoomEnabled = UserPreferences.cinemaRoomEnabled,
            ambientActive = active,
        )
        brandChrome?.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            brandChrome?.alpha = 0f
            brandChrome?.animate()?.alpha(1f)?.setDuration(420L)?.start()
            brandLabel?.contentDescription =
                fragment.getString(R.string.cinema_room_brand_label)
        }
    }

    private fun applyAmbientLightHint(url: String, width: Int, height: Int) {
        val glow = glowView ?: return
        val allowed = CinemaRoomPolicy.ambientLightHintsAllowed(
            cinemaRoomEnabled = UserPreferences.cinemaRoomEnabled,
            lightHintsPref = UserPreferences.cinemaAmbientLightHints,
            highEndOk = highEndOk(),
            highContrast = UserPreferences.highContrast,
        )
        if (!allowed) {
            clearGlow()
            return
        }
        val ctx = fragment.context ?: return
        clearGlowTargetOnly()
        val target = object : CustomTarget<Bitmap>(width, height) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                if (!active || !fragment.isAdded) return
                ExpAmbientGlow.apply(resource, glow)
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                // no-op — exit clears visibility
            }
        }
        glowTarget = target
        Glide.with(ctx).asBitmap().load(url).centerCrop().into(target)
    }

    private fun clearGlow() {
        clearGlowTargetOnly()
        glowView?.animate()?.cancel()
        glowView?.visibility = View.GONE
        glowView?.alpha = 0f
        glowView?.background = null
    }

    private fun clearGlowTargetOnly() {
        val ctx = fragment.context
        val target = glowTarget
        glowTarget = null
        if (ctx != null && target != null) {
            runCatching { Glide.with(ctx).clear(target) }
        }
    }

    private fun cancelViewAnimators() {
        ambientRoot.animate().cancel()
        homeContent.animate().cancel()
        homeChrome.forEach { it.animate().cancel() }
        imageA.animate().cancel()
        imageB.animate().cancel()
        brandChrome?.animate()?.cancel()
    }

    private fun registerA11yListeners() {
        val ctx = fragment.context ?: return
        val am = ctx.getSystemService(AccessibilityManager::class.java) ?: return
        a11yManager = am
        runCatching {
            am.addAccessibilityStateChangeListener(a11yListener)
            am.addTouchExplorationStateChangeListener(touchExplorationListener)
        }
    }

    private fun unregisterA11yListeners() {
        val am = a11yManager ?: return
        runCatching {
            am.removeAccessibilityStateChangeListener(a11yListener)
            am.removeTouchExplorationStateChangeListener(touchExplorationListener)
        }
        a11yManager = null
    }
}
