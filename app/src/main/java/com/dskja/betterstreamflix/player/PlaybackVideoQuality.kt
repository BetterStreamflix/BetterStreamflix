package com.dskja.betterstreamflix.player

import androidx.media3.common.TrackSelectionParameters

/**
 * Persistent stream quality cap (Settings → Playback).
 * Applied via ExoPlayer [TrackSelectionParameters] max video size so adaptive
 * selection stays at or below the chosen height and saves mobile data.
 */
enum class PlaybackVideoQuality {
    AUTO,
    P2160,
    P1440,
    P1080,
    P720,
    P480,
    P360,
    ;

    /** Max video height in pixels, or null when uncapped (Auto). */
    val maxHeight: Int?
        get() = when (this) {
            AUTO -> null
            P2160 -> 2160
            P1440 -> 1440
            P1080 -> 1080
            P720 -> 720
            P480 -> 480
            P360 -> 360
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

    /**
     * Data saver auto-caps adaptive Auto (and any higher rung) to 720p.
     * The stored preference is unchanged; callers use the capped value at play time.
     */
    fun cappedForDataSaver(enabled: Boolean): PlaybackVideoQuality {
        if (!enabled) return this
        val max = maxHeight ?: return P720
        return if (max > 720) P720 else this
    }

    companion object {
        fun fromKey(key: String?): PlaybackVideoQuality {
            if (key.isNullOrBlank()) return AUTO
            entries.find { it.name.equals(key, ignoreCase = true) }?.let { return it }
            return when (key.trim().lowercase().removeSuffix("p")) {
                "2160", "4k", "uhd" -> P2160
                "1440", "2k", "qhd" -> P1440
                "1080", "fhd" -> P1080
                "720", "hd" -> P720
                "480", "sd" -> P480
                "360" -> P360
                "auto" -> AUTO
                else -> AUTO
            }
        }
    }
}
