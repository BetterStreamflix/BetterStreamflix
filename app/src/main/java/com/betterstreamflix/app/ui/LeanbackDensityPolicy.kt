package com.betterstreamflix.app.ui

import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.betterstreamflix.app.BuildConfig
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.utils.UserPreferences

/**
 * TV Leanback poster density: Compact (more tiles) vs Comfort (larger posters).
 *
 * Column counts, shelf/grid poster sizes, Ambient Continue strip height, and
 * common item spacings are owned here so Movies / TV / Genre / People / Search /
 * Favorites / Downloads, Home shelves, Live pins, and Ambient stay in sync.
 *
 * Watch Together / Sidekick / Cast Room overlays have no poster grids; they use the
 * chrome density contract ([overlayChromePadDp] / [overlayStackGapDp]) so dialog and
 * sheet padding tracks Compact vs Comfort. Post-party focus lands on density-correct
 * shelves after rematerialize.
 *
 * Per-surface column ladders intentionally differ (catalog +2, genre/downloads +2,
 * favorites tile-width math) — Compact always adds capacity relative to Comfort.
 */
object LeanbackDensityPolicy {

    const val COMPACT = "compact"
    const val COMFORT = "comfort"
    /** High-end Leanback default — larger posters, fewer columns. */
    const val DEFAULT = COMFORT

    private const val TAG = "LeanbackDensity"

    enum class Mode {
        COMPACT,
        COMFORT,
    }

