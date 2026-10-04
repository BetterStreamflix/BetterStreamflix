package com.dskja.betterstreamflix.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.offline.Download
import com.dskja.betterstreamflix.extractors.StreamMime
import com.dskja.betterstreamflix.models.Video
import org.json.JSONArray
import java.io.File

object OfflinePlayback {
    private const val TAG = "OfflinePlayback"
    fun contentKeyFor(videoType: Video.Type, providerName: String): String {
        return when (videoType) {
            is Video.Type.Movie -> DownloadContentKey.movie(providerName, videoType.id)
            is Video.Type.Episode -> DownloadContentKey.episode(
                providerName = providerName,
                tvShowId = videoType.tvShow.id,
                seasonNumber = videoType.season.number,
                episodeNumber = videoType.number,
                episodeId = videoType.id,
            )
        }
    }

    suspend fun findCompleted(context: Context, videoType: Video.Type): DownloadItemEntity? {
        val providerName = videoType.let {
            // Prefer tagged provider from existing download by id match later
            com.dskja.betterstreamflix.utils.UserPreferences.currentProvider?.name
        } ?: return null
        val key = contentKeyFor(videoType, providerName)
        val item = DownloadRepository.get(context).getByContentKey(key)
            ?: return null
        return item.takeIf { it.state == DownloadItemState.COMPLETED.name }
    }

    suspend fun findCompletedAnyProvider(context: Context, videoType: Video.Type): DownloadItemEntity? {
        val repo = DownloadRepository.get(context)
        val all = repo.getAllOnce().filter { it.state == DownloadItemState.COMPLETED.name }
        return when (videoType) {
            is Video.Type.Movie -> all.firstOrNull {
                it.kind == DownloadKind.MOVIE.name &&
                    (
                        it.contentKey == DownloadContentKey.movie(it.providerName, videoType.id) ||
                            videoTypeIdEquals(it.videoTypeJson, videoType.id)
                        )
            }
            is Video.Type.Episode -> all.firstOrNull {
                it.kind == DownloadKind.EPISODE.name &&
                    (
                        it.contentKey == DownloadContentKey.episode(
                            it.providerName,
                            videoType.tvShow.id,
                            videoType.season.number,
                            videoType.number,
                            videoType.id,
                        ) || videoTypeIdEquals(it.videoTypeJson, videoType.id)
                        )
            }
        }
    }

    private fun videoTypeIdEquals(videoTypeJson: String, id: String): Boolean {
        if (videoTypeJson.isBlank() || id.isBlank()) return false
        return runCatching {
            org.json.JSONObject(videoTypeJson).optString("id") == id
        }.getOrDefault(false)
    }

