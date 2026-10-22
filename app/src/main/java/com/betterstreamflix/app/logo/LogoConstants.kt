package com.betterstreamflix.app.logo

/**
 * Central constants for the TMDb title-logo pipeline.
 *
 * SCROLL / layout timing lives in [com.betterstreamflix.app.ui.DetailHeaderController].
 */
object LogoConstants {
    /** Cache schema; bump to wipe disk logo cache on upgrade. */
    const val DISK_CACHE_VERSION = 2

    const val MIN_SEARCH_VOTE_COUNT = 50
    const val MIN_SEARCH_POPULARITY = 20f
    const val MIN_TITLE_MATCH_LENGTH = 4

    /** Default miss soft-TTL (overridable via preferences). */
    const val DEFAULT_MISS_TTL_MS = 6L * 60L * 60L * 1000L

    /** Default hit soft-TTL (overridable via preferences). */
    const val DEFAULT_HIT_TTL_MS = 24L * 60L * 60L * 1000L

    /** Soft blacklist after a trusted Glide decode failure. */
    const val FAIL_BLACKLIST_TTL_MS = 30L * 60L * 1000L

    /**
     * Hard cap for persisted disk rows, pruned during init. Must stay at the upper
     * bound of the user-configurable TTL prefs (UserPreferences coerces to 336h),
     * because init runs before those TTLs are applied.
     */
    const val MAX_DISK_ENTRY_AGE_MS = 336L * 60L * 60L * 1000L

    const val MIN_PREFERRED_WIDTH = 200

    /** Wide hero slots (Featured / detail hero) want bigger wordmarks. */
    const val HERO_MIN_PREFERRED_WIDTH = 400

    const val MIN_ASPECT_FOR_WORDMARK = 1.2f

    /** Memory LRU capacity (entries) — scaled by device RAM class at init. */
    const val MEMORY_CACHE_MAX_ENTRIES = 256
    const val MEMORY_CACHE_MAX_ENTRIES_LOW = 128
    const val MEMORY_CACHE_MAX_ENTRIES_HIGH = 512

    /** Session network-fetch budget — beyond it resolves serve cache/miss only. */
    const val SESSION_FETCH_BUDGET = 400

    /** Cooldown after an HTTP 429 before logo fetches resume. */
    const val RATE_LIMIT_COOLDOWN_MS = 60_000L

    /** Channel-logo persisted hit TTL (channels rarely rebrand). */
    const val CHANNEL_LOGO_HIT_TTL_MS = 30L * 24L * 60L * 60L * 1000L

    /** Network retries for transient logo detail failures. */
    const val NETWORK_RETRY_COUNT = 1
    const val NETWORK_RETRY_BASE_DELAY_MS = 200L

    /** Hard cap for a single title-logo resolve (id lookup + images). */
    const val RESOLVE_TIMEOUT_MS = 8_000L

    /** Prefix length ratio for whole-word prefix matches (shorter*2 >= longer ≈ 50%). */
    const val PREFIX_LENGTH_RATIO_NUM = 2
    const val PREFIX_LENGTH_RATIO_DEN = 1

    const val GLIDE_SIZE_ORIGINAL = "original"
    const val GLIDE_SIZE_W1280 = "w1280"
    const val GLIDE_SIZE_W500 = "w500"
    const val GLIDE_SIZE_W300 = "w300"

    /** Prefetch only on unmetered networks when this preference is on (default true for mobile data save). */
    const val PREFS_FILE = "tmdb_logo_cache_v$DISK_CACHE_VERSION"
}
