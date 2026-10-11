package com.dskja.betterstreamflix.widgets

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.iptv.IptvLiveSession
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.ui.FeaturedProviderSwitch
import com.dskja.betterstreamflix.utils.ParentalControlUtils

/** Applies Live [WidgetDeepLinks.Target] on Main Mobile/TV activities. */
object WidgetDeepLinkRouter {
    private const val TAG = "WidgetDeepLink"

    fun handle(activity: FragmentActivity, target: WidgetDeepLinks.Target): Boolean {
        val navHost =
            activity.supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
        val nav = navHost?.navController ?: return false
        return when (target) {
            WidgetDeepLinks.Target.LiveHub -> {
                if (nav.currentDestination?.id != R.id.live_hub) {
                    runCatching { nav.navigate(R.id.live_hub) }
                }
                true
            }
            is WidgetDeepLinks.Target.LiveChannel -> openLiveChannel(activity, nav, target)
        }
    }

    private fun openLiveChannel(
        activity: FragmentActivity,
        nav: NavController,
        target: WidgetDeepLinks.Target.LiveChannel,
    ): Boolean {
        if (ParentalControlUtils.isFilteringActive()) {
            if (nav.currentDestination?.id != R.id.live_hub) {
                runCatching { nav.navigate(R.id.live_hub) }
            }
            return true
        }
        FeaturedProviderSwitch.switchSticky(target.provider, activity)
        val provider = Provider.findByName(target.provider)
        val show = TvShow(
            id = target.id,
            title = target.title,
            poster = target.poster,
        ).apply { providerName = target.provider }
        if (!IptvLiveSession.contains(target.id)) {
            IptvLiveSession.rememberShows(
                listOf(show),
                provider,
                clearOnProviderChange = false,
            )
        }
        IptvLiveSession.setCurrent(target.id)
        val args = Bundle().apply {
            putString("id", target.id)
            putString("title", target.title)
            putString("subtitle", target.title)
            putSerializable(
                "videoType",
                Video.Type.Episode(
                    id = target.id,
                    number = 1,
                    title = target.title,
                    poster = target.poster,
                    overview = null,
                    tvShow = Video.Type.Episode.TvShow(
                        id = target.id,
                        title = target.title,
                        poster = target.poster,
                        banner = null,
                        releaseDate = null,
                        imdbId = null,
                    ),
                    season = Video.Type.Episode.Season(
                        number = 1,
                        title = activity.getString(R.string.player_live_season),
                    ),
                ),
            )
        }
        val liveAction = R.id.action_live_hub_to_player
        return try {
            if (nav.currentDestination?.getAction(liveAction) != null) {
                nav.navigate(liveAction, args)
            } else {
                nav.navigate(R.id.player, args)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open live channel from widget", e)
            runCatching { nav.navigate(R.id.live_hub) }
            true
        }
    }
}
