package com.dskja.betterstreamflix.download

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

object StreamflixDownloadManager {
    @Volatile
    private var downloadManager: DownloadManager? = null

    @Volatile
    private var simpleCache: SimpleCache? = null

    @Volatile
    private var dataSourceFactory: DownloadDataSourceFactory? = null

    @Volatile
    private var notificationHelper: DownloadNotificationHelper? = null

    @Volatile
    private var databaseProvider: StandaloneDatabaseProvider? = null

    @Volatile
    private var connectivityWatcherStarted = false

    private val executor: Executor = Executors.newFixedThreadPool(2)
    private val connectivityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityActionMutex = Mutex()

    fun get(context: Context): DownloadManager {
        downloadManager?.let { return it }
        synchronized(this) {
            downloadManager?.let { return it }
            val app = context.applicationContext
            DownloadConnectivityMonitor.start(app)
            DownloadNotifier.ensureChannel(app)

            val dbProvider = StandaloneDatabaseProvider(app).also { databaseProvider = it }
            val cache = SimpleCache(
                DownloadStorage.cacheDir(app),
                NoOpCacheEvictor(),
                dbProvider,
            ).also { simpleCache = it }

            val upstreamFactory = DownloadDataSourceFactory(app).also { dataSourceFactory = it }
            val cacheFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val manager = DownloadManager(
                app,
                dbProvider,
                cache,
                cacheFactory,
                executor,
            ).apply {
                maxParallelDownloads = UserPreferences.downloadMaxConcurrent.coerceIn(1, 4)
                addListener(DownloadEventBridge)
            }
            downloadManager = manager
            notificationHelper = DownloadNotificationHelper(app, DownloadNotifier.CHANNEL_ID)
            DownloadEventBridge.attach(app)
            startConnectivityWatcher(app)
            pruneOrphanedSidecarDirs(app)
            return manager
        }
    }

    private fun startConnectivityWatcher(app: Context) {
        if (connectivityWatcherStarted) return
        connectivityWatcherStarted = true
        connectivityScope.launch {
            DownloadConnectivityMonitor.status
                .map { it.type }
                .distinctUntilChanged()
                .collectLatest { type ->
                    // Debounce flaky network flips so we don't thrash pause/resume.
                    delay(750)
                    connectivityActionMutex.withLock {
                        val repo = DownloadRepository.get(app)
                        if (UserPreferences.downloadWifiOnly && type != DownloadNetworkType.WIFI) {
                            repo.pauseAll()
                        } else if (type != DownloadNetworkType.NONE) {
                            repo.resumeAll()
                        }
                    }
                }
        }
    }

    /**
     * Deletes download sidecar subtitle directories whose owning download item no
     * longer exists in the DB (leftovers from crashes or older versions).
     */
    private fun pruneOrphanedSidecarDirs(app: Context) {
        connectivityScope.launch {
            runCatching {
                val validKeys = DownloadRepository.get(app)
                    .getAllOnce()
                    .map { it.contentKey.replace(Regex("[^a-zA-Z0-9._-]"), "_") }
                    .toSet()
                val subsRoot = java.io.File(DownloadStorage.downloadsDir(app), "subs")
                subsRoot.listFiles()
                    ?.filter { it.isDirectory && it.name !in validKeys }
                    ?.forEach { DownloadStorage.deleteQuietly(it) }
            }
        }
    }

    fun dataSourceFactory(context: Context): DownloadDataSourceFactory {
        get(context)
        return dataSourceFactory!!
    }

    fun cacheDataSourceFactory(context: Context): CacheDataSource.Factory {
        val manager = get(context)
        val cache = requireCacheReady()
        val upstream = dataSourceFactory
            ?: throw IOException("download data source factory not ready")
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .also { manager /* keep ref */ }
    }

    /**
     * Read-only [CacheDataSource] for offline playback. Uses the same [SimpleCache] as
     * downloads and does not write. Upstream intentionally rejects network opens so a
     * cache miss cannot fetch an expired CDN URL and surface a raw HTTP 404.
     *
     * MediaItem must come from [DownloadRequest.toMediaItem] (stream keys + cache key)
     * so HLS/DASH variants match what was downloaded — see [OfflinePlayback.buildOfflineMediaItem].
     *
     * Throws [IOException] (soft) instead of crashing when cache is mid-relocate.
     */
    fun playbackCacheDataSourceFactory(context: Context): CacheDataSource.Factory {
        get(context)
        val cache = requireCacheReady()
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(OfflineCacheMissDataSource.Factory)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
    }

