package com.dskja.betterstreamflix.player

import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.ui.TrailerPlaybackController
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Clean Exit — Back from the player must:
 * 1. Kill audio immediately (no Exo / MediaSession / trailer zombie).
 * 2. Restore exact Home focus when Home is the return surface (TV DPAD).
 *
 * Soft-leave still defers ExoPlayer.release (OEM surface race); silence is
 * guaranteed via mute + pause + MediaSession release + view detach.
 */
object PlayerCleanExit {

    const val FEATURED_WATCH_KEY = "featured_watch"

    data class HomeFocusToken(
        /** Stable content id, or [FEATURED_WATCH_KEY]. */
        val itemKey: String,
        /** Optional shelf / category hint for debugging. */
        val rowHint: String? = null,
    )

    private val pendingHomeRestore = AtomicBoolean(false)
    private val homeFocusToken = AtomicReference<HomeFocusToken?>(null)
    private val leftPlayer = AtomicBoolean(false)

    /** Soft-stop steps for Clean Exit (mute before pause). */
    val silenceSteps: List<String> = listOf(
        "mute_volume",
        "pause_playback",
        "detach_player_view",
        "release_media_session",
        "remove_player_listeners",
        "silence_trailers",
    )

    /**
     * Capture the Home tile / Featured CTA that launched (or last held) focus
     * so Back can land on the same control.
     */
    fun rememberHomeFocus(itemKey: String?, rowHint: String? = null) {
        val key = itemKey?.takeIf { it.isNotBlank() } ?: return
        homeFocusToken.set(HomeFocusToken(itemKey = key, rowHint = rowHint))
        pendingHomeRestore.set(true)
    }

    /** Tag a Home shelf tile so [restoreHomeFocus] can find it later. */
    fun tagHomeFocusView(view: View?, itemKey: String?) {
        if (view == null) return
        val key = itemKey?.takeIf { it.isNotBlank() } ?: return
        view.setTag(R.id.home_clean_exit_focus_key, key)
    }

    fun tagFeaturedWatch(view: View?) {
        tagHomeFocusView(view, FEATURED_WATCH_KEY)
    }

    /** Call when Back / nav leaves the player destination. */
    fun noteLeavingPlayer() {
        leftPlayer.set(true)
        pendingHomeRestore.set(homeFocusToken.get() != null)
    }

    fun peekHomeFocusToken(): HomeFocusToken? = homeFocusToken.get()

    fun shouldRestoreHomeFocus(): Boolean =
        pendingHomeRestore.get() && homeFocusToken.get() != null

    /**
     * Consume the pending Home restore. Returns the token once, then clears.
     */
    fun consumeHomeFocusRestore(): HomeFocusToken? {
        if (!pendingHomeRestore.compareAndSet(true, false)) return null
        leftPlayer.set(false)
        return homeFocusToken.getAndSet(null)
    }

    /** True for one frame after leaving player (any destination). */
    fun consumeLeftPlayer(): Boolean = leftPlayer.getAndSet(false)

    /**
     * Guaranteed silence for a live ExoPlayer. Safe on any thread; never throws.
     * Volume is muted first so a failed pause cannot leave audible playback.
     */
    fun silenceExo(player: Player?) {
        if (player == null) return
        runCatching {
            player.volume = 0f
            player.playWhenReady = false
            player.pause()
        }
    }

    /** Kill player audio + every in-app trailer session. */
    fun silenceEverything(player: Player?) {
        silenceExo(player)
        runCatching { TrailerPlaybackController.silenceAllActive() }
        runCatching { PlaybackLifecycleGuard.stopActivePlayback() }
    }

    /**
     * Walk [root] for a view tagged with [token.itemKey] and request focus.
     * Returns true when focus landed on that exact control.
     */
    fun restoreHomeFocus(root: View?, token: HomeFocusToken): Boolean {
        if (root == null || !root.isAttachedToWindow) return false
        val match = findTagged(root, token.itemKey) ?: return false
        if (!match.isShown) return false
        match.isFocusable = true
        match.isFocusableInTouchMode = match.isFocusableInTouchMode
        return match.requestFocus()
    }

    private fun findTagged(root: View, key: String): View? {
        val tagged = root.getTag(R.id.home_clean_exit_focus_key) as? String
        if (tagged == key) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            val found = findTagged(root.getChildAt(i), key)
            if (found != null) return found
        }
        return null
    }
}
