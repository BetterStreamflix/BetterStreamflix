package com.dskja.betterstreamflix.extractors

import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.NetworkClient
import com.dskja.betterstreamflix.utils.withExtractorTimeouts
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.lang.Exception

class PDrainExtractor : Extractor() {

    override val name = "PDrain"
    override val mainUrl = "https://pixeldrain.com"

    override suspend fun extract(link: String): Video {
        try {
            val id = link.substringAfter("/u/").substringBefore("?")
            val videoSource = "$mainUrl/api/file/$id"

            val contentType = withContext(Dispatchers.IO) {
                val client = NetworkClient.default.newBuilder()
                    .withExtractorTimeouts()
                    .build()
                val request = Request.Builder()
                    .url(videoSource)
                    .head()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", link)
                    .build()
                runCatching {
                    client.newCall(request).execute().use { resp ->
                        resp.header("Content-Type").orEmpty()
                    }
                }.getOrDefault("")
            }

            val mime = when {
                contentType.contains("mpegurl", ignoreCase = true) ||
                    contentType.contains("m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                contentType.contains("mp4", ignoreCase = true) ||
                    contentType.contains("video/", ignoreCase = true) -> MimeTypes.VIDEO_MP4
                contentType.isBlank() -> MimeTypes.VIDEO_MP4 // unknown — legacy default
                else -> null // let player sniff from URL/extension
            }

            return Video(
                source = videoSource,
                type = mime,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to link,
                    "Origin" to mainUrl,
                    "Connection" to "keep-alive"
                )
            )

        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Exception("PDrainExtractor failed: ${e.message}", e)
        }
    }
}
