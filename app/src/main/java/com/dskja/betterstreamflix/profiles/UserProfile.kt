package com.dskja.betterstreamflix.profiles

data class UserProfile(
    val id: String,
    val displayName: String,
    val avatarKey: String,
    val accentColorArgb: Int? = null,
    val isKids: Boolean = false,
    val maxAgeRating: Int? = null,
    val pinHash: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val enabledIntegrations: Set<String> = emptySet(),
    val notes: String? = null,
    val lastUsedAtMillis: Long = 0L,
    val atmosphereKey: String? = ProfileAtmosphere.DEFAULT,
    val greetingName: String? = null,
    val autoLockMinutes: Int? = null,
    val pinFailedAttempts: Int = 0,
    val pinLockedUntilMillis: Long = 0L,
) {
    fun safeIntegrations(): Set<String> =
        runCatching { enabledIntegrations }.getOrNull().orEmpty()

    fun safeAtmosphere(): String = ProfileAtmosphere.normalize(atmosphereKey)

    fun publicName(): String =
        greetingName?.trim()?.takeIf { it.isNotEmpty() } ?: displayName

    object Integration {
        const val TRAKT = "trakt"
        const val JELLYFIN = "jellyfin"
        const val PLEX = "plex"
        const val DEBRID = "debrid"
        const val SIMKL = "simkl"
        const val OPENSUBTITLES = "opensubtitles"
        const val TMDB = "tmdb"

        val ALL = setOf(
            TRAKT,
            JELLYFIN,
            PLEX,
            DEBRID,
            SIMKL,
            OPENSUBTITLES,
            TMDB,
        )
    }
}
