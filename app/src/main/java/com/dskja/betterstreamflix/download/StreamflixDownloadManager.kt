package com.dskja.betterstreamflix.download

import android.content.Context
import android.net.Uri
import android.util.Log
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
import androidx.media3.exoplayer.scheduler.Requirements
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

object StreamflixDownloadManager {
    private const val TAG = "StreamflixDownloadMgr"

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

            val opened = openCacheWithRecovery(app)
            val dbProvider = opened.first
            val cache = opened.second
            databaseProvider = dbProvider
            simpleCache = cache

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
                // Mirror app wifi-only: Media3 will not pull on metered when set.
                requirements = media3Requirements()
                addListener(DownloadEventBridge)
            }
            downloadManager = manager
            notificationHelper = DownloadNotificationHelper(app, DownloadNotifier.CHANNEL_ID)
            DownloadEventBridge.attach(app)
            startConnectivityWatcher(app)
            pruneOrphanedSidecarDirs(app)
            // Rebuild header host/url maps synchronously before any resume/FGS so
            // the first HLS segments after process death still get Referer/UA.
            runCatching {
                val items = runBlocking(Dispatchers.IO) {
                    DownloadRepository.get(app).getAllOnce()
                }
                DownloadHeaderStore.rebuildFromRoom(app, items)
            }
            connectivityScope.launch {
                runCatching { DownloadRepository.get(app).reconcileOrphanedCompleted() }
            }
            return manager
        }
    }

    /** Update Media3 network requirements when the wifi-only preference changes. */
    fun applyWifiOnlyRequirements(context: Context) {
        val dm = downloadManager ?: runCatching { get(context) }.getOrNull() ?: return
        dm.requirements = media3Requirements()
    }

    private fun media3Requirements(): Requirements {
        val network = if (UserPreferences.downloadWifiOnly) {
            Requirements.NETWORK_UNMETERED
        } else {
            Requirements.NETWORK
        }
        return Requirements(network)
    }

    /**
     * True when a Media3 COMPLETED download still has spans in the bound
     * [SimpleCache]. Index state alone is not enough after a storage-root flip.
     */
    fun hasPlayableCacheBytes(context: Context, media3Id: String): Boolean {
        if (media3Id.isBlank()) return false
        runCatching { get(context) }
        val cache = simpleCache ?: return false
        if (cache.cacheSpace <= 0L) return false
        val download = runCatching {
            downloadManager?.downloadIndex?.getDownload(media3Id)
        }.getOrNull() ?: return false
        if (download.state != Download.STATE_COMPLETED) return false
        val key = download.request.customCacheKey?.takeIf { it.isNotBlank() }
            ?: download.request.uri.toString()
        if (cache.getCachedSpans(key).isNotEmpty()) return true
        val uriKey = download.request.uri.toString()
        return uriKey != key && cache.getCachedSpans(uriKey).isNotEmpty()
    }

    /**
     * Open SimpleCache; on poison DB / IO failure wipe the cache dir once and retry (SS-10).
     */
    private fun openCacheWithRecovery(
        app: Context,
    ): Pair<StandaloneDatabaseProvider, SimpleCache> {
        val dir = DownloadStorage.cacheDir(app)
        return try {
            val dbProvider = StandaloneDatabaseProvider(app)
            val cache = SimpleCache(dir, NoOpCacheEvictor(), dbProvider)
            dbProvider to cache
        } catch (first: Exception) {
            Log.w(TAG, "SimpleCache open failed; wiping once and retrying: ${first.message}")
            runCatching {
                if (dir.exists()) dir.deleteRecursively()
                dir.mkdirs()
            }
            val dbProvider = StandaloneDatabaseProvider(app)
            val cache = SimpleCache(dir, NoOpCacheEvictor(), dbProvider)
            dbProvider to cache
        }
    }

    /**
     * True when Room has any download row or the Media3 download index still
     * holds entries (cache that would be orphaned by a storage relocate).
     * Also treats a non-empty cache directory (stray files) as blocking.
     */
    fun hasCachedOrQueuedDownloads(context: Context): Boolean {
        val app = context.applicationContext
        val roomNonEmpty = runCatching {
            // Prefer an already-warmed manager path; avoid nested get()+runBlocking
            // deadlocks by probing Room on IO without forcing manager init first.
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                DownloadRepository.get(app).getAllOnce().isNotEmpty()
            }
        }.getOrDefault(false)
        if (roomNonEmpty) return true
        val dm = downloadManager
        if (dm != null) {
            val indexNonEmpty = runCatching {
                dm.downloadIndex.getDownloads().use { cursor -> cursor.moveToFirst() }
            }.getOrDefault(false)
            if (indexNonEmpty) return true
        }
        val cacheDir = runCatching { DownloadStorage.cacheDir(app) }.getOrNull() ?: return false
        return cacheDir.isDirectory &&
            (cacheDir.listFiles()?.any { file ->
                file.isFile && file.length() > 0L ||
                    file.isDirectory && (file.listFiles()?.isNotEmpty() == true)
            } == true)
    }

    private fun startConnectivityWatcher(app: Context) {
        if (connectivityWatcherStarted) return
        connectivityWatcherStarted = true
        connectivityScope.launch {
            DownloadConnectivityMonitor.status
                // Hold rule is meteredness, not transport alone — include isMetered so
                // Wi‑Fi → metered hotspot flips re-evaluate even when type stays WIFI.
                .map { it.type to it.isMetered }
                .distinctUntilChanged()
                .collectLatest { (type, _) ->
                    // Short debounce: long delays let metered transfers start
                    // before wifi-only pause lands (DL-P3-D).
                    delay(150)
                    connectivityActionMutex.withLock {
                        val repo = DownloadRepository.get(app)
                        if (DownloadConnectivityMonitor.shouldHoldForWifiOnly(app)) {
                            // Hold only in-flight work. A user pause keeps its own stop reason.
                            repo.pauseForConnectivity()
                        } else if (type != DownloadNetworkType.NONE) {
                            repo.resumeConnectivityHeld()
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
        maxBytes: Long = 8L * 1024L * 1024L * 1024L,
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
        val shareDir = java.io.File(context.cacheDir, "share").also { it.mkdirs() }
        val out = java.io.File(shareDir, "share_${media3Id.hashCode()}.$ext")
        return runCatching {
            val dataSource = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(OfflineCacheMissDataSource.Factory)
                .setCacheWriteDataSinkFactory(null)
                .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
                .createDataSource()
            val dataSpec = offlineDataSpec(
                uri = download.request.uri,
                cacheKey = download.request.customCacheKey,
            )
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

    /**
     * Open a completed download from the offline cache. [cacheKey] is the
     * DownloadRequest custom cache key and applies only to the primary playlist
     * URI — segments are keyed by their own URLs.
     */
    fun openOfflineSource(context: Context, uri: Uri, cacheKey: String?): DataSource {
        get(context)
        val cache = requireCacheReady()
        val dataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(OfflineCacheMissDataSource.Factory)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
            .createDataSource()
        dataSource.open(offlineDataSpec(uri, cacheKey))
        return dataSource
    }

    private fun offlineDataSpec(uri: Uri, cacheKey: String?): DataSpec {
        val builder = DataSpec.Builder().setUri(uri)
        if (!cacheKey.isNullOrBlank()) builder.setKey(cacheKey)
        return builder.build()
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
     *
     * When downloads already exist, pass [wipeAndRebuild] = true only after the
     * user confirms — otherwise this returns without relocating so Media3/Room
     * state is not silently orphaned.
     */
    fun relocate(
        context: Context,
        wipeAndRebuild: Boolean = false,
        previousDownloadsDir: java.io.File? = null,
    ) {
        val app = context.applicationContext
        if (!wipeAndRebuild && hasCachedOrQueuedDownloads(app)) {
            return
        }
        synchronized(this) {
            if (wipeAndRebuild) {
                runCatching { downloadManager?.pauseDownloads() }
                val dm = downloadManager
                if (dm != null) {
                    runCatching {
                        dm.downloadIndex.getDownloads().use { cursor ->
                            val ids = ArrayList<String>()
                            while (cursor.moveToNext()) {
                                ids.add(cursor.download.request.id)
                            }
                            ids.forEach { id -> runCatching { dm.removeDownload(id) } }
                        }
                    }
                }
                runBlocking(Dispatchers.IO) {
                    val repo = DownloadRepository.get(app)
                    repo.getAllOnce().forEach { item ->
                        val state = DownloadItemState.fromKey(item.state)
                        if (state == DownloadItemState.COMPLETED || state.isActive) {
                            repo.markFailed(
                                item.id,
                                DownloadErrorCode.STORAGE_RELOCATED,
                                "Re-download needed",
                            )
                        }
                    }
                }
                DownloadHeaderStore.clear(app)
                release()
                // Callers must pass the pre-pref-flip downloads root; prefs already
                // point at the new location by the time wipe runs.
                if (previousDownloadsDir != null) {
                    val newRoot = runCatching { DownloadStorage.downloadsDir(app) }.getOrNull()
                    if (newRoot == null ||
                        previousDownloadsDir.absolutePath != newRoot.absolutePath
                    ) {
                        DownloadStorage.deleteQuietly(previousDownloadsDir)
                    }
                }
                get(app)
            } else {
                runCatching { downloadManager?.pauseDownloads() }
                release()
                get(app)
            }
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
