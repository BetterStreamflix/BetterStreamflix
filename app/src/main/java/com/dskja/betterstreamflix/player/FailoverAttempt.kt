package com.dskja.betterstreamflix.player

/**
 * One hop in a playback failover story — metadata only for the mini-timeline UX.
 */
data class FailoverAttempt(
    /** 1-based server ordinal; 0 for non-server stages (software / external). */
    val serverOrdinal: Int,
    val serverName: String = "",
    val kind: FailoverStepKind,
)

enum class FailoverStepKind {
    TIMEOUT,
    CAPTCHA,
    GEO,
    DOMAIN,
    GENERIC,
    /** Collapsed “…” placeholder between head and tail of a long chain. */
    ELLIPSIS,
    SOFTWARE_DECODER,
    EXTERNAL_PLAYER,
    OK,
}

data class FailoverTimelineStep(
    val serverOrdinal: Int,
    val kind: FailoverStepKind,
    /** True when this step is a collapsed “…” placeholder. */
    val ellipsis: Boolean = false,
)
