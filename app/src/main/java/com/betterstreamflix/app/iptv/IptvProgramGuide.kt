package com.betterstreamflix.app.iptv

import com.betterstreamflix.app.BetterStreamflixApp
import com.betterstreamflix.app.R

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
            now.isNotBlank() && next.isNotBlank() ->
                str(R.string.player_live_now_next, now, next)
            now.isNotBlank() -> now
            next.isNotBlank() -> str(R.string.player_live_next_only, next)
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
                now = str(R.string.player_live_247),
                next = listOf(cat, league).firstOrNull { it.isNotBlank() },
            )
        } else {
            val now = when {
                league.isNotBlank() && cat.isNotBlank() && !league.equals(cat, true) ->
                    "$league · $cat"
                league.isNotBlank() -> league
                cat.isNotBlank() -> cat
                else -> str(R.string.player_live_event)
            }
            Slot(
                now = if (viewers > 0) {
                    "$now · ${formatViewers(viewers)}"
                } else {
                    now
                },
                next = null,
            )
        }
    }

    private fun formatViewers(viewers: Int): String = when {
        viewers >= 1_000_000 -> str(R.string.player_live_watching_m, viewers / 1_000_000)
        viewers >= 1_000 -> str(R.string.player_live_watching_k, viewers / 1_000)
        else -> str(R.string.player_live_watching, viewers)
    }

    /** App context when available; English fallbacks keep JVM unit tests working. */
    private fun str(resId: Int, vararg args: Any): String {
        return runCatching {
            val ctx = BetterStreamflixApp.instance
            if (args.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *args)
        }.getOrElse {
            fallback(resId, args)
        }
    }

    private fun fallback(resId: Int, args: Array<out Any>): String = when (resId) {
        R.string.player_live_now_next ->
            "${args.getOrNull(0)}  ·  next: ${args.getOrNull(1)}"
        R.string.player_live_next_only -> "Next: ${args.getOrNull(0)}"
        R.string.player_live_247 -> "24/7 live"
        R.string.player_live_event -> "Live event"
        R.string.player_live_watching_m -> "${args.getOrNull(0)}M watching"
        R.string.player_live_watching_k -> "${args.getOrNull(0)}k watching"
        R.string.player_live_watching -> "${args.getOrNull(0)} watching"
        else -> args.firstOrNull()?.toString().orEmpty()
    }
}
