package com.dskja.betterstreamflix.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRendererPolicyTest {
    @Test
    fun disablesEveryAudioRendererAfterTheFirst() {
        val audio = 1
        val video = 2
        val indices = AudioRendererPolicy.secondaryRendererIndices(
            intArrayOf(video, audio, audio, video),
            audio,
        )
        assertEquals(listOf(2), indices)
    }

    @Test
    fun singleAudioRendererStaysEnabled() {
        val indices = AudioRendererPolicy.secondaryRendererIndices(intArrayOf(2, 1), 1)
        assertEquals(emptyList<Int>(), indices)
    }
}
