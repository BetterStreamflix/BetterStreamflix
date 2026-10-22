package com.betterstreamflix.app.tvlauncher

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.betterstreamflix.app.R
import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.utils.UserDataCache
import com.betterstreamflix.app.utils.UserPreferences
import com.betterstreamflix.app.utils.WatchNextUtils
import com.betterstreamflix.app.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Publishes Continue Watching to the system Watch Next row and Preview Channels,
 * plus a Recently Watched Preview Channel. TV / leanback only.
 */
@SuppressLint("RestrictedApi")
object TvLauncherPublisher {
    private const val TAG = "TvLauncherPublisher"
    private const val PREFS = "tv_launcher_channels"
    private const val KEY_CONTINUE_CHANNEL = "continue_channel_id"
    private const val KEY_RECENT_CHANNEL = "recent_channel_id"
    private const val CHANNEL_CONTINUE = "bsf_continue_watching"
    private const val CHANNEL_RECENT = "bsf_recently_watched"
    private const val MAX_WATCH_NEXT = 12
    private const val MAX_CHANNEL_PROGRAMS = 16

    suspend fun publish(context: Context) {
        if (!TvLauncherSurface.isEnabled(context)) return
        val app = context.applicationContext
        val provider = UserPreferences.currentProvider ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val snapshot = loadSnapshot(app, provider)
                syncWatchNext(app, provider, snapshot.continueItems)
                val continueChannelId = ensureChannel(
                    app,
                    key = KEY_CONTINUE_CHANNEL,
                    internalId = CHANNEL_CONTINUE,
                    displayName = app.getString(R.string.home_continue_watching),
                )
                val recentChannelId = ensureChannel(
                    app,
                    key = KEY_RECENT_CHANNEL,
                    internalId = CHANNEL_RECENT,
                    displayName = app.getString(R.string.home_recently_watched),
                )
                if (continueChannelId != null) {
                    replacePreviewPrograms(app, continueChannelId, snapshot.continueItems)
                }
                if (recentChannelId != null) {
                    replacePreviewPrograms(app, recentChannelId, snapshot.recentItems)
                }
            }.onFailure {
                Log.w(TAG, "publish failed: ${it.message}")
            }
        }
    }

    fun removeWatchNextProgram(context: Context, programId: Long) {
        if (programId < 0L) return
        WatchNextUtils.deleteProgramById(context.applicationContext, programId)
    }

    fun removePreviewProgram(context: Context, programId: Long) {
        if (programId < 0L) return
        runCatching {
            context.applicationContext.contentResolver.delete(
                TvContractCompat.buildPreviewProgramUri(programId),
                null,
                null,
            )
        }
    }

    private data class LauncherItem(
        val kind: TvWatchDeepLink.Kind,
        val id: String,
        val title: String,
        val description: String?,
        val posterUri: String?,
        val engagementMillis: Long,
        val positionMillis: Int?,
        val durationMillis: Int?,
        val episodeNumber: Int? = null,
        val episodeTitle: String? = null,
        val seasonNumber: Int? = null,
        val seasonTitle: String? = null,
        val seriesId: String? = null,
        val releaseDate: String? = null,
        val watchNext: Boolean,
    )

    private data class Snapshot(
        val continueItems: List<LauncherItem>,
        val recentItems: List<LauncherItem>,
    )

    private suspend fun loadSnapshot(context: Context, provider: Provider): Snapshot {
        val db = AppDatabase.getInstanceOrNull(context)
        val cache = UserDataCache.read(context, provider)

        val watchingMovies = if (db != null) {
            runCatching { db.movieDao().getWatchingMoviesCapped().first() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val watchingEpisodes = if (db != null) {
            runCatching { db.episodeDao().getWatchingEpisodesCapped().first() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val recentMovies = if (db != null) {
            runCatching { db.movieDao().getRecentlyWatched().first() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val recentShows = if (db != null) {
            runCatching { db.tvShowDao().getRecentlyWatched().first() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val cachedMovies = cache?.continueWatchingMovies.orEmpty().associateBy { it.id }
        val cachedEpisodes = cache?.continueWatchingEpisodes.orEmpty().associateBy { it.id }

        val continueFromDb = buildList {
            watchingMovies.forEach { movie ->
                add(movie.toContinueItem(cachedMovies[movie.id]))
            }
            watchingEpisodes.forEach { episode ->
                val cached = cachedEpisodes[episode.id]
                val show = episode.tvShow?.id?.let { id ->
                    db?.tvShowDao()?.getById(id)
                }
                add(episode.toContinueItem(cached, show))
            }
        }

        // Prefer Room; fall back to UserDataCache when DB empty (fresh install / sync lag).
        val continueItems = (
            continueFromDb.ifEmpty {
                buildList {
                    cachedMovies.values.forEach { cached ->
                        add(
                            LauncherItem(
                                kind = TvWatchDeepLink.Kind.MOVIE,
                                id = cached.id,
                                title = cached.title,
                                description = cached.overview,
                                posterUri = cached.poster ?: cached.banner,
                                engagementMillis = cached.lastEngagementTimeUtcMillis ?: 0L,
                                positionMillis = cached.lastPlaybackPositionMillis?.toInt(),
                                durationMillis = cached.durationMillis?.toInt(),
                                releaseDate = cached.released,
                                watchNext = true,
                            ),
                        )
                    }
                    cachedEpisodes.values.forEach { cached ->
                        add(
                            LauncherItem(
                                kind = TvWatchDeepLink.Kind.EPISODE,
                                id = cached.id,
                                title = cached.tvShowTitle ?: cached.title.orEmpty(),
                                description = cached.overview ?: cached.title,
                                posterUri = cached.poster
                                    ?: cached.tvShowPoster
                                    ?: cached.tvShowBanner,
                                engagementMillis = cached.lastEngagementTimeUtcMillis ?: 0L,
                                positionMillis = cached.lastPlaybackPositionMillis?.toInt(),
                                durationMillis = cached.durationMillis?.toInt(),
                                episodeNumber = cached.number,
                                episodeTitle = cached.title,
                                seasonNumber = cached.seasonNumber,
                                seasonTitle = cached.seasonTitle,
                                seriesId = cached.tvShowId,
                                watchNext = true,
                            ),
                        )
                    }
                }
            }
            )
            .sortedByDescending { it.engagementMillis }
            .distinctBy { "${it.kind.wire}:${it.id}" }
            .take(MAX_WATCH_NEXT)

        val continueKeys = continueItems.map { "${it.kind.wire}:${it.id}" }.toSet()
        val recentItems = buildList {
            recentMovies.forEach { movie ->
                val key = "${TvWatchDeepLink.Kind.MOVIE.wire}:${movie.id}"
                if (key in continueKeys) return@forEach
                add(movie.toRecentItem())
            }
            recentShows.forEach { show ->
                add(show.toRecentItem())
            }
            cache?.recentlyWatchedMovies.orEmpty().forEach { cached ->
                val key = "${TvWatchDeepLink.Kind.MOVIE.wire}:${cached.id}"
                if (key in continueKeys || any { it.kind == TvWatchDeepLink.Kind.MOVIE && it.id == cached.id }) {
                    return@forEach
                }
                add(
                    LauncherItem(
                        kind = TvWatchDeepLink.Kind.MOVIE,
                        id = cached.id,
                        title = cached.title,
                        description = cached.overview,
                        posterUri = cached.poster ?: cached.banner,
                        engagementMillis = cached.lastPlayedAtMillis ?: 0L,
                        positionMillis = null,
                        durationMillis = null,
                        releaseDate = cached.released,
                        watchNext = false,
                    ),
                )
            }
            cache?.recentlyWatchedTvShows.orEmpty().forEach { cached ->
                if (any { it.kind == TvWatchDeepLink.Kind.TV_SHOW && it.id == cached.id }) {
                    return@forEach
                }
                add(
                    LauncherItem(
                        kind = TvWatchDeepLink.Kind.TV_SHOW,
                        id = cached.id,
                        title = cached.title,
                        description = cached.overview,
                        posterUri = cached.poster ?: cached.banner,
                        engagementMillis = cached.lastPlayedAtMillis ?: 0L,
                        positionMillis = null,
                        durationMillis = null,
                        releaseDate = cached.released,
                        seriesId = cached.id,
                        watchNext = false,
                    ),
                )
            }
        }
            .sortedByDescending { it.engagementMillis }
            .distinctBy { "${it.kind.wire}:${it.id}" }
            .take(MAX_CHANNEL_PROGRAMS)

        return Snapshot(continueItems = continueItems, recentItems = recentItems)
    }

    private fun Movie.toContinueItem(cached: UserDataCache.CachedMovie?): LauncherItem {
        val history = watchHistory
        return LauncherItem(
            kind = TvWatchDeepLink.Kind.MOVIE,
            id = id,
            title = title,
            description = overview ?: cached?.overview,
            posterUri = poster ?: banner ?: cached?.poster ?: cached?.banner,
            engagementMillis = history?.lastEngagementTimeUtcMillis
                ?: cached?.lastEngagementTimeUtcMillis
                ?: 0L,
            positionMillis = (history?.lastPlaybackPositionMillis ?: cached?.lastPlaybackPositionMillis)
                ?.toInt(),
            durationMillis = (history?.durationMillis ?: cached?.durationMillis)?.toInt(),
            releaseDate = released?.format("yyyy-MM-dd") ?: cached?.released,
            watchNext = true,
        )
    }

    private fun Episode.toContinueItem(
        cached: UserDataCache.CachedEpisode?,
        show: TvShow?,
    ): LauncherItem {
        val history = watchHistory
        val showTitle = show?.title?.takeIf { it.isNotBlank() }
            ?: cached?.tvShowTitle
            ?: tvShow?.title
            ?: title.orEmpty()
        return LauncherItem(
            kind = TvWatchDeepLink.Kind.EPISODE,
            id = id,
            title = showTitle,
            description = overview ?: title ?: cached?.overview ?: cached?.title,
            posterUri = poster
                ?: show?.poster
                ?: show?.banner
                ?: cached?.poster
                ?: cached?.tvShowPoster
                ?: cached?.tvShowBanner,
            engagementMillis = history?.lastEngagementTimeUtcMillis
                ?: cached?.lastEngagementTimeUtcMillis
                ?: 0L,
            positionMillis = (history?.lastPlaybackPositionMillis ?: cached?.lastPlaybackPositionMillis)
                ?.toInt(),
            durationMillis = (history?.durationMillis ?: cached?.durationMillis)?.toInt(),
            episodeNumber = number.takeIf { it > 0 } ?: cached?.number,
            episodeTitle = title ?: cached?.title,
            seasonNumber = season?.number?.takeIf { it > 0 } ?: cached?.seasonNumber,
            seasonTitle = season?.title ?: cached?.seasonTitle,
            seriesId = show?.id ?: tvShow?.id ?: cached?.tvShowId,
            watchNext = true,
        )
    }

    private fun Movie.toRecentItem(): LauncherItem =
        LauncherItem(
            kind = TvWatchDeepLink.Kind.MOVIE,
            id = id,
            title = title,
            description = overview,
            posterUri = poster ?: banner,
            engagementMillis = lastPlayedAtMillis ?: 0L,
            positionMillis = null,
            durationMillis = null,
            releaseDate = released?.format("yyyy-MM-dd"),
            watchNext = false,
        )

    private fun TvShow.toRecentItem(): LauncherItem =
        LauncherItem(
            kind = TvWatchDeepLink.Kind.TV_SHOW,
            id = id,
            title = title,
            description = overview,
            posterUri = poster ?: banner,
            engagementMillis = lastPlayedAtMillis ?: 0L,
            positionMillis = null,
            durationMillis = null,
            releaseDate = released?.format("yyyy-MM-dd"),
            seriesId = id,
            watchNext = false,
        )

    private fun syncWatchNext(
        context: Context,
        provider: Provider,
        items: List<LauncherItem>,
    ) {
        val desired = items.filter { it.watchNext }.take(MAX_WATCH_NEXT)
        val existing = WatchNextUtils.programsForProvider(context, provider.name)
        val desiredIds = desired.map { TvWatchDeepLink.contentId(it.kind, it.id) }.toSet()

        existing.forEach { program ->
            val contentId = program.contentId
            if (contentId != null && contentId !in desiredIds && program.id >= 0L) {
                WatchNextUtils.deleteProgramById(context, program.id)
            }
        }

        desired.forEach { item ->
            val contentId = TvWatchDeepLink.contentId(item.kind, item.id)
            val built = buildWatchNextProgram(item, provider.name)
            val current = existing.find { it.contentId == contentId }
            if (current == null) {
                WatchNextUtils.insert(context, built)
            } else if (current.id >= 0L) {
                WatchNextUtils.updateProgram(
                    context,
                    current.id,
                    WatchNextProgram.Builder(current).apply {
                        applyItem(item, provider.name)
                    }.build(),
                )
            }
        }
    }

    private fun buildWatchNextProgram(item: LauncherItem, providerName: String): WatchNextProgram =
        WatchNextProgram.Builder().apply {
            applyItem(item, providerName)
        }.build()

    private fun WatchNextProgram.Builder.applyItem(item: LauncherItem, providerName: String) {
        setTitle(item.title)
        item.description?.let { setDescription(it) }
        setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
        setLastEngagementTimeUtcMillis(
            item.engagementMillis.takeIf { it > 0L } ?: System.currentTimeMillis(),
        )
        item.positionMillis?.let { setLastPlaybackPositionMillis(it) }
        item.durationMillis?.let { setDurationMillis(it) }
        setContentId(TvWatchDeepLink.contentId(item.kind, item.id))
        setInternalProviderId(providerName)
        setIntentUri(Uri.parse(TvWatchDeepLink.buildUri(item.kind, item.id, providerName)))
        when (item.kind) {
            TvWatchDeepLink.Kind.MOVIE -> {
                setType(TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                item.releaseDate?.let { setReleaseDate(it) }
            }
            TvWatchDeepLink.Kind.EPISODE -> {
                setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
                item.seriesId?.let { setSeriesId(it) }
                item.episodeNumber?.let { setEpisodeNumber(it) }
                item.episodeTitle?.let { setEpisodeTitle(it) }
                item.seasonNumber?.let { setSeasonNumber(it) }
                item.seasonTitle?.let { setSeasonTitle(it) }
            }
            TvWatchDeepLink.Kind.TV_SHOW -> {
                setType(TvContractCompat.PreviewPrograms.TYPE_TV_SERIES)
                item.seriesId?.let { setSeriesId(it) }
            }
        }
        item.posterUri?.takeIf { it.isNotBlank() }?.let { setPosterArtUri(Uri.parse(it)) }
    }

    private fun ensureChannel(
        context: Context,
        key: String,
        internalId: String,
        displayName: String,
    ): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getLong(key, -1L)
        if (stored > 0L && channelExists(context, stored)) {
            return stored
        }

        val existing = findChannelByInternalId(context, internalId)
        if (existing != null) {
            prefs.edit().putLong(key, existing).apply()
            return existing
        }

        val values = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(displayName)
            .setAppLinkIntentUri(Uri.parse("betterstreamflix://home"))
            .setInternalProviderId(internalId)
            .build()
            .toContentValues()

        val uri = runCatching {
            context.contentResolver.insert(TvContractCompat.Channels.CONTENT_URI, values)
        }.getOrNull() ?: return null
        val channelId = ContentUris.parseId(uri)
        prefs.edit().putLong(key, channelId).apply()
        storeChannelLogo(context, channelId)
        requestBrowsable(context, channelId)
        return channelId
    }

    private fun channelExists(context: Context, channelId: Long): Boolean {
        return runCatching {
            context.contentResolver.query(
                TvContractCompat.buildChannelUri(channelId),
                arrayOf(TvContractCompat.Channels._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    private fun findChannelByInternalId(context: Context, internalId: String): Long? {
        return runCatching {
            context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(
                    TvContractCompat.Channels._ID,
                    TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(TvContractCompat.Channels._ID)
                val internalIndex =
                    cursor.getColumnIndex(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)
                if (idIndex < 0 || internalIndex < 0) return@use null
                while (cursor.moveToNext()) {
                    if (cursor.getString(internalIndex) == internalId) {
                        return@use cursor.getLong(idIndex)
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun storeChannelLogo(context: Context, channelId: Long) {
        runCatching {
            val bitmap = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
                ?: return@runCatching
            ChannelLogoUtils.storeChannelLogo(context, channelId, bitmap)
        }
    }

    private fun requestBrowsable(context: Context, channelId: Long) {
        runCatching {
            val intent = Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE).apply {
                putExtra(TvContractCompat.EXTRA_CHANNEL_ID, channelId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            Log.i(TAG, "Channel browsable request skipped: ${it.message}")
        }
    }

    private fun replacePreviewPrograms(
        context: Context,
        channelId: Long,
        items: List<LauncherItem>,
    ) {
        val providerName = UserPreferences.currentProvider?.name ?: return
        runCatching {
            context.contentResolver.delete(
                TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
                null,
                null,
            )
        }
        items.take(MAX_CHANNEL_PROGRAMS).forEachIndexed { index, item ->
            val program = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setTitle(item.title)
                .apply { item.description?.let { setDescription(it) } }
                .setContentId(TvWatchDeepLink.contentId(item.kind, item.id))
                .setInternalProviderId(providerName)
                .setIntentUri(Uri.parse(TvWatchDeepLink.buildUri(item.kind, item.id, providerName)))
                .setWeight(MAX_CHANNEL_PROGRAMS - index)
                .apply {
                    when (item.kind) {
                        TvWatchDeepLink.Kind.MOVIE ->
                            setType(TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                        TvWatchDeepLink.Kind.EPISODE -> {
                            setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
                            item.seriesId?.let { setSeriesId(it) }
                            item.episodeNumber?.let { setEpisodeNumber(it) }
                            item.episodeTitle?.let { setEpisodeTitle(it) }
                            item.seasonNumber?.let { setSeasonNumber(it) }
                            item.seasonTitle?.let { setSeasonTitle(it) }
                        }
                        TvWatchDeepLink.Kind.TV_SHOW -> {
                            setType(TvContractCompat.PreviewPrograms.TYPE_TV_SERIES)
                            item.seriesId?.let { setSeriesId(it) }
                        }
                    }
                    item.posterUri?.takeIf { it.isNotBlank() }?.let { setPosterArtUri(Uri.parse(it)) }
                    item.positionMillis?.let { setLastPlaybackPositionMillis(it) }
                    item.durationMillis?.let { setDurationMillis(it) }
                }
                .build()
            runCatching {
                context.contentResolver.insert(
                    TvContractCompat.PreviewPrograms.CONTENT_URI,
                    program.toContentValues(),
                )
            }
        }
    }
}
