package com.betterstreamflix.app.download

enum class DownloadQualityPreset {
    BEST,
    DATA_SAVER,
    ASK,
    ;

    companion object {
        fun fromKey(key: String?): DownloadQualityPreset =
            entries.find { it.name.equals(key, ignoreCase = true) } ?: BEST
    }
}

enum class DownloadItemState {
    QUEUED,
    PREPARING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    REMOVING,
    ;

    val isActive: Boolean
        get() = this == QUEUED || this == PREPARING || this == DOWNLOADING || this == PAUSED

    companion object {
        fun fromKey(key: String?): DownloadItemState =
            entries.find { it.name.equals(key, ignoreCase = true) } ?: FAILED
    }
}

enum class DownloadKind {
    MOVIE,
    EPISODE,
}

enum class DownloadErrorCode {
    IPTV,
    DRM,
    NO_SERVERS,
    CLOUDFLARE,
    NETWORK,
    NOSPACE,
    /** Soft storage limit reached (distinct from true disk-full NOSPACE). */
    SOFT_LIMIT,
    WIFI_REQUIRED,
    /** Soft hold while free space / soft-limit is exhausted. */
    LOW_STORAGE,
    /** Soft hold while a higher Download Lane uses the parallel slots. */
    LANE_HOLD,
    /** Smart Downloads per-series unwatched quota has no free slots. */
    SERIES_QUOTA,
    EXPIRED,
    /** Provider/CDN returned HTTP 404 — content removed or bad resolve URL. */
    NOT_FOUND,
    FILE_MISSING,
    /** Offline cache wiped after the user changed download storage location. */
    STORAGE_RELOCATED,
    UNSUPPORTED,
    EMPTY_RESPONSE,
    UNKNOWN,
}
