package com.dskja.betterstreamflix.player

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.download.DownloadSeriesFolders
import com.dskja.betterstreamflix.net.HonestEta
import com.dskja.betterstreamflix.net.NetworkThroughputHistory

/**
 * Thin buffer-ETA chrome for Mobile + TV players.
 * Shows “Buffering · ~Xs” from LoadControl deficit × bitrate / network history.
 */
@OptIn(UnstableApi::class)
class BufferEtaController(
    private val label: TextView,
    private val playerProvider: () -> Player?,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var lastShownEta: Long = Long.MIN_VALUE

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            refresh()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING -> start()
            Player.STATE_READY, Player.STATE_ENDED, Player.STATE_IDLE -> stop()
            else -> Unit
        }
    }

    fun onIsLoadingChanged(isLoading: Boolean) {
        val player = playerProvider() ?: return
        if (isLoading && player.playbackState == Player.STATE_BUFFERING) {
            start()
        } else if (!isLoading && player.playbackState != Player.STATE_BUFFERING) {
            stop()
        }
    }

    fun release() {
        stop()
    }

    private fun start() {
        if (ticking) {
            refresh()
            return
        }
        ticking = true
        refresh()
        handler.postDelayed(tick, TICK_MS)
    }

    private fun stop() {
        ticking = false
        handler.removeCallbacks(tick)
        lastShownEta = Long.MIN_VALUE
        label.visibility = View.GONE
    }

    private fun refresh() {
        val player = playerProvider()
        if (player == null || player.playbackState != Player.STATE_BUFFERING) {
            stop()
            return
        }
        val buffered = player.totalBufferedDuration.coerceAtLeast(0L)
        val targetMs = if (player.currentPosition > 1_000L) {
            PlayerBuilderFactory.lastRebufferMs.toLong()
        } else {
            PlayerBuilderFactory.lastPlaybackBufferMs.toLong()
        }
        val deficitMs = (targetMs - buffered).coerceAtLeast(0L)
        if (deficitMs < 250L) {
            // Almost ready — keep spinner, hide noisy ETA.
            label.visibility = View.GONE
            return
        }
        val bitrate = currentMediaBitrateBps(player)
        val history = NetworkThroughputHistory.estimateBytesPerSec()
        val estimate = HonestEta.buffer(
            bufferDeficitMs = deficitMs,
            mediaBitrateBps = bitrate,
            historyBps = history,
            historySampleCount = NetworkThroughputHistory.sampleCount(),
        )
        if (estimate.etaSeconds < 0L) {
            label.visibility = View.GONE
            return
        }
        // Smooth UI: only update when ETA moves by ≥1s or first show.
        if (lastShownEta != Long.MIN_VALUE &&
            kotlin.math.abs(estimate.etaSeconds - lastShownEta) < 1L
        ) {
            label.visibility = View.VISIBLE
            return
        }
        lastShownEta = estimate.etaSeconds
        val etaText = DownloadSeriesFolders.formatEta(label.context, estimate.etaSeconds)
        label.text = label.context.getString(R.string.player_buffer_eta, etaText)
        label.visibility = View.VISIBLE
    }

    private fun currentMediaBitrateBps(player: Player): Int? {
        var bestVideo: Int? = null
        var bestAudio: Int? = null
        for (i in 0 until player.currentTracks.groups.size) {
            val group = player.currentTracks.groups[i]
            if (group.type != C.TRACK_TYPE_VIDEO && group.type != C.TRACK_TYPE_AUDIO) continue
            for (j in 0 until group.length) {
                if (!group.isTrackSelected(j)) continue
                val br = group.getTrackFormat(j).bitrate
                if (br <= 0) continue
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> if (bestVideo == null || br > bestVideo) bestVideo = br
                    C.TRACK_TYPE_AUDIO -> if (bestAudio == null || br > bestAudio) bestAudio = br
                }
            }
        }
        return bestVideo ?: bestAudio
    }

    companion object {
        private const val TICK_MS = 500L
    }
}
