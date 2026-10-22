package com.betterstreamflix.app.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
// ByteBuffer / ByteOrder used by partial-frame Night test.

@OptIn(UnstableApi::class)
class NightModeAudioProcessorTest {

    @Test
    fun passThroughWhenDisabled() {
        NightModeAudio.enabled = false
        val processor = NightModeAudioProcessor()
        processor.configure(
            AudioProcessor.AudioFormat(/* sampleRate= */ 48_000, /* channelCount= */ 2, C.ENCODING_PCM_16BIT),
        )
        assertTrue(processor.isActive)

        val input = shortBuffer(shortArrayOf(1000, -2000, 3000, -4000))
        processor.queueInput(input)
        val output = processor.output
        val samples = readShorts(output)
        assertEquals(listOf<Short>(1000, -2000, 3000, -4000), samples)
        processor.reset()
    }

    @Test
    fun processesWhenEnabledWithoutClippingSilence() {
        NightModeAudio.enabled = true
        val processor = NightModeAudioProcessor()
        processor.configure(
            AudioProcessor.AudioFormat(/* sampleRate= */ 48_000, /* channelCount= */ 1, C.ENCODING_PCM_16BIT),
        )
        val input = shortBuffer(ShortArray(64) { 0 })
        processor.queueInput(input)
        val samples = readShorts(processor.output)
        assertEquals(64, samples.size)
        assertTrue(samples.all { it == 0.toShort() })
        NightModeAudio.enabled = false
        processor.reset()
    }

    @Test
    fun nightKeepsPartialStereoFrameAcrossQueues() {
        NightModeAudio.enabled = true
        val processor = NightModeAudioProcessor()
        processor.configure(
            AudioProcessor.AudioFormat(/* sampleRate= */ 48_000, /* channelCount= */ 2, C.ENCODING_PCM_16BIT),
        )
        // One sample (2 bytes) — incomplete stereo frame; must not emit a desynced channel.
        val partial = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder())
        partial.putShort(1000)
        partial.flip()
        processor.queueInput(partial)
        assertEquals(0, processor.output.remaining())
        // Second channel completes the frame.
        val rest = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder())
        rest.putShort(-2000)
        rest.flip()
        processor.queueInput(rest)
        val samples = readShorts(processor.output)
        assertEquals(2, samples.size)
        NightModeAudio.enabled = false
        processor.reset()
    }

    private fun shortBuffer(values: ShortArray): ByteBuffer {
        val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.nativeOrder())
        values.forEach { buffer.putShort(it) }
        buffer.flip()
        return buffer
    }

    private fun readShorts(buffer: ByteBuffer): List<Short> {
        val ordered = buffer.order(ByteOrder.nativeOrder())
        val out = ArrayList<Short>(ordered.remaining() / 2)
        while (ordered.remaining() >= 2) {
            out += ordered.short
        }
        return out
    }
}
