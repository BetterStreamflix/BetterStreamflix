package com.betterstreamflix.app.player

import com.betterstreamflix.app.utils.UserPreferences

/**
 * Runtime flag for the Night audio preset (dialog boost + quieter action peaks).
 *
 * Preference changes soft-rebuild Exo so the Night [NightModeAudioProcessor] is
 * present in the audio sink only while enabled (true bypass when off — PT-134).
 * The live flag is also read per-buffer for mid-rebuild safety.
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