    fun resolve(raw: String?): Mode = when (raw?.lowercase()) {
        COMPACT -> Mode.COMPACT
        COMFORT -> Mode.COMFORT
        null, "" -> Mode.COMFORT
        else -> {
            runCatching {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Unknown leanback density \"$raw\" → Comfort")
                }
            }
            Mode.COMFORT
        }
    }

    /**
     * Effective mode for the active profile. Kids Fortress forces Comfort so
     * Compact's denser wall does not apply on kids profiles (LD-019).
     *
     * Prefs may be unread before [UserPreferences] setup (unit tests / early
     * Search column probes) — fall back to Comfort rather than throwing.
     */
    fun mode(raw: String? = null): Mode {
        if (runCatching { ProfileManager.isKidsActive() }.getOrDefault(false)) {
            return Mode.COMFORT
        }
        val value = raw ?: runCatching { UserPreferences.leanbackDensity }.getOrNull()
        return resolve(value)
    }

    fun valueOf(mode: Mode): String = when (mode) {
        Mode.COMPACT -> COMPACT
        Mode.COMFORT -> COMFORT
    }

    /** Catalog / genre / people VerticalGridView column count. */
    fun posterGridColumns(
        mode: Mode = mode(),
        largeText: Boolean = runCatching { UserPreferences.largeText }.getOrDefault(false),
    ): Int {
        val base = when (mode) {
            Mode.COMPACT -> 7
            Mode.COMFORT -> 5
        }
        // Large Text + Compact packs labels into thin cells — drop one column (LD-009).
        return if (largeText && mode == Mode.COMPACT) (base - 1).coerceAtLeast(5) else base
    }

    /** Home / search shelf poster ImageView height in dp. */
    fun shelfPosterHeightDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 180
        Mode.COMFORT -> 240
    }

    /**
     * Live Hub horizontal shelf poster height (LD-026).
     * Compact is denser than catalog shelves so more channels fit; Comfort matches
     * [shelfPosterHeightDp].
     */
    fun liveShelfPosterHeightDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 150
        Mode.COMFORT -> shelfPosterHeightDp(mode)
    }

    /** Title + progress budget under Ambient Continue posters. */
    fun ambientContinueMetaBudgetDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 56
        Mode.COMFORT -> 64
    }

    /**
     * Title/meta budget under standard Home shelf posters (FA-028).
     * Matches Ambient Continue meta so row caps stay consistent.
     */
    fun shelfTitleMetaBudgetDp(mode: Mode = mode()): Int = ambientContinueMetaBudgetDp(mode)

    /**
     * Overlay dialog / sheet outer chrome pad (LD-018 / SS-B10-12).
     * Compact 12dp / Comfort 16dp — Watch Together, Cast Room, Sidekick, Device Link.
     */
    fun overlayChromePadDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 12
        Mode.COMFORT -> 16
    }

    /**
     * Overlay vertical stack / reaction-row gap (LD-018).
     * Same ladder as [itemSpacingDp] (Compact 8 / Comfort 12).
     */
    fun overlayStackGapDp(mode: Mode = mode()): Int = itemSpacingDp(mode)

    /** Ambient Continue HGV height: shelf poster + meta/title budget. */
    fun ambientContinueStripHeightDp(mode: Mode = mode()): Int =
        shelfPosterHeightDp(mode) + ambientContinueMetaBudgetDp(mode)

    /**
     * Ambient Continue strip row height (poster + meta budget).
     * Alias kept for callers that still use the shorter name.
     */
    fun ambientStripHeightDp(mode: Mode = mode()): Int =
        ambientContinueStripHeightDp(mode)

    /**
     * Featured hero card height hint (mobile card / density-aware surfaces).
     * Compact is modestly shorter; Comfort matches the default dimen (580dp).
     */
    fun featuredCardHeightDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 520
        Mode.COMFORT -> 580
    }

    /** Catalog GRID cell poster height (decode + optional min height). */
    fun gridPosterHeightDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 160
        Mode.COMFORT -> 200
    }

    fun searchGenreColumns(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 6
        Mode.COMFORT -> 4
    }

    fun searchResultColumns(
        mode: Mode = mode(),
        largeText: Boolean = runCatching { UserPreferences.largeText }.getOrDefault(false),
    ): Int = posterGridColumns(mode, largeText)

    /** Approximate tile width used for Favorites span math. */
    fun favoritesTileWidthDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 150
        Mode.COMFORT -> 220
    }

    fun downloadsLibraryColumns(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 6
        Mode.COMFORT -> 4
    }

    fun favoritesColumnCount(screenWidthDp: Int, mode: Mode = mode()): Int {
        val minCols = if (mode == Mode.COMFORT) 3 else 4
        return maxOf(minCols, screenWidthDp / favoritesTileWidthDp(mode).coerceAtLeast(1))
    }

    /** Home shelf / Live Hub horizontal item spacing. */
    fun itemSpacingDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 8
        Mode.COMFORT -> 12
    }

    /** Movies / TV / Genre / Search catalog grid spacing. */
    fun catalogItemSpacingDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 12
        Mode.COMFORT -> 16
    }

    fun downloadsLibrarySpacingDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 8
        Mode.COMFORT -> 12
    }

    fun peopleFilmographySpacingDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 14
        Mode.COMFORT -> 20
    }

    fun focusZoomScale(mode: Mode = mode()): Float = when (mode) {
        Mode.COMPACT -> 1.05f
        Mode.COMFORT -> 1.08f
    }

    fun focusStrokeWidthDp(mode: Mode = mode(), highContrast: Boolean = false): Int {
        val base = when (mode) {
            Mode.COMPACT -> 3
            Mode.COMFORT -> 4
        }
        return if (highContrast) base + 2 else base
    }

    fun channelPinSizeDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 52
        Mode.COMFORT -> 64
    }

    fun channelPinItemWidthDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 64
        Mode.COMFORT -> 76
    }

    fun peopleAvatarSizeDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 64
        Mode.COMFORT -> 80
    }

    fun peopleItemWidthDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 80
        Mode.COMFORT -> 96
    }

    fun genreLabelTextSp(mode: Mode = mode()): Float = when (mode) {
        Mode.COMPACT -> 13f
        Mode.COMFORT -> 16f
    }

    fun supportBannerHeightDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 88
        Mode.COMFORT -> 110
    }

    fun seasonEpisodeRowHeightDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 400
        Mode.COMFORT -> 460
    }

    fun searchMemoryChipPadHDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 12
        Mode.COMFORT -> 16
    }

    fun searchMemoryChipPadVDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 6
        Mode.COMFORT -> 10
    }

    fun offlineStudioCompactChrome(mode: Mode = mode()): Boolean = mode == Mode.COMPACT

    /** LD-037: Home remix / shelf pin chrome size (dp). */
    fun chromeIconSizeDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 40
        Mode.COMFORT -> 48
    }

    fun applyChromeIconSize(view: View, mode: Mode = mode()) {
        val sizePx = dpToPx(view, chromeIconSizeDp(mode))
        val lp = view.layoutParams ?: return
        if (lp.width == sizePx && lp.height == sizePx) return
        lp.width = sizePx
        lp.height = sizePx
        view.layoutParams = lp
        view.requestLayout()
    }

    /**
     * Apply [shelfPosterHeightDp] (+ matching 2:3 width) to a TV shelf poster view.
     */
    fun applyShelfPosterHeight(poster: View, mode: Mode = mode()) {
        applyPosterHeightDp(poster, shelfPosterHeightDp(mode))
    }

    /**
     * Apply [liveShelfPosterHeightDp] (+ matching 2:3 width) for Live Hub shelves (LD-026).
     */
    fun applyLiveShelfPosterHeight(poster: View, mode: Mode = mode()) {
        applyPosterHeightDp(poster, liveShelfPosterHeightDp(mode))
    }

    private fun applyPosterHeightDp(poster: View, heightDp: Int) {
        val density = poster.resources.displayMetrics.density
        val hPx = (heightDp * density).toInt()
        val wPx = (hPx * 2 / 3f).toInt()
        val lp = poster.layoutParams ?: return
        val widthFixed = lp.width != ViewGroup.LayoutParams.MATCH_PARENT &&
            lp.width != ViewGroup.LayoutParams.WRAP_CONTENT &&
            lp.width > 0
        if (lp.height == hPx && (!widthFixed || lp.width == wPx)) {
            return
        }
        lp.height = hPx
        if (widthFixed || lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.width = wPx
        }
        poster.layoutParams = lp
        poster.requestLayout()
    }

    /** Size Ambient Continue HGV from shelf poster height + meta budget. */
    fun applyAmbientContinueStripHeight(strip: View, mode: Mode = mode()) {
        val hPx = (ambientContinueStripHeightDp(mode) * strip.resources.displayMetrics.density).toInt()
        val lp = strip.layoutParams ?: return
        if (lp.height == hPx) return
        lp.height = hPx
        strip.layoutParams = lp
        strip.requestLayout()
        (strip as? androidx.leanback.widget.HorizontalGridView)?.setRowHeight(hPx)
    }

    /**
     * Glide override for shelf posters; width follows 2:3.
     * Floor is relative to mode height so density=1.0 still differs Compact vs Comfort.
     */
    fun shelfPosterOverridePx(density: Float, mode: Mode = mode()): Pair<Int, Int> =
        posterOverridePx(density, shelfPosterHeightDp(mode))

    /** Glide override for Live Hub shelf posters (LD-026). */
    fun liveShelfPosterOverridePx(density: Float, mode: Mode = mode()): Pair<Int, Int> =
        posterOverridePx(density, liveShelfPosterHeightDp(mode))

    /** Glide override for catalog GRID posters. */
    fun gridPosterOverridePx(density: Float, mode: Mode = mode()): Pair<Int, Int> =
        posterOverridePx(density, gridPosterHeightDp(mode))

    private fun posterOverridePx(density: Float, hDp: Int): Pair<Int, Int> {
        val h = (hDp * density).toInt().coerceAtLeast(hDp)
        val w = (h * 2 / 3f).toInt().coerceAtLeast((hDp * 2 / 3f).toInt().coerceAtLeast(1))
        return w to h
    }

    fun dpToPx(view: View, dp: Int): Int =
        (dp * view.resources.displayMetrics.density).toInt()

    fun overlayChromePadPx(density: Float, mode: Mode = mode()): Int =
        (overlayChromePadDp(mode) * density).toInt()

    fun overlayStackGapPx(density: Float, mode: Mode = mode()): Int =
        (overlayStackGapDp(mode) * density).toInt()
}
