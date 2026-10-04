package com.dskja.betterstreamflix.player

/**
 * Extension audio renderers sit beside MediaCodec. The track selector then
 * enables one track per audio renderer, so original and dubbed audio play together.
 * Keep the first audio renderer and disable the rest.
 */
object AudioRendererPolicy {
    fun secondaryRendererIndices(rendererTypes: IntArray, audioType: Int): List<Int> {
        var kept = false
        val disabled = ArrayList<Int>(2)
        for (index in rendererTypes.indices) {
            if (rendererTypes[index] != audioType) continue
            if (!kept) {
                kept = true
            } else {
                disabled += index
            }
        }
        return disabled
    }
}
