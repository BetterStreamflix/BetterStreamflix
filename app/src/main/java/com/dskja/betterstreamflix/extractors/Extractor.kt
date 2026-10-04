package com.dskja.betterstreamflix.extractors

import android.util.Log
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.MeinecloudEmbedHelper
import kotlinx.coroutines.delay

abstract class Extractor {

    abstract val name: String
    abstract val mainUrl: String
    open val aliasUrls: List<String> = emptyList()
    open val rotatingDomain: List<Regex> = emptyList()

    // THIS is the main method all subclasses must implement
    abstract suspend fun extract(link: String): Video

    // THIS is a convenience helper
    open suspend fun extract(link: String, server: Video.Server? = null): Video {
        return extract(link)
    }

    companion object {
        private val extractors = listOf(
            JKPlayerExtractor(),
            RabbitstreamExtractor(),
            RabbitstreamExtractor.MegacloudExtractor(),
            RabbitstreamExtractor.DokicloudExtractor(),
            RabbitstreamExtractor.PremiumEmbedingExtractor(),
            UpzoneExtractor(),
            StreamhubExtractor(),
            VtubeExtractor(),
            NuuploadExtractor(),
            VoeExtractor(),
            StreamtapeExtractor(),
            VidozaExtractor(),
            VidsrcToExtractor(),
            VidplayExtractor(),
            NekostreamExtractor(),
            FilemoonExtractor(),
            VidplayExtractor.MyCloud(),
            VidplayExtractor.VidplayOnline(),
            MyFileStorageExtractor(),
            MoflixExtractor(),
            MStreamDayExtractor(),
            VidsrcNetExtractor(),
            StreamWishExtractor(),
            StreamWishExtractor.UqloadsXyz(),
            StreamWishExtractor.SwishExtractor(),
            StreamWishExtractor.HlswishExtractor(),
            StreamWishExtractor.PlayerwishExtractor(),
            StreamWishExtractor.SwiftPlayersExtractor(),
            TwoEmbedExtractor(),
            ChillxExtractor(),
            ChillxExtractor.JeanExtractor(),
            MoviesapiExtractor(),
            CloseloadExtractor(),
            LuluVdoExtractor(),
            DoodLaExtractor(),
            DoodLaExtractor.DoodLiExtractor(),
            VidPlyExtractor(),
            MagaSavorExtractor(),
            VidMoLyExtractor(),
            VidMoLyExtractor.ToDomain(),
            VideoSibNetExtractor(),
            SaveFilesExtractor(),
            BigWarpExtractor(),
            DoodLaExtractor.DoodExtractor(),
            LoadXExtractor(),
            VidHideExtractor(),
            VeevExtractor(),
            RidooExtractor(),
            USTRExtractor(),
            VidGuardExtractor(),
            OkruExtractor(),
            StreamSBExtractor(),
            Mp4UploadExtractor(),
            StreamlareExtractor(),
            NinjaStreamExtractor(),
            UchExtractor(),
            VixSrcExtractor(),
            GoodstreamExtractor(),
            LamovieExtractor(),
            UqloadExtractor(),
            MailRuExtractor(),
            MixDropExtractor(),
            SupervideoExtractor(),
            DroploadExtractor(),
            RpmvidExtractor(),
            YourUploadExtractor(),
            PlusPomlaExtractor(),
            OneuploadExtractor(),
            FsvidExtractor(),
            GoogleDriveExtractor(),
            PcloudExtractor(),
            AmazonDriveExtractor(),
            VidzyExtractor(),
            GuploadExtractor(),
            StreamUpExtractor(),
            EinschaltenExtractor(),
            VidLinkExtractor(),
            VidsrcRuExtractor(),
            VidflixExtractor(),
            VidrockExtractor(),
            VideasyExtractor(),
            VidzeeExtractor(),
            VidnestExtractor(),
            PrimeSrcExtractor(),
            VidoraExtractor(),
            GxPlayerExtractor(),
            UpZurExtractor(),
            DailymotionExtractor(),
            ApiVoirFilmExtractor(),
            StreamixExtractor(),
            ShareCloudyExtractor(),
            StreamrubyExtractor(),
            VidaraExtractor(),
            FirestreamExtractor(),
            MeinecloudExtractor(),
            VidsonicExtractor(),
            HxfileExtractor(),
            ZillaExtractor(),
            PDrainExtractor(),
            MaxstreamExtractor(),
            VidxGoExtractor()
        )

        suspend fun extract(link: String, server: Video.Server? = null): Video {
            var lastError: Exception? = null
            // Transient hoster flaps are common — retry once before surfacing failure.
            repeat(2) { attempt ->
                try {
                    val video = extractOnce(link, server)
                    // Ensure HLS/DASH get a MIME so ExoPlayer does not sniff HTML error pages as progressive.
                    val mime = StreamMime.coalesce(video.type, video.source)
                    return if (mime != null && mime != video.type) video.copy(type = mime) else video
                } catch (e: Exception) {
                    lastError = e
                    Log.w("Extractor", "extract attempt ${attempt + 1} failed for $link: ${e.message}")
                    // Permanent empty responses — do not burn a second attempt.
                    if (ExtractorFailureClassifier.isPermanent(e)) throw e
                    if (attempt == 0) delay(350)
                }
            }
            throw lastError ?: Exception("No extractors found for URL: $link")
        }

        @Deprecated("Use ExtractorFailureClassifier.isPermanent", ReplaceWith("ExtractorFailureClassifier.isPermanent(error)"))
        fun isPermanentExtractFailure(error: Throwable): Boolean =
            ExtractorFailureClassifier.isPermanent(error)

        private suspend fun extractOnce(link: String, server: Video.Server? = null): Video {
            var finalLink = link

            // Magnets can be resolved immediately (no embed/bridge needed).
            if (finalLink.startsWith("magnet:", ignoreCase = true) &&
                com.dskja.betterstreamflix.platform.debrid.DebridResolver.looksLikeHosterOrMagnet(finalLink)
            ) {
                tryDebrid(finalLink)?.let { return it }
            }

            // Expand DE embed wrappers (meinecloud / firestream) to a concrete hoster URL.
            if (MeinecloudEmbedHelper.isEmbedWrapper(finalLink)) {
                val resolved = MeinecloudEmbedHelper.resolveToHosterUrl(finalLink)
                if (resolved.isNotBlank() && resolved != finalLink) {
                    Log.d("Extractor", "Embed wrapper resolved: $finalLink -> $resolved")
                    finalLink = resolved
                }
            }
            
            // 1. RISOLUZIONE BRIDGE UNIVERSALE (StreamHG/Sync/Cuevana)
            // Facciamo questo PRIMA di cercare l'estrattore perché il link bridge (es. mysync.mov)
            // non appartiene a nessun estrattore specifico, ma il link risolto sì (es. filemoon).
            if (finalLink.contains("mysync.mov/stream/")) {
                try {
                    // Bounded timeouts — bare OkHttpClient hangs forever on dead peers and
                    // coroutine cancel cannot interrupt blocking execute().
                    val client = okhttp3.OkHttpClient.Builder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    val responseBody = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val request = okhttp3.Request.Builder()
                            .url(finalLink)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                            .build()
                        val call = client.newCall(request)
                        kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                            cont.invokeOnCancellation { runCatching { call.cancel() } }
                            call.enqueue(object : okhttp3.Callback {
                                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                                    if (cont.isActive) {
                                        cont.resumeWith(Result.failure(e))
                                    }
                                }

                                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                    response.use { resp ->
                                        val body = runCatching { resp.body?.string().orEmpty() }
                                            .getOrDefault("")
                                        if (cont.isActive) {
                                            cont.resumeWith(Result.success(body))
                                        }
                                    }
                                }
                            })
                        }
                    }

