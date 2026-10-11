package com.dskja.betterstreamflix.download

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
    WIFI_REQUIRED,
    /** Soft hold while free space / soft-limit is exhausted. */
    LOW_STORAGE,
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
