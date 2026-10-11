package com.dskja.betterstreamflix.download

/**
 * Parallel-download priority lanes.
 *
 * Currently watching > user Queue > Smart-Auto.
 */
enum class DownloadLane {
    WATCHING,
    QUEUE,
    SMART_AUTO,
    ;

    /** Lower rank runs first when filling [UserPreferences.downloadMaxConcurrent] slots. */
    val rank: Int
        get() = when (this) {
            WATCHING -> 0
            QUEUE -> 1
            SMART_AUTO -> 2
        }
}