    private fun requireCacheReady(): SimpleCache {
        return simpleCache
            ?: throw IOException("offline cache not ready — retry after storage settles")
    }

    /**
     * Copy a completed progressive Media3 download out of SimpleCache into a shareable
     * temp file for external players. Returns null for HLS/DASH / incomplete / oversized.
     */
    fun exportProgressiveShareFile(
        context: Context,
        media3Id: String,
        mimeHint: String = "",
        streamUrl: String = "",
        maxBytes: Long = 1_500L * 1024L * 1024L,
    ): java.io.File? {
        if (media3Id.isBlank()) return null
        runCatching { get(context) }.getOrElse { return null }
        val cache = simpleCache ?: return null
        val download = downloadManager?.downloadIndex?.getDownload(media3Id) ?: return null
        if (download.state != Download.STATE_COMPLETED) return null
        val mime = mimeHint.ifBlank { download.request.mimeType.orEmpty() }
        if (isAdaptiveStream(mime, streamUrl.ifBlank { download.request.uri.toString() })) {
            return null
        }
        val lengthHint = download.contentLength.takeIf { it > 0L }
            ?: download.bytesDownloaded
        if (lengthHint > maxBytes) return null

        val ext = when {
            mime.contains("mp4", ignoreCase = true) ||
                streamUrl.contains(".mp4", ignoreCase = true) -> "mp4"
            mime.contains("webm", ignoreCase = true) -> "webm"
            mime.contains("mkv", ignoreCase = true) ||
                mime.contains("matroska", ignoreCase = true) -> "mkv"
            else -> "bin"
        }
        val out = java.io.File(context.cacheDir, "share_${media3Id.hashCode()}.$ext")
        return runCatching {
            val dataSource = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(OfflineCacheMissDataSource.Factory)
                .setCacheWriteDataSinkFactory(null)
                .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
                .createDataSource()
            val dataSpec = DataSpec(download.request.uri)
            dataSource.open(dataSpec)
            try {
                java.io.FileOutputStream(out).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val read = dataSource.read(buf, 0, buf.size)
                        if (read == androidx.media3.common.C.RESULT_END_OF_INPUT) break
                        if (read < 0) break
                        written += read
                        if (written > maxBytes) {
                            fos.close()
                            out.delete()
                            return@runCatching null
                        }
                        fos.write(buf, 0, read)
                    }
                }
            } finally {
                runCatching { dataSource.close() }
            }
            out.takeIf { it.exists() && it.length() > 0L }
        }.getOrNull()
    }

    private fun isAdaptiveStream(mime: String, url: String): Boolean {
        val m = mime.lowercase()
        val u = url.lowercase()
        return m.contains("mpegurl") ||
            m.contains("dash+xml") ||
            m.contains("x-mpegurl") ||
            u.contains(".m3u8") ||
            u.contains(".mpd")
    }

    /**
     * Upstream used only for offline playback. Any open means the requested bytes were
     * not in the download cache (wrong stream keys, wiped cache, incomplete download).
     */
    private class OfflineCacheMissDataSource : BaseDataSource(/* isNetwork= */ false) {
        private var openedUri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            openedUri = dataSpec.uri
            throw IOException(
                "Offline cache miss for ${dataSpec.uri} — re-download or play online",
            )
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            throw IOException("Offline cache miss")
        }

        override fun getUri(): Uri? = openedUri

        override fun close() {
            openedUri = null
        }

        object Factory : DataSource.Factory {
            override fun createDataSource(): DataSource = OfflineCacheMissDataSource()
        }
    }

    fun notificationHelper(context: Context): DownloadNotificationHelper {
        get(context)
        return notificationHelper!!
    }

    fun setMaxParallel(context: Context, max: Int) {
        get(context).maxParallelDownloads = max.coerceIn(1, 4)
    }

    /**
     * Pause work, tear down SimpleCache/DownloadManager, and rebuild against the
     * current [DownloadStorage.cacheDir]. Call after changing storage location.
     */
    fun relocate(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            runCatching {
                downloadManager?.pauseDownloads()
            }
            release()
            get(app)
        }
    }

    fun release() {
        synchronized(this) {
            downloadManager?.release()
            downloadManager = null
            simpleCache?.release()
            simpleCache = null
            dataSourceFactory = null
            notificationHelper = null
            databaseProvider = null
        }
    }
}
