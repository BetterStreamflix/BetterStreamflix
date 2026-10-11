package com.dskja.betterstreamflix.ui

import android.view.View
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * TV Leanback poster density: Compact (more tiles) vs Comfort (larger posters).
 *
 * Column counts and shelf poster heights are owned here so Movies / TV / Genre /
 * People / Search / Favorites / Downloads and Home shelves stay in sync.
 */
object LeanbackDensityPolicy {

    const val COMPACT = "compact"
    const val COMFORT = "comfort"
    /** High-end Leanback default — larger posters, fewer columns. */
    const val DEFAULT = COMFORT

    enum class Mode {
        COMPACT,
        COMFORT,
    }

    fun resolve(raw: String?): Mode = when (raw?.lowercase()) {
        COMPACT -> Mode.COMPACT
        else -> Mode.COMFORT
    }

    fun mode(raw: String? = UserPreferences.leanbackDensity): Mode = resolve(raw)

    fun valueOf(mode: Mode): String = when (mode) {
        Mode.COMPACT -> COMPACT
        Mode.COMFORT -> COMFORT
    }

    /** Catalog / genre / people VerticalGridView column count. */
    fun posterGridColumns(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 7
        Mode.COMFORT -> 5
    }

    /** Home / search shelf poster ImageView height in dp. */
    fun shelfPosterHeightDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 180
        Mode.COMFORT -> 240
    }

    /**
     * Ambient Continue strip row height (poster + vertical padding).
     * Matches shelf poster height so Comfort tiles are not clipped in a fixed 148dp row.
     */
    fun ambientStripHeightDp(mode: Mode = mode()): Int =
        shelfPosterHeightDp(mode) + 8

    /**
     * Featured hero card height hint (mobile card / density-aware surfaces).
     * Compact is modestly shorter; Comfort matches the default dimen (580dp).
     */
    fun featuredCardHeightDp(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 520
        Mode.COMFORT -> 580
    }

    fun searchGenreColumns(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 6
        Mode.COMFORT -> 4
    }

    fun searchResultColumns(mode: Mode = mode()): Int = when (mode) {
        Mode.COMPACT -> 7
        Mode.COMFORT -> 5
    }

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

    /** Apply [shelfPosterHeightDp] to a TV shelf poster view (layout + next measure). */
    fun applyShelfPosterHeight(poster: View, mode: Mode = mode()) {
        val hPx = (shelfPosterHeightDp(mode) * poster.resources.displayMetrics.density).toInt()
        val lp = poster.layoutParams ?: return
        if (lp.height == hPx) return
        lp.height = hPx
        poster.layoutParams = lp
    }

    /** Glide override height (px) for shelf posters; width follows 2:3. */
    fun shelfPosterOverridePx(density: Float, mode: Mode = mode()): Pair<Int, Int> {
        val h = (shelfPosterHeightDp(mode) * density).toInt().coerceAtLeast(360)
        val w = (h * 2 / 3f).toInt().coerceAtLeast(240)
        return w to h
    }
}
