package com.dskja.betterstreamflix.download

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.dskja.betterstreamflix.fragments.downloads.OfflineVideoCache
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DownloadRepository private constructor(
    private val context: Context,
    private val dao: DownloadDao,
) {
    private val enqueueLocks = ConcurrentHashMap<String, Mutex>()
    fun observeAll(): Flow<List<DownloadItemEntity>> = dao.observeAll()

    /** Downloads list for the active profile (legacy rows only on default). */
    fun observeForActiveProfile(): Flow<List<DownloadItemEntity>> =
        dao.observeForProfile(DownloadProfileScope.activeId())

    /**
     * Completed download item contentKeys plus completed season-pack ids
     * ([DownloadSeasonPackEntity.id] / [DownloadContentKey.seasonPack]),
     * scoped to the active profile.
     */
    fun observeCompletedKeys(): Flow<Set<String>> =
        combine(
            dao.observeCompletedKeysForProfile(DownloadProfileScope.activeId()).map { it.toSet() },
            dao.observeSeasonPacks().map { packs ->
                packs.filter { it.state == DownloadItemState.COMPLETED.name }
                    .map { it.id }
                    .toSet()
            },
        ) { itemKeys, packKeys -> itemKeys + packKeys }

    fun observeSeasonPacks(): Flow<List<DownloadSeasonPackEntity>> = dao.observeSeasonPacks()

    suspend fun getAllOnce(): List<DownloadItemEntity> = dao.observeAll().first()

    suspend fun getById(id: String) = dao.getById(id)

    /** Lookup for the active profile (includes legacy empty profileId on default). */
    suspend fun getByContentKey(contentKey: String) =
        dao.getByContentKeyForProfile(contentKey, DownloadProfileScope.activeId())

    suspend fun getByMedia3Id(media3Id: String) = dao.getByMedia3Id(media3Id)

    suspend fun completedKeys(): Set<String> {
        val itemKeys = dao.completedKeysForProfile(DownloadProfileScope.activeId()).toSet()
        val packKeys = dao.observeSeasonPacks().first()
            .filter { it.state == DownloadItemState.COMPLETED.name }
            .map { it.id }
            .toSet()
        return itemKeys + packKeys
    }

    suspend fun upsert(item: DownloadItemEntity) = dao.upsert(item)

    suspend fun updateProgress(
        id: String,
        state: DownloadItemState,
        bytesDownloaded: Long,
        contentLength: Long,
        progressPct: Int,
        speedBytesPerSec: Long,
        etaSeconds: Long,
        localUri: String,
        errorCode: String,
        errorMessage: String,
    ) {
        val current = dao.getById(id) ?: return
        dao.upsert(
            current.copy(
                state = state.name,
                bytesDownloaded = bytesDownloaded,
                contentLength = contentLength,
                progressPct = progressPct,
                speedBytesPerSec = speedBytesPerSec,
                etaSeconds = etaSeconds,
                localUri = localUri,
                errorCode = errorCode,
                errorMessage = errorMessage,
                updatedAt = System.currentTimeMillis(),
                completedAt = if (state == DownloadItemState.COMPLETED) {
                    System.currentTimeMillis()
                } else {
                    current.completedAt
                },
            ),
        )
    }

    suspend fun markFailed(id: String, code: DownloadErrorCode, message: String) {
        val current = dao.getById(id) ?: return
        dao.upsert(
            current.copy(
                state = DownloadItemState.FAILED.name,
                errorCode = code.name,
                errorMessage = message,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun enqueueResolved(
        contentKey: String,
        providerName: String,
        kind: DownloadKind,
        title: String,
        subtitle: String,
        posterUrl: String,
        videoTypeJson: String,
        serverName: String,
        serverId: String,
        qualityLabel: String,
        mimeType: String?,
        streamUrl: String,
        headers: Map<String, String>,
        seasonPackId: String? = null,
        sortIndex: Int = 0,
        streamKeys: List<StreamKey> = emptyList(),
        subtitleUrls: List<Pair<String, String>> = emptyList(),
        smartEnqueued: Boolean = false,
    ): DownloadItemEntity {
        val profileId = DownloadProfileScope.activeId()
        return enqueueLocks.computeIfAbsent("$contentKey|$profileId") { Mutex() }.withLock {
            val existing = dao.getByContentKeyForProfile(contentKey, profileId)
            if (existing != null && existing.state == DownloadItemState.COMPLETED.name) {
                return@withLock existing
            }
            if (existing != null && DownloadItemState.fromKey(existing.state).isActive) {
                return@withLock existing
            }

            val id = existing?.id ?: UUID.randomUUID().toString()
            val media3Id = existing?.media3Id?.takeIf { it.isNotBlank() } ?: id
            val headersJson = JSONObject(headers as Map<*, *>).toString()

            DownloadHeaderStore.put(context, media3Id, headers)
            DownloadHeaderStore.putForUrl(context, streamUrl, headers, media3Id = media3Id)
            StreamflixDownloadManager.dataSourceFactory(context).apply {
                activeMedia3Id = media3Id
                activeHeaders = headers
            }

            val mime = mimeType?.takeIf { it.isNotBlank() }
                ?: when {
                    streamUrl.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                    streamUrl.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                    streamUrl.contains(".mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
                    else -> null
                }

            val requestBuilder = DownloadRequest.Builder(media3Id, Uri.parse(streamUrl))
                .setMimeType(mime)
                .setData(title.toByteArray(Charsets.UTF_8))
            if (streamKeys.isNotEmpty()) {
                requestBuilder.setStreamKeys(streamKeys)
            }
            // Media3 throws "customCacheKey must be null for type: N" when the
            // inferred type is adaptive (HLS/DASH/SS) — only set it for progressive.
            if (isProgressiveRequest(streamUrl)) {
                requestBuilder.setCustomCacheKey(media3Id)
            }
            val request = requestBuilder.build()

            val entity = DownloadItemEntity(
                id = id,
                contentKey = contentKey,
                media3Id = media3Id,
                providerName = providerName,
                kind = kind.name,
                title = title,
                subtitle = subtitle,
                posterUrl = posterUrl,
                videoTypeJson = videoTypeJson,
                serverName = serverName,
                serverId = serverId,
                qualityLabel = qualityLabel,
                mimeType = mime.orEmpty(),
                state = DownloadItemState.QUEUED.name,
                streamUrl = streamUrl,
                headersJson = headersJson,
                streamKeysJson = serializeStreamKeys(streamKeys),
                seasonPackId = seasonPackId,
                sortIndex = sortIndex,
                subtitleUrlsJson = serializeSubtitleUrls(subtitleUrls),
                smartEnqueued = smartEnqueued,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                profileId = existing?.profileId?.takeIf { it.isNotBlank() } ?: profileId,
            )
            dao.upsert(entity)
            runCatching { DownloadSeriesFolders.ensureForItem(context, entity) }

            val dm = StreamflixDownloadManager.get(context)
            dm.maxParallelDownloads = UserPreferences.downloadMaxConcurrent.coerceIn(1, 4)
            DownloadService.sendAddDownload(
                context,
                StreamflixDownloadService::class.java,
                request,
                /* foreground= */ true,
            )
            // Clear enqueue-time hint so it does not linger process-wide (DL-P3-N).
            StreamflixDownloadManager.dataSourceFactory(context).apply {
                if (activeMedia3Id == media3Id) {
                    activeMedia3Id = null
                    activeHeaders = emptyMap()
                }
            }
            DownloadLaneScheduler.applyAsync(context)
            entity
        }
    }

    /**
     * Turn dead COMPLETED rows (cache wiped / storage relocated) into clear
     * [DownloadErrorCode.STORAGE_RELOCATED] failures so the Downloads tab shows
     * "Re-download needed" instead of unplayable Completed entries.
     */
    suspend fun reconcileOrphanedCompleted() {
        val now = System.currentTimeMillis()
        // Grace: skip freshly completed rows (cache may still be warming — OS-052).
        val graceMs = 90_000L
        getAllOnce()
            .filter { DownloadProfileScope.matchesActive(it.profileId) }
            .filter { it.state == DownloadItemState.COMPLETED.name }
            .forEach { item ->
                val touch = maxOf(item.completedAt, item.updatedAt, item.createdAt)
                if (touch > 0L && now - touch < graceMs) return@forEach
                val playable = runCatching {
                    OfflinePlayback.canPlayOffline(context, item)
                }.getOrDefault(false)
                if (!playable) {
                    // Second probe after a short yield reduces false positives on cold start.
                    kotlinx.coroutines.delay(120L)
                    val stillDead = !runCatching {
                        OfflinePlayback.canPlayOffline(context, item)
                    }.getOrDefault(false)
                    if (!stillDead) return@forEach
                    markFailed(
                        item.id,
                        DownloadErrorCode.STORAGE_RELOCATED,
                        "Storage location changed — re-download required",
                    )
                }
            }
    }

    suspend fun pause(
        id: String,
        stopReason: Int = DownloadQueuePolicy.STOP_USER,
        foreground: Boolean = false,
    ) {
        val item = dao.getById(id) ?: return
        // Soften FGS pause from background (BOOT / connectivity) — never crash the process.
        runCatching {
            DownloadService.sendSetStopReason(
                context,
                StreamflixDownloadService::class.java,
                item.media3Id,
                stopReason,
                foreground,
            )
        }.onFailure {
            android.util.Log.w("DownloadRepository", "pause sendSetStopReason: ${it.message}")
        }
        val connectivityHold = stopReason == DownloadQueuePolicy.STOP_CONNECTIVITY
        val storageHold = stopReason == DownloadQueuePolicy.STOP_STORAGE
        val laneHold = stopReason == DownloadQueuePolicy.STOP_LANE
        runCatching {
            dao.upsert(
                item.copy(
                    state = DownloadItemState.PAUSED.name,
                    errorCode = when {
                        connectivityHold -> DownloadErrorCode.WIFI_REQUIRED.name
                        storageHold -> DownloadErrorCode.LOW_STORAGE.name
                        laneHold -> DownloadErrorCode.LANE_HOLD.name
                        else -> ""
                    },
                    errorMessage = when {
                        connectivityHold ->
                            context.getString(com.dskja.betterstreamflix.R.string.download_error_wifi)
                        storageHold ->
                            context.getString(com.dskja.betterstreamflix.R.string.download_error_nospace)
                        laneHold ->
                            context.getString(com.dskja.betterstreamflix.R.string.download_error_lane_hold)
                        else -> ""
                    },
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun resume(id: String, foreground: Boolean = false) {
        val item = dao.getById(id) ?: return
        if (DownloadConnectivityMonitor.shouldHoldForWifiOnly(context)) {
            // Soft-gate only: keep the item paused instead of marking a terminal failure.
            dao.upsert(
                item.copy(
                    state = DownloadItemState.PAUSED.name,
                    errorCode = DownloadErrorCode.WIFI_REQUIRED.name,
                    errorMessage = context.getString(com.dskja.betterstreamflix.R.string.download_error_wifi),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            withContext(Dispatchers.Main.immediate) {
                Toast.makeText(
                    context,
                    com.dskja.betterstreamflix.R.string.download_error_wifi,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }
        if (DownloadStorage.isLowSpace(context)) {
            dao.upsert(
                item.copy(
                    state = DownloadItemState.PAUSED.name,
                    errorCode = DownloadErrorCode.LOW_STORAGE.name,
                    errorMessage = context.getString(com.dskja.betterstreamflix.R.string.download_error_nospace),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            withContext(Dispatchers.Main.immediate) {
                Toast.makeText(
                    context,
                    com.dskja.betterstreamflix.R.string.downloads_low_space,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }
        // Media3 STATE_FAILED ignores stop-reason clears — re-add the request so
        // transient failures (network blips) can continue without a full re-resolve.
        // Adaptive / empty streamKeys / storage-relocated rows need a fresh resolve
        // via retryItem (notification Retry path) — do not requeue a stale HLS URL.
        if (item.state == DownloadItemState.FAILED.name) {
            if (shouldRetryViaReResolve(item)) {
                DownloadController.retryItem(context, item)
                DownloadNotifier.cancelFailed(context, item.id)
                return
            }
            requeueFailedMedia3(item)
        } else {
            DownloadService.sendSetStopReason(
                context,
                StreamflixDownloadService::class.java,
                item.media3Id,
                Download.STOP_REASON_NONE,
                foreground,
            )
        }
        dao.upsert(
            item.copy(
                state = DownloadItemState.QUEUED.name,
                errorCode = "",
                errorMessage = "",
                updatedAt = System.currentTimeMillis(),
            ),
        )
        DownloadNotifier.cancelFailed(context, item.id)
        DownloadLaneScheduler.applyAsync(context)
    }

    private fun shouldRetryViaReResolve(item: DownloadItemEntity): Boolean {
        if (item.errorCode == DownloadErrorCode.STORAGE_RELOCATED.name) return true
        if (item.errorCode == DownloadErrorCode.EXPIRED.name) return true
        val adaptive = isAdaptiveMimeOrUrl(item.mimeType, item.streamUrl)
        val keys = item.streamKeysJson.trim()
        if (adaptive && (keys.isEmpty() || keys == "[]")) return true
        return adaptive && item.errorCode in setOf(
            DownloadErrorCode.NETWORK.name,
            DownloadErrorCode.UNKNOWN.name,
            DownloadErrorCode.NOT_FOUND.name,
            "",
        )
    }

    /** Mirrors Media3's DownloadRequest type inference: only progressive URLs may carry a customCacheKey. */
    private fun isProgressiveRequest(url: String): Boolean {
        val last = url.lowercase()
            .substringBefore('?').substringBefore('#')
            .substringAfterLast('/')
        return !(last.endsWith(".m3u8") || last.endsWith(".mpd") ||
            last.endsWith(".ism") || last.endsWith(".isml") ||
            last.endsWith("manifest"))
    }

    private fun isAdaptiveMimeOrUrl(mime: String, url: String): Boolean {
        val m = mime.lowercase()
        val u = url.lowercase()
        return m.contains("mpegurl") ||
            m.contains("dash+xml") ||
            m.contains("x-mpegurl") ||
            u.contains(".m3u8") ||
            u.contains(".mpd")
    }

    /**
     * Re-enqueue a Media3 download that is in STATE_FAILED (or missing from the index).
     * Uses the stored request URI/mime/headers — callers that need fresh signed URLs
     * should use [DownloadController.retryItem] instead.
     */
    private fun requeueFailedMedia3(item: DownloadItemEntity) {
        val dm = StreamflixDownloadManager.get(context)
        val existing = runCatching { dm.downloadIndex.getDownload(item.media3Id) }.getOrNull()
        val headers = runCatching {
            val o = org.json.JSONObject(item.headersJson.ifBlank { "{}" })
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        // Always rehydrate HeaderStore before sendAddDownload — even when the
        // Media3 index still has the request (segment host maps are in-memory).
        if (headers.isNotEmpty()) {
            DownloadHeaderStore.put(context, item.media3Id, headers)
            if (item.streamUrl.isNotBlank()) {
                DownloadHeaderStore.putForUrl(
                    context,
                    item.streamUrl,
                    headers,
                    media3Id = item.media3Id,
                )
            }
        }
        val request = existing?.request ?: run {
            if (item.streamUrl.isBlank()) return
            val mime = item.mimeType.takeIf { it.isNotBlank() }
                ?: when {
                    item.streamUrl.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                    item.streamUrl.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                    item.streamUrl.contains(".mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
                    else -> null
                }
            val requestBuilder = DownloadRequest.Builder(item.media3Id, Uri.parse(item.streamUrl))
                .setMimeType(mime)
                .setData(item.title.toByteArray(Charsets.UTF_8))
            val streamKeys = deserializeStreamKeys(item.streamKeysJson)
            if (streamKeys.isNotEmpty()) {
                requestBuilder.setStreamKeys(streamKeys)
            }
            if (isProgressiveRequest(item.streamUrl)) {
                requestBuilder.setCustomCacheKey(item.media3Id)
            }
            requestBuilder.build()
        }
        // Re-add (Media3 replaces an existing failed entry with the same id and
        // keeps already-cached bytes when the URI matches).
        DownloadService.sendAddDownload(
            context,
            StreamflixDownloadService::class.java,
            request,
            /* foreground= */ true,
        )
    }

    suspend fun remove(id: String) {
        val item = dao.getById(id) ?: return
        DownloadService.sendRemoveDownload(
            context,
            StreamflixDownloadService::class.java,
            item.media3Id,
            false,
        )
        DownloadHeaderStore.remove(context, item.media3Id)
        DownloadHeaderStore.removeForUrl(context, item.streamUrl)
        DownloadStorage.deleteQuietly(DownloadStorage.subsDir(context, item.contentKey))
        dao.deleteById(id)
        OfflineVideoCache.remove(item.contentKey)
        item.seasonPackId?.let { refreshSeasonPack(it) }
    }

    suspend fun pauseAll() {
        getAllOnce()
            .filter { DownloadItemState.fromKey(it.state).isActive }
            .forEach { pause(it.id, DownloadQueuePolicy.STOP_USER) }
    }

    /** Wi-Fi-only hold. Leaves rows the user already paused alone. */
    suspend fun pauseForConnectivity() {
        getAllOnce()
            .filter { DownloadQueuePolicy.shouldPauseForConnectivity(DownloadItemState.fromKey(it.state)) }
            .forEach { item ->
                runCatching {
                    pause(item.id, DownloadQueuePolicy.STOP_CONNECTIVITY, foreground = true)
                }.onFailure { Log.w(TAG, "Connectivity pause skipped: ${it.message}") }
            }
    }

    /** Low-storage hold. Leaves user-paused and Wi‑Fi-held rows alone. */
    suspend fun pauseForLowStorage() {
        getAllOnce()
            .filter { DownloadQueuePolicy.shouldPauseForStorage(DownloadItemState.fromKey(it.state)) }
            .forEach { item ->
                runCatching {
                    pause(item.id, DownloadQueuePolicy.STOP_STORAGE, foreground = true)
                }.onFailure { Log.w(TAG, "Storage pause skipped: ${it.message}") }
            }
    }

    /**
     * Resume only connectivity holds. Per-id stop-reason clear — never
     * [DownloadService.sendResumeDownloads], which would also restart a user pause.
     */
    suspend fun resumeConnectivityHeld() {
        if (DownloadConnectivityMonitor.shouldHoldForWifiOnly(context)) return
        if (DownloadStorage.isLowSpace(context)) return
        val held = getAllOnce().filter {
            val state = DownloadItemState.fromKey(it.state)
            DownloadQueuePolicy.shouldResumeAfterConnectivity(
                stopReason = if (it.errorCode == DownloadErrorCode.WIFI_REQUIRED.name) {
                    DownloadQueuePolicy.STOP_CONNECTIVITY
                } else {
                    DownloadQueuePolicy.STOP_USER
                },
                errorCode = it.errorCode,
            ) && (state == DownloadItemState.PAUSED || state == DownloadItemState.FAILED)
        }
        held.forEach { item ->
            runCatching { resume(item.id, foreground = true) }
                .onFailure { Log.w(TAG, "Connectivity resume skipped: ${it.message}") }
        }
        DownloadLaneScheduler.apply(context)
    }

    /** Resume only low-storage holds once free space / soft-limit recovers. */
    suspend fun resumeStorageHeld() {
        if (DownloadStorage.isLowSpace(context)) return
        val held = getAllOnce().filter {
            val state = DownloadItemState.fromKey(it.state)
            DownloadQueuePolicy.shouldResumeAfterStorage(
                stopReason = if (it.errorCode == DownloadErrorCode.LOW_STORAGE.name) {
                    DownloadQueuePolicy.STOP_STORAGE
                } else {
                    DownloadQueuePolicy.STOP_USER
                },
                errorCode = it.errorCode,
            ) && (state == DownloadItemState.PAUSED || state == DownloadItemState.FAILED)
        }
        held.forEach { item ->
            runCatching { resume(item.id, foreground = true) }
                .onFailure { Log.w(TAG, "Storage resume skipped: ${it.message}") }
        }
        // Space recovered — also unblock Wi‑Fi holds deferred while storage was full.
        resumeConnectivityHeld()
        DownloadLaneScheduler.apply(context)
    }

    suspend fun resumeAll() {
        if (DownloadConnectivityMonitor.shouldHoldForWifiOnly(context)) return
        if (DownloadStorage.isLowSpace(context)) return
        // Connectivity watcher can fire while the app is backgrounded (Android 12+),
        // which throws BackgroundServiceStartNotAllowedException / IllegalStateException.
        // Prefer a foreground start; if that is still blocked, skip silently until next
        // foreground resume (notification action / Downloads tab).
        if (!sendResumeDownloads()) return
        // Only resume paused rows. Hard failures (DRM/network/etc.) stay failed until manual retry.
        getAllOnce()
            .filter {
                it.state == DownloadItemState.PAUSED.name ||
                    (
                        it.state == DownloadItemState.FAILED.name &&
                            (
                                it.errorCode == DownloadErrorCode.WIFI_REQUIRED.name ||
                                    it.errorCode == DownloadErrorCode.LOW_STORAGE.name
                                )
                        )
            }
            .forEach { resume(it.id) }
        DownloadLaneScheduler.apply(context)
    }

    suspend fun clearCompleted() {
        getAllOnce()
            .filter { it.state == DownloadItemState.COMPLETED.name }
            .forEach { remove(it.id) }
    }

    /** Remove only downloads that were marked watched (Smart-Downloads cleanup). */
    suspend fun clearWatched() {
        getAllOnce()
            .filter { it.state == DownloadItemState.COMPLETED.name && it.watchedOffline }
            .forEach { remove(it.id) }
    }

    /**
     * Re-queue every FAILED item with its stored Media3 request. Cheap retry for
     * transient failures — items whose URLs expired will fail again and keep
     * their FAILED state until a full in-app re-resolve.
     */
    suspend fun resumeFailed() {
        if (DownloadConnectivityMonitor.shouldHoldForWifiOnly(context)) return
        getAllOnce()
            .filter { it.state == DownloadItemState.FAILED.name }
            .forEach { resume(it.id) }
    }

    suspend fun pausePack(packId: String) {
        dao.itemsForPack(packId)
            .filter { DownloadItemState.fromKey(it.state).isActive }
            .forEach { pause(it.id) }
    }

    suspend fun resumePack(packId: String) {
        dao.itemsForPack(packId)
            .filter {
                it.state == DownloadItemState.PAUSED.name ||
                    it.state == DownloadItemState.FAILED.name
            }
            .forEach { resume(it.id) }
    }

    suspend fun removePack(packId: String) {
        dao.itemsForPack(packId).forEach { remove(it.id) }
        dao.deleteSeasonPack(packId)
    }

    suspend fun clearFailed() {
        getAllOnce()
            .filter { it.state == DownloadItemState.FAILED.name }
            .forEach { remove(it.id) }
    }

    /** Subtitle entries are stored as JSON array of {"lang": "...", "url": "..."}. */
    private fun serializeSubtitleUrls(subtitles: List<Pair<String, String>>): String {
        if (subtitles.isEmpty()) return "[]"
        val arr = JSONArray()
        subtitles.forEach { (lang, url) ->
            arr.put(JSONObject().put("lang", lang).put("url", url))
        }
        return arr.toString()
    }

    /** Media3 [StreamKey] list as JSON array of {"p","g","t"}. */
    private fun serializeStreamKeys(keys: List<StreamKey>): String {
        if (keys.isEmpty()) return "[]"
        val arr = JSONArray()
        keys.forEach { key ->
            arr.put(
                JSONObject()
                    .put("p", key.periodIndex)
                    .put("g", key.groupIndex)
                    .put("t", key.streamIndex),
            )
        }
        return arr.toString()
    }

    private fun deserializeStreamKeys(raw: String): List<StreamKey> {
        if (raw.isBlank() || raw == "[]") return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(StreamKey(o.getInt("p"), o.getInt("g"), o.getInt("t")))
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun clearAll() {
        getAllOnce().forEach { remove(it.id) }
        dao.deleteAll()
        DownloadHeaderStore.clear(context)
        OfflineVideoCache.clear()
    }

    suspend fun assignPack(id: String, packId: String, sortIndex: Int) {
        dao.assignPack(id, packId, sortIndex)
    }

    private fun sendResumeDownloads(): Boolean {
        return runCatching {
            DownloadService.sendResumeDownloads(
                context,
                StreamflixDownloadService::class.java,
                /* foreground= */ true,
            )
            true
        }.recoverCatching { first ->
            Log.w(TAG, "Foreground resume blocked (${first.message}); retrying foreground service start")
            runCatching {
                StreamflixDownloadService.start(context)
                DownloadService.sendResumeDownloads(
                    context,
                    StreamflixDownloadService::class.java,
                    /* foreground= */ true,
                )
                true
            }.getOrElse { second ->
                Log.w(TAG, "Skipping resume while backgrounded: ${second.message}")
                false
            }
        }.getOrDefault(false)
    }

    suspend fun upsertSeasonPack(pack: DownloadSeasonPackEntity) {
        val stamped = if (pack.profileId.isBlank()) {
            pack.copy(profileId = DownloadProfileScope.activeId())
        } else {
            pack
        }
        dao.upsertSeasonPack(stamped)
    }

    suspend fun getSeasonPack(id: String) = dao.getSeasonPack(id)

    /**
     * When several per-episode downloads for the same show/season exist without a pack,
     * create one cheaply so Controls Packs can share them (OS-054).
     */
    suspend fun maybeUpsertOpportunisticSeasonPack(item: DownloadItemEntity) {
        if (item.kind != DownloadKind.EPISODE.name) return
        if (!item.seasonPackId.isNullOrBlank()) return
        val parts = item.contentKey.split('|')
        if (parts.size < 6 || parts[0] != "episode") return
        val provider = parts[1].trim()
        val showId = parts[2].trim()
        val season = parts[3].toIntOrNull() ?: return
        if (provider.isBlank() || showId.isBlank()) return
        val packId = DownloadContentKey.seasonPack(provider, showId, season)
        if (dao.getSeasonPack(packId) != null) return
        val siblings = getAllOnce()
            .filter { DownloadProfileScope.matchesActive(it.profileId) }
            .filter { it.kind == DownloadKind.EPISODE.name }
            .filter { row ->
                val p = row.contentKey.split('|')
                p.size >= 6 &&
                    p[0] == "episode" &&
                    p[1] == provider &&
                    p[2] == showId &&
                    p[3].toIntOrNull() == season
            }
        val present = siblings.filter { row ->
            val state = DownloadItemState.fromKey(row.state)
            state.isActive ||
                row.state == DownloadItemState.COMPLETED.name ||
                row.state == DownloadItemState.PAUSED.name
        }
        if (present.size < 2) return
        val title = runCatching {
            org.json.JSONObject(item.videoTypeJson.ifBlank { "{}" }).optString("showTitle")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: item.title
        val poster = runCatching {
            org.json.JSONObject(item.videoTypeJson.ifBlank { "{}" }).optString("showPoster")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: item.posterUrl
        upsertSeasonPack(
            DownloadSeasonPackEntity(
                id = packId,
                providerName = provider,
                tvShowId = showId,
                tvShowTitle = title,
                seasonNumber = season,
                posterUrl = poster,
                totalEpisodes = present.size,
                profileId = DownloadProfileScope.activeId(),
            ),
        )
        present.sortedBy { it.sortIndex }.forEachIndexed { index, row ->
            if (row.seasonPackId.isNullOrBlank()) {
                assignPack(row.id, packId, index)
            }
        }
        refreshSeasonPack(packId)
    }

    suspend fun refreshSeasonPack(packId: String) {
        val pack = dao.getSeasonPack(packId) ?: return
        val items = dao.itemsForPack(packId)
        if (items.isEmpty()) {
            dao.deleteSeasonPack(packId)
            return
        }
        val completed = items.count { it.state == DownloadItemState.COMPLETED.name }
        val failed = items.count { it.state == DownloadItemState.FAILED.name }
        val active = items.any { DownloadItemState.fromKey(it.state).isActive }
        val state = when {
            items.isNotEmpty() && completed == items.size -> DownloadItemState.COMPLETED
            items.isNotEmpty() && failed == items.size -> DownloadItemState.FAILED
            active -> DownloadItemState.DOWNLOADING
            else -> DownloadItemState.QUEUED
        }
        dao.upsertSeasonPack(
            pack.copy(
                completedEpisodes = completed,
                failedEpisodes = failed,
                totalEpisodes = items.size,
                state = state.name,
                updatedAt = System.currentTimeMillis(),
                profileId = pack.profileId.ifBlank { DownloadProfileScope.activeId() },
            ),
        )
    }

    companion object {
        private const val TAG = "DownloadRepository"

        @Volatile
        private var instance: DownloadRepository? = null
        @Volatile
        private var currentProfileId: String? = null

        fun get(context: Context): DownloadRepository {
            val profileId = ProfileManager.activeProfileId
            return instance?.takeIf { currentProfileId == profileId } ?: synchronized(this) {
                instance?.takeIf { currentProfileId == profileId } ?: DownloadRepository(
                    context.applicationContext,
                    DownloadDatabase.get(context).downloadDao(),
                ).also {
                    instance = it
                    currentProfileId = profileId
                }
            }
        }

        fun resetInstance() {
            synchronized(this) {
                instance = null
                currentProfileId = null
            }
        }
    }
}
