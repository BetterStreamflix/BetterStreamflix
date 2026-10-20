package com.dskja.betterstreamflix.profiles

/**
 * Pure rules for the temporary Guest profile (Batch 7 §1).
 * Wall-clock TTL from [UserProfile.createdAtMillis]; Continue/History
 * are wiped when the guest expires (and the temp profile is removed).
 *
 * Guest has **no exit PIN** (GP-005): leaving Guest does not require a challenge;
 * parental exit PIN only applies when *leaving* a restricted (kids/ceiling) profile
 * *into* Guest.
 */
object GuestProfilePolicy {

    const val TTL_MS: Long = 2L * 60L * 60L * 1000L
    const val AVATAR_KEY: String = "robot"
    const val DEFAULT_DISPLAY_NAME: String = "Guest"

    fun expiresAtMillis(createdAtMillis: Long): Long = createdAtMillis + TTL_MS

    fun isGuest(profile: UserProfile): Boolean = profile.isGuest

    fun isExpired(profile: UserProfile, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!profile.isGuest) return false
        val expires = profile.expiresAtMillis ?: expiresAtMillis(profile.createdAtMillis)
        return nowMillis >= expires
    }

    fun remainingMillis(profile: UserProfile, nowMillis: Long = System.currentTimeMillis()): Long {
        if (!profile.isGuest) return 0L
        val expires = profile.expiresAtMillis ?: expiresAtMillis(profile.createdAtMillis)
        return (expires - nowMillis).coerceAtLeast(0L)
    }

    /** Compact remaining label: `1h 42m`, `12m`, or `ending`. */
    fun formatRemaining(
        remainingMs: Long,
        hoursSuffix: String = "h",
        minutesSuffix: String = "m",
        endingLabel: String = "ending",
    ): String {
        if (remainingMs <= 0L) return endingLabel
        val totalMinutes = (remainingMs + 59_999L) / 60_000L // round up
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> "${hours}${hoursSuffix} ${minutes}${minutesSuffix}"
            hours > 0L -> "${hours}${hoursSuffix}"
            else -> "${minutes.coerceAtLeast(1L)}${minutesSuffix}"
        }
    }
}
