package com.dskja.betterstreamflix.ui

import android.content.Context
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Show
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.WatchItem
import com.dskja.betterstreamflix.utils.format
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared Featured hero helpers: status copy, resume CTA, watch progress,
 * episode meta, impressions, and artwork URLs for warm-up.
 *
 * Contract (with [com.dskja.betterstreamflix.utils.HomeCatalogPipeline] +
 * [FeaturedSwiperChrome]):
 * - FEATURED rows are identity-cloned so shelf itemType mutations cannot
 *   crash ViewPager2 (BETTERSTREAMFLIX-13).
 * - List CTAs observe Room favorite Flow.
 * - Neighbor logos enrich on unmetered networks only; cancel on recycle /
 *   network downgrade.
 * - TV rotation should use [PAYLOAD_ROTATE] instead of full rebind.
 */
object FeaturedHeroController {

    const val PAYLOAD_ROTATE = "featured_rotate"
    const val MAX_FEATURED_BITMAP_BUDGET = 12

    private val impressionCount = AtomicInteger(0)

    data class WatchProgress(
        val percent: Int,
        val history: WatchItem.WatchHistory?,
    )

    fun watchProgress(show: Show): WatchProgress {
        val history = when (show) {
            is Movie -> show.watchHistory
            is TvShow -> show.episodeToWatch?.watchHistory
            else -> null
        }
        if (history == null || history.durationMillis <= 0L) {
            return WatchProgress(0, null)
        }
        val pct = (history.lastPlaybackPositionMillis * 100 /
            history.durationMillis.toDouble()).toInt().coerceIn(0, 100)
        return WatchProgress(pct, history)
    }

    fun watchCtaLabel(context: Context, show: Show): String {
        val progress = watchProgress(show)
        return if (progress.history != null && progress.percent in 1..95) {
            context.getString(R.string.home_swiper_resume, progress.percent)
        } else {
            context.getString(R.string.home_swiper_watch_now)
        }
    }

    fun statusLine(context: Context, show: Show): String? {
        return when (show) {
            is Movie -> {
                val year = show.released?.format("yyyy")
                when {
                    show.watchHistory != null ->
                        context.getString(R.string.home_swiper_continue)
                    !year.isNullOrBlank() ->
                        context.getString(R.string.home_swiper_released_year, year)
                    else -> context.getString(R.string.home_swiper_now_playing)
                }
            }
            is TvShow -> {
                val ep = episodeMeta(context, show)
                when {
                    show.episodeToWatch?.watchHistory != null ->
                        context.getString(R.string.home_swiper_continue)
                    ep != null -> ep
                    show.seasons.isNotEmpty() ->
                        context.getString(R.string.home_swiper_all_episodes)
                    else -> context.getString(R.string.tv_show_item_type)
                }
            }
            else -> null
        }
    }

    fun episodeMeta(context: Context, tvShow: TvShow): String? {
        val episode = tvShow.episodeToWatch
            ?: tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()
            ?: return null
        val season = tvShow.seasons.firstOrNull { season ->
            season.episodes.any { it.id == episode.id }
        } ?: tvShow.seasons.lastOrNull()
        val seasonNumber = season?.number ?: 0
        return if (seasonNumber != 0) {
            context.getString(
                R.string.tv_show_item_season_number_episode_number,
                seasonNumber,
                episode.number,
            )
        } else {
            context.getString(R.string.tv_show_item_episode_number, episode.number)
        }
    }

    fun genresLine(show: Show): String {
        val labels = when (show) {
            is Movie -> show.genres.map { it.name }
            is TvShow -> show.genres.map { it.name }
            else -> emptyList()
        }.filter { it.isNotBlank() }
        return labels.joinToString(", ")
    }

    fun bannerUrl(item: AppAdapter.Item?): String? = FeaturedTvRotation.bannerOf(item)

    fun recordImpression(show: Show?) {
        if (show == null) return
        impressionCount.incrementAndGet()
        // Lightweight debug counter — Settings TMDb telemetry can surface later.
        runCatching {
            android.util.Log.d(
                "FeaturedTelemetry",
                "impression #${impressionCount.get()} title=${FeaturedTvRotation.titleOf(show)}",
            )
        }
    }

    fun impressionCount(): Int = impressionCount.get()

    fun resetImpressions() {
        impressionCount.set(0)
    }
}
