package com.dskja.betterstreamflix.live

import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.ui.UserDataNotifier
import com.dskja.betterstreamflix.utils.CrossProviderLibrary
import com.dskja.betterstreamflix.utils.LiveHubCatalog
import com.dskja.betterstreamflix.widgets.WidgetDeepLinkRouter
import com.dskja.betterstreamflix.widgets.WidgetDeepLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Binds [ChannelPinStripView] on Main Mobile/TV — load pins, seed from live favorites,
 * open channels, hide on player / kids.
 */
class ChannelPinStripController(
    private val activity: FragmentActivity,
    private val strip: ChannelPinStripView,
) {
    private var refreshJob: Job? = null
    private var destinationAllowsStrip: Boolean = true

    fun attach() {
        strip.onChannelClick = ::openChannel
        strip.onChannelLongClick = ::unpinChannel
        strip.onAddClick = ::openLiveHub
        strip.setTitleVisible(true)
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                refresh()
                UserDataNotifier.updates.collectLatest { refresh() }
            }
        }
    }

    fun onDestinationChanged(destinationId: Int?) {
        destinationAllowsStrip = when (destinationId) {
            R.id.player -> false
            else -> true
        }
        applyVisibility()
        if (destinationAllowsStrip) refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = activity.lifecycleScope.launch {
            val gated = ProfileManager.isKidsActive() || LiveHubCatalog.isAgeGated()
            if (gated) {
                strip.isVisible = false
                return@launch
            }
            val shows = withContext(Dispatchers.IO) {
                val favorites = runCatching {
                    CrossProviderLibrary.loadFavoriteTvShows(activity)
                }.getOrDefault(emptyList())
                    .filter { LiveHubCatalog.isLiveFavorite(it) }
                ChannelPinStore.seedFromFavoritesIfEmpty(activity, favorites)
                ChannelPinStore.resolveShows(activity, favorites)
            }
            if (!destinationAllowsStrip || ProfileManager.isKidsActive()) {
                strip.isVisible = false
                return@launch
            }
            strip.bind(shows, showAdd = true)
        }
    }

    private fun applyVisibility() {
        val gated = ProfileManager.isKidsActive() || LiveHubCatalog.isAgeGated()
        if (!destinationAllowsStrip || gated) {
            strip.isVisible = false
        }
    }

    private fun openChannel(show: TvShow) {
        val provider = show.providerName.orEmpty()
        if (provider.isBlank() || show.id.isBlank()) return
        WidgetDeepLinkRouter.handle(
            activity,
            WidgetDeepLinks.Target.LiveChannel(
                id = show.id,
                provider = provider,
                title = show.title,
                poster = show.poster,
            ),
        )
    }

    private fun unpinChannel(show: TvShow) {
        ChannelPinStore.unpin(activity, show)
        Toast.makeText(
            activity,
            activity.getString(R.string.meine_sender_leiste_unpinned, show.title),
            Toast.LENGTH_SHORT,
        ).show()
        UserDataNotifier.notifyChanged()
        refresh()
    }

    private fun openLiveHub() {
        val navHost =
            activity.supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
        val nav = navHost?.navController ?: return
        if (nav.currentDestination?.id != R.id.live_hub) {
            runCatching { nav.navigate(R.id.live_hub) }
        }
    }

    companion object {
        fun pinFromShowOptions(activity: FragmentActivity, show: TvShow): Boolean {
            if (!LiveHubCatalog.isLiveFavorite(show) || show.providerName.isNullOrBlank()) {
                return false
            }
            val pinned = ChannelPinStore.toggle(activity, show)
            // Keep library favorite in sync when pinning a live channel.
            if (pinned && !show.isFavorite) {
                val provider = com.dskja.betterstreamflix.providers.Provider
                    .findByName(show.providerName.orEmpty())
                if (provider != null) {
                    activity.lifecycleScope.launch(Dispatchers.IO) {
                        runCatching {
                            com.dskja.betterstreamflix.sync.FavoriteMutations.setTvShowFavorite(
                                activity,
                                provider,
                                show,
                                true,
                            )
                        }
                        withContext(Dispatchers.Main) {
                            UserDataNotifier.notifyChanged()
                        }
                    }
                }
            } else {
                UserDataNotifier.notifyChanged()
            }
            return pinned
        }
    }
}
