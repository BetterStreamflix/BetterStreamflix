package com.betterstreamflix.app.profiles

data class UserProfile(
    val id: String,
    val displayName: String,
    val avatarKey: String,
    val accentColorArgb: Int? = null,
    val isKids: Boolean = false,
    val maxAgeRating: Int? = null,
    val pinHash: String? = null,
    /** Temporary guest shell — Continue/History wiped when [expiresAtMillis] elapses. */
    val isGuest: Boolean = false,
    /** Absolute wall-clock expiry; null when not a guest. */
    val expiresAtMillis: Long? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val enabledIntegrations: Set<String> = emptySet(),
    val notes: String? = null,
) {
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
