package com.dskja.betterstreamflix.ui

import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.ArtworkUrls
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
     */
    fun revealDurationMs(cinemaRoomEnabled: Boolean): Long =
        CinemaRoomPolicy.trailerCrossfadeMs(cinemaRoomEnabled)

    data class Handoff(
        val contentId: String,
        val coverUrl: String?,
        val serverHot: Boolean,
        val armedAtMs: Long,
    )

    @Volatile
    private var handoff: Handoff? = null

    /** Overridable for JVM unit tests. */
    @Volatile
    internal var elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() }

    fun arm(target: TrailerPlayPipeline.Target?) {
        if (target == null) return
        arm(target.contentId, target.videoType)
    }

    fun arm(contentId: String, videoType: Video.Type) {
        val id = contentId.trim()
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
        if (!current.contentId.equals(contentId.trim(), ignoreCase = true)) return null
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
     * on the main thread. Always runs [block] — never blocks Stream Now forever.
     */
    fun runWhenHot(host: View, contentId: String, block: () -> Unit) {
        val id = contentId.trim()
        val owner = host.findViewTreeLifecycleOwner()
        if (owner == null) {
            block()
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
            if (!host.isAttachedToWindow) {
                // Host may be gone after trailer dismiss — still navigate.
                block()
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
        bridge.postDelayed({
            if (bridge.visibility == View.VISIBLE) onTimeout()
        }, BRIDGE_MAX_MS)
        return true
    }

    /** Short dissolve once the stream has a first ready frame. */
    fun revealStream(bridge: ImageView, durationMs: Long = CROSSFADE_MS) {
        if (bridge.visibility != View.VISIBLE) return
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

    private fun hideBridge(bridge: ImageView) {
        bridge.visibility = View.GONE
        bridge.alpha = 1f
        runCatching { Glide.with(bridge).clear(bridge) }
        bridge.setImageDrawable(null)
    }
}
