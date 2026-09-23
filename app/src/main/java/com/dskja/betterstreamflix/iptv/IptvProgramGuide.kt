package com.dskja.betterstreamflix.iptv

/**
 * Lightweight now/next program labels for live chrome and the channel guide.
 * Full XMLTV is out of scope; sports / catalog providers supply short strings.
 */
object IptvProgramGuide {

    data class Slot(
        val now: String? = null,
        val next: String? = null,
    )

    fun formatMetaLine(channel: IptvLiveSession.Channel): String? {
        val now = channel.programNow?.trim().orEmpty()
        val next = channel.programNext?.trim().orEmpty()
        return when {
            now.isNotBlank() && next.isNotBlank() -> "$now  ·  next: $next"
            now.isNotBlank() -> now
            next.isNotBlank() -> "Next: $next"
            else -> null
        }
    }

    fun formatGuideSubtitle(channel: IptvLiveSession.Channel): String? {
        val group = channel.group?.trim().orEmpty()
        val program = formatMetaLine(channel)
        return when {
            group.isNotBlank() && !program.isNullOrBlank() -> "$group · $program"
            !program.isNullOrBlank() -> program
            group.isNotBlank() -> group
            else -> null
        }
    }

    /** SportsBite-style: 24/7 vs live event label. */
    fun sportsSlot(
        alwaysLive: Boolean,
        category: String?,
        tag: String?,
        viewers: Int = 0,
    ): Slot {
        val cat = category?.trim().orEmpty()
        val league = tag?.trim().orEmpty()
        return if (alwaysLive) {
            Slot(
                now = "24/7 live",
                next = listOf(cat, league).firstOrNull { it.isNotBlank() },
            )
        } else {
            val now = when {
                league.isNotBlank() && cat.isNotBlank() && !league.equals(cat, true) ->
                    "$league · $cat"
                league.isNotBlank() -> league
                cat.isNotBlank() -> cat
                else -> "Live event"
            }
            Slot(
                now = if (viewers > 0) "$now · ${formatViewers(viewers)}" else now,
                next = null,
            )
        }
    }

    private fun formatViewers(viewers: Int): String = when {
        viewers >= 1_000_000 -> "${viewers / 1_000_000}M watching"
        viewers >= 1_000 -> "${viewers / 1_000}k watching"
        else -> "$viewers watching"
    }
}
