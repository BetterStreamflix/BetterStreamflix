package com.dskja.betterstreamflix.player

import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Runtime flag for the Night audio preset (dialog boost + quieter action peaks).
 * Toggling updates playback without rebuilding ExoPlayer — the PCM processor
 * reads this on every buffer.
 */
object NightModeAudio {
    @Volatile
    var enabled: Boolean = false

    fun syncFromPreferences() {
        enabled = UserPreferences.nightAudioMode
    }

    /** Persist preference and update the live DSP flag (instant toggle). */
    fun setPersisted(value: Boolean) {
        UserPreferences.nightAudioMode = value
        enabled = value
    }
}
