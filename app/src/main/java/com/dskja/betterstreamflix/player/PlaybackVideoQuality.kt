package com.dskja.betterstreamflix.player

import androidx.media3.common.TrackSelectionParameters

/**
 * Persistent stream quality cap (Settings → Playback).
 * Applied via ExoPlayer [TrackSelectionParameters] max video size so adaptive
 * selection stays at or below the chosen height and saves mobile data.
 */
enum class PlaybackVideoQuality {
    AUTO,
    P720,
    P1080,
    ;

    /** Max video height in pixels, or null when uncapped (Auto). */
    val maxHeight: Int?
        get() = when (this) {
            AUTO -> null
            P720 -> 720
            P1080 -> 1080
        }

    /** True when [height] is allowed under this preference. */
    fun allowsHeight(height: Int): Boolean {
        val max = maxHeight ?: return true
        return height <= max
    }

    fun applyMaxVideoSize(builder: TrackSelectionParameters.Builder) {
        val height = maxHeight
        if (height == null) {
            builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        } else {
            // Cap by height only so unusual aspect ratios still match the rung.
            builder.setMaxVideoSize(Int.MAX_VALUE, height)
        }
    }

    companion object {
        fun fromKey(key: String?): PlaybackVideoQuality {
            if (key.isNullOrBlank()) return AUTO
            entries.find { it.name.equals(key, ignoreCase = true) }?.let { return it }
            return when (key.trim().lowercase().removeSuffix("p")) {
                "720" -> P720
                "1080" -> P1080
                "auto" -> AUTO
                else -> AUTO
            }
        }
    }
}
