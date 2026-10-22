package com.betterstreamflix.app.ui

import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.platform.ContinueWatchingMerger
import com.betterstreamflix.app.utils.ArtworkUrls
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure policy for TV Ambient Mode — idle enter/exit, poster pool, burn-in shift.
 * JVM-safe for unit tests (no Android Context).
 */
object AmbientModePolicy {
    const val IDLE_ENTER_MS = 90_000L
    const val IMAGE_HOLD_MS = 24_000L
    const val BURN_IN_SHIFT_MS = 60_000L
    const val CROSSFADE_MS = 900
    const val KEN_BURNS_MS = 22_000L
    /** Stick Energy: shorter, subtler Ken Burns so Ambient still paints on Fire Stick. */
    const val KEN_BURNS_STICK_MS = 14_000L
    const val KEN_BURNS_SCALE_END = 1.06f
    const val KEN_BURNS_SCALE_END_STICK = 1.02f
    const val MAX_POOL = 24
    const val MAX_CONTINUE = 12
    const val MAX_SHIFT_PX = 18f
    /** How often to re-check prefs / a11y / Stick Energy while Ambient is active. */
    const val ACTIVE_GATE_RECHECK_MS = 2_000L

    data class AmbientImage(
        val key: String,
        val url: String,
        val title: String = "",
    )

    fun shouldEnter(
        enabled: Boolean,
        highEndOk: Boolean,
        idleElapsedMs: Long,
        idleThresholdMs: Long = IDLE_ENTER_MS,
        poolSize: Int,
        modalBlocking: Boolean,
        alreadyActive: Boolean,
    ): Boolean {
        if (alreadyActive) return false
        if (!enabled) return false
        if (!highEndOk) return false
        if (modalBlocking) return false
        if (poolSize <= 0) return false
        return idleElapsedMs >= idleThresholdMs
    }

    /**
     * Whether Ambient may run on this device class.
     *
     * Stick Energy alone does **not** hard-off Ambient (use reduced Ken Burns instead).
     * [hardReduceEffects] covers low-RAM / constrained playback / animator-scale 0 —
     * anything that is not “Stick Energy only”.
     * TalkBack, reduce-motion (animator-off), and High Contrast hard-off enter.
     */
    fun highEndOk(
        hardReduceEffects: Boolean,
        touchExploration: Boolean,
        reduceMotion: Boolean,
        highContrast: Boolean = false,
    ): Boolean {
        if (hardReduceEffects) return false
        if (touchExploration) return false
        if (reduceMotion) return false
        if (highContrast) return false
        return true
    }

    fun kenBurnsScaleEnd(stickEnergy: Boolean): Float =
        if (stickEnergy) KEN_BURNS_SCALE_END_STICK else KEN_BURNS_SCALE_END

    fun kenBurnsDurationMs(stickEnergy: Boolean): Long =
        if (stickEnergy) KEN_BURNS_STICK_MS else KEN_BURNS_MS

    fun resolvePrefEnabled(explicit: Boolean?): Boolean = explicit ?: true

    fun nextImageIndex(current: Int, size: Int): Int {
        if (size <= 0) return 0
        if (size == 1) return 0
        return (current.coerceIn(0, size - 1) + 1) % size
    }

    /**
     * Deterministic soft pixel-shift for burn-in safety.
     * Returns (translationX, translationY) in px within ±[maxShiftPx].
     */
    fun burnInOffsetPx(tick: Int, maxShiftPx: Float = MAX_SHIFT_PX): Pair<Float, Float> {
        val t = tick.coerceAtLeast(0)
        val angle = t * 0.85
        val x = (sin(angle) * maxShiftPx).toFloat()
        val y = (cos(angle * 0.7) * maxShiftPx * 0.65f).toFloat()
        return x to y
    }

    fun buildPool(
        featured: List<AppAdapter.Item>,
        continueWatching: List<AppAdapter.Item>,
        favorites: List<AppAdapter.Item>,
    ): List<AmbientImage> {
        val out = ArrayList<AmbientImage>(MAX_POOL)
        val seen = HashSet<String>()
        fun add(item: AppAdapter.Item?) {
            if (out.size >= MAX_POOL) return
            val image = imageOf(item) ?: return
            if (!seen.add(image.key)) return
            out += image
        }
        featured.forEach(::add)
        continueWatching.forEach(::add)
        favorites.forEach(::add)
        return out
    }

    fun imageOf(item: AppAdapter.Item?): AmbientImage? {
        item ?: return null
        return when (item) {
            is Movie -> {
                val url = ArtworkUrls.featuredBannerOrPoster(item.banner, item.poster)
                    ?: return null
                AmbientImage(
                    key = ContinueWatchingMerger.identityKey(item) ?: "movie:${item.id}",
                    url = url,
                    title = item.title,
                )
            }
            is TvShow -> {
                val url = ArtworkUrls.featuredBannerOrPoster(item.banner, item.poster)
                    ?: return null
                AmbientImage(
                    key = ContinueWatchingMerger.identityKey(item) ?: "tv:${item.id}",
                    url = url,
                    title = item.title,
                )
            }
            is Episode -> {
                val show = item.tvShow
                val url = ArtworkUrls.featuredBannerOrPoster(
                    show?.banner,
                    item.poster ?: show?.poster,
                ) ?: return null
                AmbientImage(
                    key = ContinueWatchingMerger.identityKey(item)
                        ?: "ep:${item.id}",
                    url = url,
                    title = show?.title ?: item.title.orEmpty(),
                )
            }
            else -> null
        }
    }

    // KeyEvent constants inlined so JVM unit tests need no android.view import.
    const val KEYCODE_BACK = 4
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_CENTER = 23
    const val KEYCODE_ENTER = 66
    const val KEYCODE_NUMPAD_ENTER = 160

    /** D-pad Left/Right stay inside the Continue strip while ambient is up. */
    fun isStripNavigationKey(keyCode: Int): Boolean = when (keyCode) {
        KEYCODE_DPAD_LEFT,
        KEYCODE_DPAD_RIGHT,
        KEYCODE_DPAD_UP,
        KEYCODE_DPAD_DOWN,
        -> true
        else -> false
    }

    /** Center / Enter activate a strip tile — exit Ambient after the click. */
    fun isStripActivateKey(keyCode: Int): Boolean = when (keyCode) {
        KEYCODE_DPAD_CENTER,
        KEYCODE_ENTER,
        KEYCODE_NUMPAD_ENTER,
        -> true
        else -> false
    }

    fun shouldExitOnKey(keyCode: Int, stripHasFocus: Boolean): Boolean {
        if (keyCode == KEYCODE_BACK) return true
        if (stripHasFocus && isStripActivateKey(keyCode)) return true
        if (stripHasFocus && isStripNavigationKey(keyCode)) return false
        // Any other key wakes the living-room UI.
        return true
    }
}
