package com.dskja.betterstreamflix.logo

/** Where a title logo URL came from. */
enum class LogoSource {
    UNKNOWN,
    PROVIDER,
    TMDB,
    USER,
}

/**
 * Request bundle for resolving / binding a title logo (replaces long parameter lists).
 */
data class LogoRequest(
    val title: String,
    val year: Int? = null,
    val isTv: Boolean = false,
    val tmdbId: String? = null,
    val imdbId: String? = null,
    val language: String? = null,
    val existingLogo: String? = null,
    val existingSource: LogoSource = LogoSource.UNKNOWN,
)