                    val redirectUrl = responseBody.substringAfter("window.location.replace(\"", "").substringBefore("\"")
                        .ifEmpty { responseBody.substringAfter("window.location.href = \"", "").substringBefore("\"") }
                        .ifEmpty { responseBody.substringAfter("src=\"", "").substringBefore("\"") }

                    if (redirectUrl.isNotEmpty() && redirectUrl.startsWith("http")) {
                        Log.d("Extractor", "Universal Bridge resolved: $finalLink -> $redirectUrl")
                        finalLink = redirectUrl
                    }
                } catch (e: Exception) {
                    Log.e("Extractor", "Universal Bridge error: ${e.message}")
                }
            }

            // Debrid AFTER embed/bridge so hosters behind wrappers get unrestricted.
            if (com.dskja.betterstreamflix.platform.debrid.DebridResolver.looksLikeHosterOrMagnet(finalLink)) {
                tryDebrid(finalLink)?.let { return it }
            }

            val urlRegex = Regex("^(https?://)?(www\\.)?")
            val compareUrl = finalLink.lowercase().replace(urlRegex, "")

            var foundExtractor: Extractor? = null

            for (extractor in extractors) {
                if (compareUrl.startsWith(extractor.mainUrl.replace(urlRegex, ""))) {
                    foundExtractor = extractor
                    break
                } else {
                    for (aliasUrl in extractor.aliasUrls) {
                        if (compareUrl.startsWith(aliasUrl.lowercase().replace(urlRegex, ""))) {
                            foundExtractor = extractor
                            break
                        }
                    }
                }
                if (foundExtractor != null) break
            }

            if (foundExtractor == null) {
                for (extractor in extractors) {
                    if (compareUrl.startsWith(
                            extractor.mainUrl.replace(
                                Regex("^(https?://)?(www\\.)?(.*?)(\\.[a-z]+)"),
                                "$3"
                            )
                        )
                    ) {
                        foundExtractor = extractor
                        break
                    } else {
                        for (aliasUrl in extractor.aliasUrls) {
                            if (compareUrl.startsWith(
                                    aliasUrl.replace(
                                        Regex("^(https?://)?(www\\.)?(.*?)(\\.[a-z]+)"),
                                        "$3"
                                    )
                                )
                            ) {
                                foundExtractor = extractor
                                break
                            }
                        }
                    }
                    if (foundExtractor != null) break
                }
            }

            if (foundExtractor == null) {
                for (extractor in extractors) {
                    if (extractor.rotatingDomain.any { it.containsMatchIn(compareUrl) }) {
                        foundExtractor = extractor
                        break
                    }
                }
            }

            if (foundExtractor == null) {
                for (extractor in extractors) {
                    if ((server?.name?.lowercase() ?: "").contains(extractor.name.lowercase())) {
                        foundExtractor = extractor
                        break
                    }
                }
            }

            if (foundExtractor != null) {
                Log.i("BetterStreamflix", "[EXTRACTOR] -> Starting: ${foundExtractor.name} (URL: $finalLink)")
                val video = foundExtractor.extract(finalLink)
                Log.i("BetterStreamflix", "[VIDEO] -> Extracted: ${video.source}")
                return video
            }

            throw Exception("No extractors found for URL: $finalLink")
        }

        private suspend fun tryDebrid(link: String): Video? {
            return when (
                val debrid = com.dskja.betterstreamflix.platform.debrid.DebridResolver.resolve(link)
            ) {
                is com.dskja.betterstreamflix.platform.debrid.DebridResult.Stream -> {
                    Log.i("Extractor", "Debrid resolved: $link")
                    Video(source = debrid.url, headers = debrid.headers.ifEmpty { null })
                }
                is com.dskja.betterstreamflix.platform.debrid.DebridResult.Pending -> {
                    if (link.startsWith("magnet:", ignoreCase = true)) {
                        throw Exception("Debrid still caching torrent (${debrid.id}). Try again shortly.")
                    }
                    Log.i("Extractor", "Debrid pending (${debrid.id}): ${debrid.message}")
                    null
                }
                is com.dskja.betterstreamflix.platform.debrid.DebridResult.Failure -> {
                    Log.d("Extractor", "Debrid skip: ${debrid.reason}")
                    null
                }
            }
        }
    }
}