    fun buildLocalVideo(context: Context, item: DownloadItemEntity): Video? {
        if (item.state != DownloadItemState.COMPLETED.name) return null
        val unavailable = runCatching {
            DownloadStorage.storageUnavailableReason(context)
        }.getOrNull()
        if (unavailable != null) {
            runCatching {
                Log.w(TAG, "Offline storage unavailable: ${context.getString(unavailable)}")
            }
            return null
        }
        val cacheRoot = runCatching { DownloadStorage.cacheDir(context) }.getOrNull()
        if (cacheRoot == null || !cacheRoot.exists() || !cacheRoot.canRead()) {
            runCatching { Log.w(TAG, "Offline cache dir missing: ${cacheRoot?.absolutePath}") }
            return null
        }
        val media3Id = item.media3Id
        val download = runCatching {
            StreamflixDownloadManager.get(context).downloadIndex.getDownload(media3Id)
        }.getOrNull()
        // Only treat Media3-completed downloads with cached bytes as offline playable.
        // Keep source = request.uri so CacheDataSource keys match; never treat https as a
        // local share path (see [exportShareUri]).
        val hasCachedContent = download != null &&
            download.state == Download.STATE_COMPLETED &&
            (download.bytesDownloaded > 0L || item.bytesDownloaded > 0L)
        val source = when {
            hasCachedContent -> download!!.request.uri.toString()
            item.localUri.isNotBlank() &&
                !item.localUri.startsWith("http://", ignoreCase = true) &&
                !item.localUri.startsWith("https://", ignoreCase = true) -> item.localUri
            else -> return null
        }
        val headers = runCatching {
            val o = org.json.JSONObject(item.headersJson.ifBlank { "{}" })
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())

        val langByPath = runCatching {
            val urls = SubtitleFetch.decodeUrls(item.subtitleUrlsJson)
            val paths = JSONArray(item.subtitlePathsJson.ifBlank { "[]" })
            buildMap {
                for (i in 0 until paths.length()) {
                    val path = paths.optString(i)
                    if (path.isBlank()) continue
                    val lang = urls.getOrNull(i)?.first
                        ?: File(path).nameWithoutExtension
                            .substringAfter('_', missingDelimiterValue = "")
                            .ifBlank { File(path).nameWithoutExtension }
                    put(path, lang)
                }
            }
        }.getOrDefault(emptyMap())

        val subs = runCatching {
            val arr = JSONArray(item.subtitlePathsJson.ifBlank { "[]" })
            buildList {
                for (i in 0 until arr.length()) {
                    val path = arr.optString(i)
                    if (path.isBlank()) continue
                    add(
                        Video.Subtitle(
                            label = langByPath[path]
                                ?: File(path).nameWithoutExtension
                                    .substringAfter('_', missingDelimiterValue = "")
                                    .ifBlank { File(path).nameWithoutExtension },
                            file = path,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())

        return Video(
            source = source,
            headers = headers.ifEmpty { null },
            type = item.mimeType.ifBlank { null }
                ?: download?.request?.mimeType?.takeIf { it.isNotBlank() },
            subtitles = subs,
            // Only stamp Media3 id when the download index has completed bytes —
            // players use this to rebuild MediaItem with streamKeys/customCacheKey.
            offlineMedia3Id = media3Id.takeIf { hasCachedContent && it.isNotBlank() },
        )
    }

    /**
     * Build the Exo [MediaItem] for offline playback.
     *
     * Critical: use [DownloadRequest.toMediaItem] so HLS/DASH [StreamKey]s and the custom
     * cache key match what was downloaded. A plain URI MediaItem re-reads the master
     * playlist, picks a non-downloaded variant, misses cache, and hits the expired CDN
     * (HTTP 404).
     */
    fun buildOfflineMediaItem(
        context: Context,
        video: Video,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration>,
        mediaMetadata: MediaMetadata? = null,
    ): MediaItem {
        val media3Id = video.offlineMedia3Id?.takeIf { it.isNotBlank() }
        if (media3Id != null) {
            val download = runCatching {
                StreamflixDownloadManager.get(context).downloadIndex.getDownload(media3Id)
            }.getOrNull()
            if (download != null &&
                download.state == Download.STATE_COMPLETED &&
                (download.bytesDownloaded > 0L)
            ) {
                val builder = download.request.toMediaItem().buildUpon()
                    .setSubtitleConfigurations(subtitleConfigurations)
                if (mediaMetadata != null) {
                    builder.setMediaMetadata(mediaMetadata)
                }
                // Prefer stored mime when Media3 request omitted it.
                if (download.request.mimeType.isNullOrBlank() && !video.type.isNullOrBlank()) {
                    builder.setMimeType(video.type)
                }
                return builder.build()
            }
        }
        val builder = MediaItem.Builder()
            .setUri(Uri.parse(video.source))
            .setMimeType(StreamMime.coalesce(video.type, video.source))
            .setSubtitleConfigurations(subtitleConfigurations)
        if (mediaMetadata != null) {
            builder.setMediaMetadata(mediaMetadata)
        }
        return builder.build()
    }

    /**
     * Share/export for external players.
     * 1) Real on-disk paths in [DownloadItemEntity.localUri]
     * 2) Progressive Media3 cache materialization (mp4/webm/mkv)
     * HLS/DASH stay null — callers show cache-only copy.
     */
    fun exportShareUri(context: Context, item: DownloadItemEntity): Uri? {
        fileFromLocalUri(item.localUri)?.let { file ->
            return runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file,
                )
            }.getOrNull()
        }
        val exported = runCatching {
            StreamflixDownloadManager.exportProgressiveShareFile(
                context = context,
                media3Id = item.media3Id,
                mimeHint = item.mimeType,
                streamUrl = item.streamUrl,
            )
        }.getOrNull() ?: return null
        return runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                exported,
            )
        }.getOrNull()
    }

    private fun fileFromLocalUri(path: String): File? {
        if (path.isBlank()) return null
        if (path.startsWith("http://", ignoreCase = true) ||
            path.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        val file = when {
            path.startsWith("file:") -> File(Uri.parse(path).path ?: return null)
            path.startsWith("/") -> File(path)
            else -> return null
        }
        return file.takeIf { it.exists() && it.canRead() }
    }
}
