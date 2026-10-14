package com.dskja.betterstreamflix.live

import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.fragments.live.LiveHubSectionZap
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.ui.UserDataNotifier
import com.dskja.betterstreamflix.utils.CrossProviderLibrary
import com.dskja.betterstreamflix.utils.LiveHubCatalog
import com.dskja.betterstreamflix.utils.UserPreferences
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
 *
 * Long-click on a strip item unpins it (SL-007).
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
        // Pin strip is Home chrome (and Live Hub); hide on Search/Settings/Favorites/…
        destinationAllowsStrip = when (destinationId) {
            R.id.home, R.id.live_hub -> true
            else -> false
        }
        applyVisibility()
        if (destinationAllowsStrip) refresh()
    }

    private fun settingsAllowStrip(): Boolean {
        if (!UserPreferences.channelPinStripEnabled) return false
        if (!UserPreferences.channelPinStripLiveOnly) return true
        return LiveHubCatalog.isLiveProvider(UserPreferences.currentProvider)
    }

    fun refresh() {
        refreshJob?.cancel()
        // SL-003: hide immediately so kids/gate cannot click during IO.
        strip.isVisible = false
        refreshJob = activity.lifecycleScope.launch {
            val gated = ProfileManager.isKidsActive() || LiveHubCatalog.isAgeGated()
            if (gated || !destinationAllowsStrip || !settingsAllowStrip()) {
                strip.isVisible = false
                return@launch
            }
            val shows = withContext(Dispatchers.IO) {
                val favorites = runCatching {
                    CrossProviderLibrary.loadFavoriteTvShows(activity)
                }.getOrDefault(emptyList())
                    .filter { LiveHubCatalog.isLiveFavorite(it) }
                    .map { LiveHubCatalog.enrichEpgLite(it) }
                // Do not auto-seed pins from favorites — empty strip + Add is intentional
                // when the user has not pinned (Settings opt-in alone must not flood).
                // SL-009: enrich from hub binding + favorites when available.
                val hub = LiveHubSectionZap.enrichmentCatalog()
                val catalog = (favorites + hub).distinctBy { LiveHubCatalog.identityKey(it) }
                ChannelPinStore.resolveShows(activity, catalog).map { show ->
                    val key = LiveHubCatalog.identityKey(show)
                    val fromHub = catalog.firstOrNull { LiveHubCatalog.identityKey(it) == key }
                    if (fromHub != null) {
                        if (show.programNow.isNullOrBlank()) {
                            show.programNow = LiveHubCatalog.programNowOf(fromHub)
                        }
                        if (show.poster.isNullOrBlank()) show.poster = fromHub.poster
                    }
                    show
                }
            }
            // SL-002: re-check kids AND age-gate after IO.
            if (!destinationAllowsStrip ||
                !settingsAllowStrip() ||
                ProfileManager.isKidsActive() ||
                LiveHubCatalog.isAgeGated()
            ) {
                strip.isVisible = false
                return@launch
            }
            strip.bind(shows, showAdd = true)
        }
    }

    private fun applyVisibility() {
        val gated = ProfileManager.isKidsActive() || LiveHubCatalog.isAgeGated()
        if (!destinationAllowsStrip || gated || !settingsAllowStrip()) {
            strip.isVisible = false
        }
    }

    private fun openChannel(show: TvShow) {
        // SL-003 / SL-008: block kids / age-gate opens.
        if (ProfileManager.isKidsActive() || LiveHubCatalog.isAgeGated()) {
            strip.isVisible = false
            return
        }
        val providerName = show.providerName.orEmpty()
        if (providerName.isBlank() || show.id.isBlank()) return
        WidgetDeepLinkRouter.handle(
            activity,
            WidgetDeepLinks.Target.LiveChannel(
                id = show.id,
                provider = providerName,
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
            // SL-010: no pin mutations under kids / age-gate.
            if (ProfileManager.isKidsActive() || LiveHubCatalog.isAgeGated()) {
                return false
            }
            if (!LiveHubCatalog.isLiveFavorite(show) || show.providerName.isNullOrBlank()) {
                return false
            }
            val alreadyPinned = ChannelPinStore.isPinned(activity, show)
            val countBefore = ChannelPinStore.pinCount(activity)
            val pinned = ChannelPinStore.toggle(activity, show)
            // SL-006: toast when pinning at capacity (eviction kept).
            if (pinned && !alreadyPinned && countBefore >= ChannelPinStore.MAX_PINS) {
                Toast.makeText(
                    activity,
                    activity.getString(
                        R.string.meine_sender_leiste_pin_max,
                        ChannelPinStore.MAX_PINS,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            // Keep library favorite in sync when pinning a live channel.
            if (pinned && !show.isFavorite) {
                val provider = Provider.findByName(show.providerName.orEmpty())
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
