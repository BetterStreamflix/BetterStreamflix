package com.dskja.betterstreamflix.player

import android.media.audiofx.Visualizer
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * Best-effort amplitude probe for the opening minutes. Feeds [SmartSkipIntro] silence gaps.
 * Never required for Skip to work — chapter marks and the episode heuristic cover the rest.
 */
class SkipIntroSilenceProbe private constructor(
    private val visualizer: Visualizer,
) {
    private val lock = Any()
    private var latestPositionMs: Long = 0L
    private var quietSinceMs: Long? = null
    private var hadLoudAudio = false
    private val gaps = ArrayList<SmartSkipIntro.SilenceGap>(4)
    private val released = AtomicBoolean(false)

    fun onPlaybackPosition(positionMs: Long) {
        if (positionMs < 0L) return
        synchronized(lock) {
            latestPositionMs = positionMs
            if (positionMs > SmartSkipIntro.ANALYSIS_WINDOW_MS && quietSinceMs == null) {
                runCatching { visualizer.enabled = false }
            }
        }
    }

    fun gaps(): List<SmartSkipIntro.SilenceGap> = synchronized(lock) { gaps.toList() }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching {
            visualizer.enabled = false
            visualizer.release()
        }
    }

    private fun onRms(rms: Double) {
        if (released.get()) return
        synchronized(lock) {
            val pos = latestPositionMs
            if (pos <= 0L || pos > SmartSkipIntro.ANALYSIS_WINDOW_MS) return
            val quiet = rms < QUIET_RMS
            if (!quiet) {
                hadLoudAudio = true
                val since = quietSinceMs
                if (since != null) {
                    val end = pos
                    val start = since
                    quietSinceMs = null
                    val len = end - start
                    if (len in 1_200L..5_000L && gaps.size < 6) {
                        val last = gaps.lastOrNull()
                        if (last != null && start <= last.endMs + 800L) {
                            gaps[gaps.lastIndex] = SmartSkipIntro.SilenceGap(last.startMs, end)
                        } else {
                            gaps += SmartSkipIntro.SilenceGap(start, end)
                        }
                    }
                }
            } else if (hadLoudAudio && quietSinceMs == null) {
                quietSinceMs = pos
            }
        }
    }

    companion object {
        private const val TAG = "SkipIntroSilenceProbe"
        private const val QUIET_RMS = 6.0

        fun tryCreate(audioSessionId: Int): SkipIntroSilenceProbe? {
            if (audioSessionId == 0) return null
            return try {
                val holder = AtomicReference<SkipIntroSilenceProbe?>(null)
                val visualizer = Visualizer(audioSessionId)
                visualizer.captureSize = Visualizer.getCaptureSizeRange()[0]
                visualizer.setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int,
                        ) {
                            val probe = holder.get() ?: return
                            if (waveform == null || waveform.isEmpty()) return
                            var sum = 0.0
                            for (b in waveform) {
                                val centered = (b.toInt() and 0xFF) - 128
                                sum += (centered * centered).toDouble()
                            }
                            probe.onRms(sqrt(sum / waveform.size))
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int,
                        ) = Unit
                    },
                    Visualizer.getMaxCaptureRate() / 4,
                    true,
                    false,
                )
                val probe = SkipIntroSilenceProbe(visualizer)
                holder.set(probe)
                visualizer.enabled = true
                probe
            } catch (t: Throwable) {
                Log.d(TAG, "Visualizer unavailable: ${t.message}")
                null
            }
        }
    }
}
