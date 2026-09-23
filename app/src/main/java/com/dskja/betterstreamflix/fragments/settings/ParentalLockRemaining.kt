package com.dskja.betterstreamflix.fragments.settings

/**
 * Shared math for parental-control lock countdowns shown in Settings.
 * Extracted from SettingsTvFragment to keep the monolith from growing further.
 */
object ParentalLockRemaining {
    fun minutes(remainingMillis: Long): Int =
        ((remainingMillis + 60_000L - 1L) / 60_000L).toInt().coerceAtLeast(1)
}
