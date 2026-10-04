package com.dskja.betterstreamflix.utils

import com.dskja.betterstreamflix.models.Season

/**
 * Picks later seasons for autoplay. An empty season must not stop the walk.
 */
object EpisodeSeasonCursor {

    fun upcoming(currentSeasonNumber: Int, seasons: List<Season>, limit: Int = 4): List<Season> {
        if (limit <= 0) return emptyList()
        return seasons
            .asSequence()
            .filter { it.number > currentSeasonNumber }
            .sortedBy { it.number }
            .distinctBy { it.number }
            .take(limit)
            .toList()
    }
}
