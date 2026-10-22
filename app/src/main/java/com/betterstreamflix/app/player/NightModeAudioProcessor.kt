package com.betterstreamflix.app.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * High-end Night listening preset for PCM 16-bit:
 * - peaking EQ around dialog band (~2.5 kHz)
 * - soft-knee compressor to tame loud action peaks
 * - gentle makeup + soft limiter
 *
 * Attached only while Night is enabled ([SubtitleOffsetRenderersFactory] — PT-134).
 * Soft-rebuild on toggle keeps a true empty-chain bypass when Night is off.
 * If [NightModeAudio.enabled] flips mid-buffer before rebuild, samples copy through.
 */
@UnstableApi
class NightModeAudioProcessor : BaseAudioProcessor() {

    private var channelCount = 0
    private var sampleRate = 0
    private var biquad: Biquad? = null
    private var envelopes = FloatArray(0)
    private var attackCoeff = 0f
    private var releaseCoeff = 0f

    /** Leftover bytes that do not form a whole PCM frame yet (Night path). */
    private var pendingInput: ByteArray? = null
    private var pendingSize = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        biquad = Biquad.peaking(
            sampleRate = sampleRate.toFloat(),
            freqHz = DIALOG_FREQ_HZ,
            q = DIALOG_Q,
            gainDb = DIALOG_GAIN_DB,
            channels = channelCount,
        )
        envelopes = FloatArray(channelCount)
        attackCoeff = expCoeff(ATTACK_MS, sampleRate)
        releaseCoeff = expCoeff(RELEASE_MS, sampleRate)
        pendingSize = 0
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        // Pass-through when Night is off — still need an output buffer while active
        // in the sink chain for instant toggle (true Media3 bypass would drop mid-toggle).
        if (!NightModeAudio.enabled || channelCount <= 0 || biquad == null) {
            pendingSize = 0
            val output = replaceOutputBuffer(remaining)
            output.put(inputBuffer)
            output.flip()
            return
        }
        val channels = channelCount
        val bytesPerFrame = channels * 2
        if (bytesPerFrame <= 0) {
            val output = replaceOutputBuffer(remaining)
            output.put(inputBuffer)
            output.flip()
            return
        }

        // Concatenate leftover + new input, process only whole frames, keep remainder.
        val totalIn = pendingSize + remaining
        val combined = ByteArray(totalIn)
        if (pendingSize > 0) {
            System.arraycopy(pendingInput!!, 0, combined, 0, pendingSize)
        }
        inputBuffer.get(combined, pendingSize, remaining)
        pendingSize = 0

        val processable = totalIn - (totalIn % bytesPerFrame)
        val leftover = totalIn - processable
        val output = replaceOutputBuffer(processable)
        val eq = biquad!!
        val order = inputBuffer.order()
        var offset = 0
        while (offset + bytesPerFrame <= processable) {
            val frame = ByteBuffer.wrap(combined, offset, bytesPerFrame).order(order)
            for (ch in 0 until channels) {
                val sample = frame.short
                var x = sample / 32768f
                x = eq.process(ch, x)
                val abs = kotlin.math.abs(x)
                val env = envelopes[ch]
                envelopes[ch] = if (abs > env) {
                    attackCoeff * env + (1f - attackCoeff) * abs
                } else {
                    releaseCoeff * env + (1f - releaseCoeff) * abs
                }
                val gain = compressorGain(envelopes[ch])
                x = softLimit(x * gain * MAKEUP_GAIN)
                val out = (x * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.putShort(out.toShort())
            }
            offset += bytesPerFrame
        }
        if (leftover > 0) {
            val buf = pendingInput?.takeIf { it.size >= leftover }
                ?: ByteArray(leftover.coerceAtLeast(bytesPerFrame))
            System.arraycopy(combined, processable, buf, 0, leftover)
            pendingInput = buf
            pendingSize = leftover
        }
        output.flip()
    }

    override fun onFlush() {
        envelopes.fill(0f)
        biquad?.reset()
        pendingSize = 0
    }

    override fun onReset() {
        envelopes = FloatArray(0)
        biquad = null
        channelCount = 0
        sampleRate = 0
        pendingSize = 0
        pendingInput = null
    }

    private fun compressorGain(envelope: Float): Float {
        if (envelope <= THRESHOLD_LINEAR) return 1f
        val overDb = 20f * ln10(envelope / THRESHOLD_LINEAR)
        val soft = min(overDb, KNEE_DB)
        val hard = max(0f, overDb - KNEE_DB)
        val softReduction = soft * soft / (2f * KNEE_DB) * (1f - 1f / RATIO)
        val hardReduction = hard * (1f - 1f / RATIO)
        val reductionDb = softReduction + hardReduction
        return 10f.pow(-reductionDb / 20f)
    }

    private fun softLimit(x: Float): Float {
        val a = kotlin.math.abs(x)
        if (a <= LIMIT_START) return x
        val sign = if (x >= 0f) 1f else -1f
        val t = (a - LIMIT_START) / (1f - LIMIT_START)
        val shaped = LIMIT_START + (1f - LIMIT_START) * (t / (1f + t))
        return sign * min(shaped, 0.98f)
    }

    private class Biquad(
        private val b0: Float,
        private val b1: Float,
        private val b2: Float,
        private val a1: Float,
        private val a2: Float,
        channels: Int,
    ) {
        private val x1 = FloatArray(channels)
        private val x2 = FloatArray(channels)
        private val y1 = FloatArray(channels)
        private val y2 = FloatArray(channels)

        fun process(channel: Int, x: Float): Float {
            val y = b0 * x + b1 * x1[channel] + b2 * x2[channel] - a1 * y1[channel] - a2 * y2[channel]
            x2[channel] = x1[channel]
            x1[channel] = x
            y2[channel] = y1[channel]
            y1[channel] = y
            return y
        }

        fun reset() {
            x1.fill(0f)
            x2.fill(0f)
            y1.fill(0f)
            y2.fill(0f)
        }

        companion object {
            fun peaking(
                sampleRate: Float,
                freqHz: Float,
                q: Float,
                gainDb: Float,
                channels: Int,
            ): Biquad {
                val a = 10f.pow(gainDb / 40f)
                val w0 = (2.0 * PI * freqHz / sampleRate).toFloat()
                val alpha = sin(w0) / (2f * q)
                val cosW = cos(w0)
                val b0 = 1f + alpha * a
                val b1 = -2f * cosW
                val b2 = 1f - alpha * a
                val a0 = 1f + alpha / a
                val a1 = -2f * cosW
                val a2 = 1f - alpha / a
                return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0, channels)
            }
        }
    }

    companion object {
        private const val DIALOG_FREQ_HZ = 2500f
        private const val DIALOG_Q = 1.1f
        private const val DIALOG_GAIN_DB = 4.5f
        private const val THRESHOLD_DB = -18f
        private val THRESHOLD_LINEAR = 10f.pow(THRESHOLD_DB / 20f)
        private const val RATIO = 3.2f
        private const val KNEE_DB = 6f
        private const val ATTACK_MS = 5f
        private const val RELEASE_MS = 120f
        private val MAKEUP_GAIN = 10f.pow(2.5f / 20f)
        private const val LIMIT_START = 0.88f

        private fun expCoeff(ms: Float, sampleRate: Int): Float =
            exp(-1f / (sampleRate * ms / 1000f)).toFloat()

        private fun ln10(v: Float): Float = kotlin.math.ln(max(v, 1e-8f)) / kotlin.math.ln(10f)
    }
}
