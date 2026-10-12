package com.dskja.betterstreamflix.ui

import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.ArtworkUrls
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Trailer → Stream crossfade posts: keep a full-bleed cover up so the handoff
 * never flashes black, while [TrailerPlayPipeline] keeps the first server hot.
 *
 * Flow: [arm] before leaving the trailer → [runWhenHot] short-waits for warm
 * servers → player [attachBridge] shows the cover → [revealStream] fades it out
 * on first ready frame.
 */
object TrailerStreamCrossfade {

    /** Short visual dissolve from cover → first stream frame. */
    const val CROSSFADE_MS = 320L

    /** Cap how long Stream Now waits for a warm server list before navigating. */
    const val HOT_WAIT_MS = 600L

    /** Safety: never leave the bridge up forever if playback never starts. */
    const val BRIDGE_MAX_MS = 12_000L

    /**
     * Living-room Trailer→Play dissolve when Cinema Room is on (TV player).
     * Mobile keeps [CROSSFADE_MS] unless callers pass an explicit duration.
     * Stick Energy / reduce-motion caps at [CROSSFADE_MS] (SE-006).
     */
    fun revealDurationMs(
        cinemaRoomEnabled: Boolean,
        reduceMotion: Boolean = false,
    ): Long = CinemaRoomPolicy.trailerCrossfadeMs(cinemaRoomEnabled, reduceMotion)

    data class Handoff(
        val contentId: String,
        val coverUrl: String?,
        val serverHot: Boolean,
        val armedAtMs: Long,
    )

    @Volatile
    private var handoff: Handoff? = null

    private val bridgeTimeouts = java.util.WeakHashMap<ImageView, Runnable>()

    /** Overridable for JVM unit tests. */
    @Volatile
    internal var elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() }

    fun arm(target: TrailerPlayPipeline.Target?) {
        if (target == null) return
        arm(target.contentId, target.videoType)
    }

    fun arm(contentId: String, videoType: Video.Type) {
        val id = canonicalizeId(contentId)
        if (id.isEmpty()) return
        val cover = coverUrlFor(videoType)
        val hot = TrailerPlayPipeline.isServersHot(id)
        if (!hot) {
            TrailerPlayPipeline.warm(
                TrailerPlayPipeline.Target(contentId = id, videoType = videoType),
            )
        }
        handoff = Handoff(
            contentId = id,
            coverUrl = cover,
            serverHot = hot,
            armedAtMs = elapsedRealtimeMs(),
        )
    }

    fun peek(): Handoff? = handoff

    fun consumeFor(contentId: String): Handoff? {
        val current = handoff ?: return null
        val want = canonicalizeId(contentId)
        if (!current.contentId.equals(want, ignoreCase = true)) {
            // Stale / mismatched arm — drop so a later open does not flash the wrong cover.
            handoff = null
            return null
        }
        handoff = null
        return current
    }

    fun clear() {
        handoff = null
    }

    fun coverUrlFor(videoType: Video.Type): String? = when (videoType) {
        is Video.Type.Movie ->
            ArtworkUrls.preferFeatured(videoType.poster)
                ?: ArtworkUrls.preferPoster(videoType.poster)
                ?: videoType.poster.takeIf { it.isNotBlank() }
        is Video.Type.Episode ->
            ArtworkUrls.bannerOrPoster(
                banner = videoType.tvShow.banner,
                poster = videoType.poster ?: videoType.tvShow.poster,
                hero = false,
            ) ?: ArtworkUrls.preferPoster(videoType.poster ?: videoType.tvShow.poster)
    }

    /**
     * Waits briefly for [TrailerPlayPipeline] warm servers, then runs [block]
     * on the main thread when the host lifecycle is at least STARTED.
     */
    fun runWhenHot(host: View, contentId: String, block: () -> Unit) {
        val id = canonicalizeId(contentId)
        val owner = host.findViewTreeLifecycleOwner()
        if (owner == null) {
            // Detached host — do not navigate on a wrong back stack.
            return
        }
        owner.lifecycleScope.launch {
            val hot = withContext(Dispatchers.IO) {
                TrailerPlayPipeline.awaitServersHot(id, HOT_WAIT_MS)
            }
            val current = handoff
            if (current != null && current.contentId.equals(id, ignoreCase = true)) {
                handoff = current.copy(serverHot = hot || current.serverHot)
            }
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return@launch
            }
            if (!host.isAttachedToWindow) {
                return@launch
            }
            block()
        }
    }

    /**
     * Shows the armed cover on the player bridge ImageView. Returns true when
     * a bridge was installed (caller should skip enter-screen fade-from-black).
     */
    fun attachBridge(
        bridge: ImageView,
        contentId: String,
        onTimeout: () -> Unit = { revealStream(bridge) },
    ): Boolean {
        val armed = consumeFor(contentId) ?: return false
        cancelBridgeTimeout(bridge)
        bridge.animate().cancel()
        bridge.alpha = 1f
        bridge.visibility = View.VISIBLE
        val url = armed.coverUrl
        if (!url.isNullOrBlank()) {
            Glide.with(bridge)
                .load(url)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade(120))
                .into(bridge)
        } else {
            // Still cover black with ink-tone solid via existing ImageView background.
            bridge.setImageDrawable(null)
        }
        val timeout = Runnable {
            if (bridge.visibility == View.VISIBLE) onTimeout()
        }
        bridgeTimeouts[bridge] = timeout
        bridge.postDelayed(timeout, BRIDGE_MAX_MS)
        return true
    }

    /** Short dissolve once the stream has a first ready frame. */
    fun revealStream(bridge: ImageView, durationMs: Long = CROSSFADE_MS) {
        if (bridge.visibility != View.VISIBLE) return
        cancelBridgeTimeout(bridge)
        bridge.animate().cancel()
        val reduce = durationMs <= 0L ||
            android.provider.Settings.Global.getFloat(
                bridge.context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        if (reduce) {
            hideBridge(bridge)
            return
        }
        bridge.animate()
            .alpha(0f)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { hideBridge(bridge) }
            .start()
    }

    fun hideBridge(bridge: ImageView) {
        cancelBridgeTimeout(bridge)
        bridge.visibility = View.GONE
        bridge.alpha = 1f
        runCatching { Glide.with(bridge).clear(bridge) }
        bridge.setImageDrawable(null)
    }

    private fun cancelBridgeTimeout(bridge: ImageView) {
        val pending = bridgeTimeouts.remove(bridge) ?: return
        bridge.removeCallbacks(pending)
    }

    /** Canonical content ids so warm cache + crossfade handoff agree (case-insensitive). */
    fun canonicalizeId(contentId: String): String =
        contentId.trim().lowercase(Locale.US)
}
