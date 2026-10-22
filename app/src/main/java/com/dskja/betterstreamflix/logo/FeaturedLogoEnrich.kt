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
import com.dskja.betterstreamflix.utils.loadMovieBanner
import com.dskja.betterstreamflix.utils.loadTvShowBanner
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
 * On metered networks only the next item's logo is resolved (no bitmap
 * prefetch, no backdrop work).
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
        if (UserPreferences.dataSaverMode) return
        if (!UserPreferences.enableTmdb || !UserPreferences.enableTmdbLogos) return
        if (items.isEmpty() || count <= 0) return
        FeaturedNetworkMonitor.ensureRegistered(context)
        FeaturedNetworkMonitor.addListener(meteredListener)
        // On metered: still resolve the single next item's logo (one API call,
        // no bitmap prefetch / backdrop work) so the upcoming slide never ships
        // a text-only hero just because the user is off WiFi.
        val unmetered = isDefinitelyUnmetered(context)
        val effectiveCount = if (wifiOnly && !unmetered) 1 else count

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
                while (i < effectiveCount && offset <= items.size) {
                    val idx = (fromIndex + offset).floorMod(items.size)
                    offset++
                    // Metered single-slot: only spend it on a missing/wrong-logo
                    // item — backdrop-only upgrades stay WiFi work.
                    val needsLogo = when (val item = items.getOrNull(idx)) {
                        is Movie -> TmdbLogoPicker.shouldUpgradeLogo(
                            item.logo, item.logoLanguage,
                            UserPreferences.currentProvider?.language,
                        )
                        is TvShow -> TmdbLogoPicker.shouldUpgradeLogo(
                            item.logo, item.logoLanguage,
                            UserPreferences.currentProvider?.language,
                        )
                        else -> false
                    }
                    val needsBackdrop = !unmetered && when (val item = items.getOrNull(idx)) {
                        is Movie -> needsBackdropUpgrade(item.banner, item.poster)
                        is TvShow -> needsBackdropUpgrade(item.banner, item.poster)
                        else -> false
                    }
                    if (needsLogo || needsBackdrop) {
                        items.getOrNull(idx)?.let { add(it) }
                        i++
                    }
                }
            }
            if (targets.isEmpty()) return
            jobs[anchor] = owner.lifecycleScope.launch {
                targets.forEach { item ->
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
                    }
                    // Heavy payloads stay unmetered-only: bitmap prefetch + backdrop.
                    if (!isDefinitelyUnmetered(appCtx)) return@launch
                    if (url != null) TmdbLogoGlide.prefetch(appCtx, url, wifiOnly = true)
                    // Upcoming slide needs a real backdrop too — resolve + persist
                    // so the swiper binds it already upgraded.
                    try {
                        when (item) {
                            is Movie -> if (needsBackdropUpgrade(item.banner, item.poster)) {
                                resolveMovieBackdrop(item)?.let { backdrop ->
                                    item.banner = backdrop
                                    persistMovieBanner(item.id, backdrop)
                                    prefetchBanner(appCtx, backdrop)
                                }
                            }
                            is TvShow -> if (needsBackdropUpgrade(item.banner, item.poster)) {
                                resolveTvBackdrop(item)?.let { backdrop ->
                                    item.banner = backdrop
                                    persistTvBanner(item.id, backdrop)
                                    prefetchBanner(appCtx, backdrop)
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }
        }
        start()
    }

    /**
     * Featured cover upgrade: providers that only ship a portrait poster get it
     * duplicated into `banner` (e.g. Anikoto), which centerCrops into a blurry,
     * badly-framed hero. Detects poster-as-banner / missing banner, resolves a
     * real TMDb backdrop, persists it into Room, and reloads the bound view.
     * Runs on any connected network (one API call + one image for the visible
     * hero) but respects data-saver.
     */
    fun enrichBackdropIfNeeded(
        anchor: View,
        imageView: android.widget.ImageView,
        movie: Movie,
    ) {
        if (UserPreferences.dataSaverMode || !UserPreferences.enableTmdb) return
        if (!needsBackdropUpgrade(movie.banner, movie.poster)) return
        startBackdropJob(anchor, key = "m:${movie.id}") {
            val backdrop = resolveMovieBackdrop(movie)
            if (backdrop != null) {
                movie.banner = backdrop
                persistMovieBanner(movie.id, backdrop)
                imageView.post {
                    imageView.loadMovieBanner(movie, hero = false) {
                        centerCrop().transition(
                            com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
                                .withCrossFade(320),
                        )
                    }
                }
            }
        }
    }

    fun enrichBackdropIfNeeded(
        anchor: View,
        imageView: android.widget.ImageView,
        tvShow: TvShow,
    ) {
        if (UserPreferences.dataSaverMode || !UserPreferences.enableTmdb) return
        if (!needsBackdropUpgrade(tvShow.banner, tvShow.poster)) return
        startBackdropJob(anchor, key = "t:${tvShow.id}") {
            val backdrop = resolveTvBackdrop(tvShow)
            if (backdrop != null) {
                tvShow.banner = backdrop
                persistTvBanner(tvShow.id, backdrop)
                imageView.post {
                    imageView.loadTvShowBanner(tvShow, hero = false) {
                        centerCrop().transition(
                            com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
                                .withCrossFade(320),
                        )
                    }
                }
            }
        }
    }

    /** Poster copied into banner, or no banner at all → TMDb backdrop worth fetching. */
    private fun needsBackdropUpgrade(banner: String?, poster: String?): Boolean {
        val b = banner?.trim().orEmpty()
        if (b.isEmpty()) return true
        val p = poster?.trim().orEmpty()
        return p.isNotEmpty() && b == p
    }

    private val backdropJobs = java.util.WeakHashMap<View, MutableMap<String, Job>>()

    private fun startBackdropJob(anchor: View, key: String, work: suspend () -> Unit) {
        val owner = anchor.findViewTreeLifecycleOwner()
        if (owner == null) {
            anchor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    anchor.removeOnAttachStateChangeListener(this)
                    startBackdropJob(anchor, key, work)
                }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
            return
        }
        val map = synchronized(backdropJobs) {
            backdropJobs.getOrPut(anchor) { mutableMapOf() }
        }
        if (map.containsKey(key)) return
        map[key] = owner.lifecycleScope.launch {
            try {
                work()
            } finally {
                synchronized(backdropJobs) { backdropJobs[anchor]?.remove(key) }
            }
        }
    }

    private suspend fun resolveMovieBackdrop(movie: Movie): String? = withContext(Dispatchers.IO) {
        runCatching {
            val lang = UserPreferences.currentProvider?.language
            val tmdb = movie.tmdbId?.toIntOrNull()?.let { TmdbUtils.getMovieById(it, lang) }
                ?: movie.imdbId?.takeIf { it.isNotBlank() }
                    ?.let { TmdbUtils.getMovieByImdbId(it, lang) }
                ?: TmdbUtils.getMovie(
                    movie.title,
                    year = movie.released?.format("yyyy")?.toIntOrNull(),
                    language = lang,
                )
            val banner = tmdb?.banner?.takeIf { it.isNotBlank() } ?: return@runCatching null
            if (movie.tmdbId.isNullOrBlank()) movie.tmdbId = tmdb.tmdbId
            if (movie.imdbId.isNullOrBlank()) movie.imdbId = tmdb.imdbId
            banner
        }.getOrNull()
    }

    private suspend fun resolveTvBackdrop(tvShow: TvShow): String? = withContext(Dispatchers.IO) {
        runCatching {
            val lang = UserPreferences.currentProvider?.language
            val tmdb = tvShow.tmdbId?.toIntOrNull()?.let { TmdbUtils.getTvShowById(it, lang) }
                ?: tvShow.imdbId?.takeIf { it.isNotBlank() }
                    ?.let { TmdbUtils.getTvShowByImdbId(it, lang) }
                ?: TmdbUtils.getTvShow(
                    tvShow.title,
                    year = tvShow.released?.format("yyyy")?.toIntOrNull(),
                    language = lang,
                )
            val banner = tmdb?.banner?.takeIf { it.isNotBlank() } ?: return@runCatching null
            if (tvShow.tmdbId.isNullOrBlank()) tvShow.tmdbId = tmdb.tmdbId
            if (tvShow.imdbId.isNullOrBlank()) tvShow.imdbId = tmdb.imdbId
            banner
        }.getOrNull()
    }

    private suspend fun persistMovieBanner(id: String, banner: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val db = com.dskja.betterstreamflix.database.AppDatabase
                    .getInstanceOrNull(com.dskja.betterstreamflix.BetterStreamflixApp.instance)
                    ?: return@runCatching
                if (db.movieDao().getById(id) != null) db.movieDao().updateBanner(id, banner)
            }
        }
    }

    private suspend fun persistTvBanner(id: String, banner: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val db = com.dskja.betterstreamflix.database.AppDatabase
                    .getInstanceOrNull(com.dskja.betterstreamflix.BetterStreamflixApp.instance)
                    ?: return@runCatching
                if (db.tvShowDao().getById(id) != null) db.tvShowDao().updateBanner(id, banner)
            }
        }
    }

    /** Prefetch next slide banner at Featured decode size on unmetered networks. */
    fun prefetchBanner(context: Context, bannerUrl: String?) {
        if (UserPreferences.dataSaverMode) return
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
