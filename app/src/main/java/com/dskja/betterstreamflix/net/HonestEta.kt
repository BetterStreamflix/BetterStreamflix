package com.dskja.betterstreamflix.net

import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Pure Honest ETA math — download remaining / buffer fill from real throughput,
 * never from fantasy % of Content-Length alone.
 */
object HonestEta {

    private const val MAX_ETA_SECONDS = 36 * 3600L // 36h display cap
    private const val MIN_BLEND_INSTANT_SAMPLES = 2

    data class DownloadEstimate(
        /** Display speed (history-preferred, blended). 0 = unknown/stalled. */
        val speedBytesPerSec: Long,
        /** Seconds remaining, or -1 when unknown. */
        val etaSeconds: Long,
        val confident: Boolean,
    )

    data class BufferEstimate(
        val etaSeconds: Long,
        val confident: Boolean,
    )

    /**
     * @param remainingBytes bytes still to download (0 if unknown length → no ETA)
     * @param instantaneousBps last progress-sample speed (may be noisy)
     * @param historyBps [NetworkThroughputHistory.estimateBytesPerSec] or null when cold
     * @param historySampleCount total samples seen (confidence gate)
     */
    fun download(
        remainingBytes: Long,
        instantaneousBps: Long,
        historyBps: Long?,
        historySampleCount: Int,
        minSamplesForConfidence: Int = 3,
    ): DownloadEstimate {
        if (remainingBytes <= 0L) {
            return DownloadEstimate(speedBytesPerSec = 0L, etaSeconds = -1L, confident = false)
        }
        val instant = instantaneousBps.coerceAtLeast(0L)
        val history = historyBps?.coerceAtLeast(0L)
        val speed = blendSpeed(instant, history, historySampleCount)
        if (speed <= 0L) {
            return DownloadEstimate(0L, -1L, confident = false)
        }
        val eta = ceil(remainingBytes.toDouble() / speed.toDouble())
            .roundToLong()
            .coerceIn(1L, MAX_ETA_SECONDS)
        val confident = historySampleCount >= minSamplesForConfidence &&
            (history != null && history > 0L || instant > 0L)
        return DownloadEstimate(speed, eta, confident)
    }

    /**
     * Buffer fill ETA from media-time deficit and observed throughput vs bitrate.
     *
     * @param bufferDeficitMs how many more ms of media we still need buffered
     * @param mediaBitrateBps container bitrate (bits/sec); null → fall back to history-only heuristic
     * @param historyBps measured network bytes/sec
     */
    fun buffer(
        bufferDeficitMs: Long,
        mediaBitrateBps: Int?,
        historyBps: Long?,
        historySampleCount: Int,
        minSamplesForConfidence: Int = 3,
    ): BufferEstimate {
        if (bufferDeficitMs <= 0L) {
            return BufferEstimate(etaSeconds = 0L, confident = true)
        }
        val net = historyBps?.coerceAtLeast(0L) ?: 0L
        if (net <= 0L || historySampleCount < minSamplesForConfidence) {
            return BufferEstimate(etaSeconds = -1L, confident = false)
        }
        val bitrate = mediaBitrateBps?.takeIf { it > 0 }
        val eta = if (bitrate != null) {
            val bytesNeeded = (bufferDeficitMs.toDouble() * (bitrate / 8.0)) / 1000.0
            ceil(bytesNeeded / net.toDouble()).roundToLong()
        } else {
            // No bitrate yet: assume ~2.5 Mbps mid-quality so we still show something honest-ish.
            val assumedBitrate = 2_500_000
            val bytesNeeded = (bufferDeficitMs.toDouble() * (assumedBitrate / 8.0)) / 1000.0
            ceil(bytesNeeded / net.toDouble()).roundToLong()
        }.coerceIn(1L, 600L)
        return BufferEstimate(etaSeconds = eta, confident = bitrate != null)
    }

    /** Prefer history; blend a little instantaneous once we have a few samples. */
    fun blendSpeed(
        instantaneousBps: Long,
        historyBps: Long?,
        historySampleCount: Int,
    ): Long {
        val history = historyBps?.takeIf { it > 0L }
        val instant = instantaneousBps.takeIf { it > 0L }
        return when {
            history != null && instant != null &&
                historySampleCount >= MIN_BLEND_INSTANT_SAMPLES ->
                ((history * 0.72) + (instant * 0.28)).roundToLong()
            history != null -> history
            instant != null -> instant
            else -> 0L
        }
    }
}
