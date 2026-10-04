package com.dskja.betterstreamflix.logo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.TmdbUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Idle / unmetered logo warm-up for Featured carousel neighbors.
 *
 * Resolves missing TMDb logos for upcoming items (not just Glide-prefetch of
 * already-known URLs), persists into Room when possible, and preloads the bitmap.
 * Shares [TmdbLogoCache] single-flight so binder + enrich do not double-fetch.
 */
object FeaturedLogoEnrich {

    private val jobs = java.util.WeakHashMap<View, Job>()
    private val meteredListener = FeaturedNetworkMonitor.Listener {
        cancelAll()
    }

    /**
     * Warm the next [count] items after [fromIndex] (wrapping). Skips when the
     * network is metered or unknown (safe default). Cancels any prior enrich
     * tied to [anchor].
     */
    fun enrichUpcoming(
        context: Context,
        anchor: View,
        items: List<AppAdapter.Item>,
        fromIndex: Int,
        count: Int = 2,
        wifiOnly: Boolean = true,
    ) {
        if (!UserPreferences.enableTmdb || !UserPreferences.enableTmdbLogos) return
        if (items.isEmpty() || count <= 0) return
        FeaturedNetworkMonitor.ensureRegistered(context)
        FeaturedNetworkMonitor.addListener(meteredListener)
        if (wifiOnly && !isDefinitelyUnmetered(context)) return

        fun start() {
            val owner = anchor.findViewTreeLifecycleOwner()
            if (owner == null) {
                anchor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        anchor.removeOnAttachStateChangeListener(this)
                        start()
                    }
                    override fun onViewDetachedFromWindow(v: View) = Unit
                })
                return
            }
            jobs.remove(anchor)?.cancel()
            val appCtx = context.applicationContext
            val targets = buildList {
                var i = 0
                var offset = 1
                while (i < count && offset <= items.size) {
                    val idx = (fromIndex + offset).floorMod(items.size)
                    offset++
                    when (val item = items.getOrNull(idx)) {
                        is Movie -> if (
                            TmdbLogoPicker.shouldUpgradeLogo(
                                item.logo,
                                item.logoLanguage,
                                UserPreferences.currentProvider?.language,
                            )
                        ) {
                            add(item)
                            i++
                        }
                        is TvShow -> if (
                            TmdbLogoPicker.shouldUpgradeLogo(
                                item.logo,
                                item.logoLanguage,
                                UserPreferences.currentProvider?.language,
                            )
                        ) {
                            add(item)
                            i++
                        }
                    }
                }
            }
            if (targets.isEmpty()) return
            jobs[anchor] = owner.lifecycleScope.launch {
                targets.forEach { item ->
                    if (!isDefinitelyUnmetered(appCtx)) return@launch
                    val url = withContext(Dispatchers.IO) {
                        try {
                            when (item) {
                                is Movie -> resolveMovie(item)
                                is TvShow -> resolveTv(item)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    } ?: return@forEach
                    TmdbLogoGlide.prefetch(appCtx, url, wifiOnly = true)
                }
            }
        }
        start()
    }

    /** Prefetch next slide banner at Featured decode size on unmetered networks. */
    fun prefetchBanner(context: Context, bannerUrl: String?) {
        if (bannerUrl.isNullOrBlank()) return
        if (!isDefinitelyUnmetered(context)) return
        val url = com.dskja.betterstreamflix.utils.ArtworkUrls.preferFeatured(bannerUrl)
            ?: bannerUrl
        val (w, h) = if (com.dskja.betterstreamflix.utils.DeviceCapabilities.isLeanbackDevice(context)) {
            com.dskja.betterstreamflix.ui.FeaturedSwiperChrome.tvBackdropOverride(context)
        } else {
            com.dskja.betterstreamflix.ui.FeaturedSwiperChrome.artworkOverride(context)
        }
        com.bumptech.glide.Glide.with(context.applicationContext)
            .load(url)
            .preload(w, h)
    }

    fun cancel(anchor: View) {
        jobs.remove(anchor)?.cancel()
    }

    fun cancelAll() {
        synchronized(jobs) {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
        }
    }

    private suspend fun resolveMovie(movie: Movie): String? {
        val lang = UserPreferences.currentProvider?.language
        val url = TmdbUtils.resolveTitleLogo(
            title = movie.title,
            year = movie.released?.format("yyyy")?.toIntOrNull(),
            isTv = false,
            tmdbId = movie.tmdbId,
            imdbId = movie.imdbId,
            language = lang,
        ) ?: return null
        if (!TmdbLogoPicker.isTrustedTmdbLogo(url)) return null
        movie.logo = url
        movie.logoSource = LogoSource.TMDB
        movie.logoLanguage = lang
        LogoPersist.persistMovieLogo(movie)
        return url
    }

    private suspend fun resolveTv(tvShow: TvShow): String? {
        val lang = UserPreferences.currentProvider?.language
        val url = TmdbUtils.resolveTitleLogo(
            title = tvShow.title,
            year = tvShow.released?.format("yyyy")?.toIntOrNull(),
            isTv = true,
            tmdbId = tvShow.tmdbId,
            imdbId = tvShow.imdbId,
            language = lang,
        ) ?: return null
        if (!TmdbLogoPicker.isTrustedTmdbLogo(url)) return null
        tvShow.logo = url
        tvShow.logoSource = LogoSource.TMDB
        tvShow.logoLanguage = lang
        LogoPersist.persistTvLogo(tvShow)
        return url
    }

    /**
     * True only when we positively know the active network is unmetered.
     * Unknown / null capabilities → false (safe: skip enrich).
     */
    fun isDefinitelyUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun Int.floorMod(m: Int): Int {
        if (m <= 0) return 0
        val r = this % m
        return if (r >= 0) r else r + m
    }
}
