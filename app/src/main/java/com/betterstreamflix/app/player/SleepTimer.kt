package com.betterstreamflix.app.player

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Session-scoped sleep timer shared by the player settings screens.
 *
 * - [Mode.In] pauses playback when the wall-clock deadline is reached.
 * - [Mode.EndOfVideo] suppresses the next-episode autoplay countdown once;
 *   the end card stays up instead of advancing.
 *
 * The mode intentionally survives episode changes inside one player session
 * and is cleared when the player screen detaches ([detach]).
 */
object SleepTimer {

    sealed interface Mode {
        data object Off : Mode
        data object EndOfVideo : Mode
        data class In(val minutes: Int, val fireAtMs: Long) : Mode
    }

    var mode: Mode = Mode.Off
        private set

    private var scope: CoroutineScope? = null
    private var tickJob: Job? = null
    private var onFire: (() -> Unit)? = null

    /** (Re)binds the fire callback + scope — call when the player view is created. */
    fun attach(scope: CoroutineScope, onFire: () -> Unit) {
        this.scope = scope
        this.onFire = onFire
        rearm()
    }

    /** Player screen is gone — forget the callback and disarm. */
    fun detach() {
        scope = null
        onFire = null
        tickJob?.cancel()
        tickJob = null
        mode = Mode.Off
    }

    fun setMinutes(minutes: Int) {
        mode = Mode.In(minutes, SystemClock.elapsedRealtime() + minutes * 60_000L)
        rearm()
    }

    fun setEndOfVideo() {
        mode = Mode.EndOfVideo
        tickJob?.cancel()
        tickJob = null
    }

    fun cancel() {
        mode = Mode.Off
        tickJob?.cancel()
        tickJob = null
    }

    fun remainingMs(): Long? = (mode as? Mode.In)
        ?.let { (it.fireAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L) }

    /** True while an armed EndOfVideo timer should keep the end card from advancing. */
    fun shouldBlockAutoplay(): Boolean = mode is Mode.EndOfVideo

    /** One-shot: after the end card was held once, the timer is spent. */
    fun onAutoplayBlocked() {
        if (mode is Mode.EndOfVideo) cancel()
    }

    private fun rearm() {
        tickJob?.cancel()
        tickJob = null
        val deadline = (mode as? Mode.In)?.fireAtMs ?: return
        val callback = onFire ?: return
        val tickScope = scope ?: return
        tickJob = tickScope.launch {
            while (isActive) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0L) break
                delay(left.coerceAtMost(1_000L))
            }
            if (mode is Mode.In) {
                mode = Mode.Off
                callback.invoke()
            }
        }
    }
}
