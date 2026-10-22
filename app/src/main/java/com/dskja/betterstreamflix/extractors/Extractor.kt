package com.dskja.betterstreamflix.extractors

import android.net.Uri
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
            VixcloudExtractor(),
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
            FastreamExtractor(),
            MeinecloudExtractor(),
            VidsonicExtractor(),
            HxfileExtractor(),
            ZillaExtractor(),
            PDrainExtractor(),
            MaxstreamExtractor(),
            VidxGoExtractor()
        )

        suspend fun extract(
            link: String,
            server: Video.Server? = null,
            maxAttempts: Int = 2,
        ): Video {
            var lastError: Exception? = null
            val attempts = maxAttempts.coerceAtLeast(1)
            // Transient hoster flaps are common — retry once before surfacing failure.
            // Nested wrapper hops (Firestream/Meinecloud) pass maxAttempts=1 to avoid amplify.
            repeat(attempts) { attempt ->
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
                    if (attempt < attempts - 1) delay(350)
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
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("Extractor", "Universal Bridge (mysync) error: ${e.message}")
                    // Don't swallow timeouts / hard failures — let failover see them.
                    if (com.dskja.betterstreamflix.utils.ExtractorTimeouts.isTimeout(e)) {
                        throw Exception("mysync.mov bridge timed out: ${e.message}", e)
                    }
                    // Non-timeout parse/network errors: continue with unresolved link
                    // so a hoster extractor may still match the original URL.
                }
            }

            // Debrid AFTER embed/bridge so hosters behind wrappers get unrestricted.
            if (com.dskja.betterstreamflix.platform.debrid.DebridResolver.looksLikeHosterOrMagnet(finalLink)) {
                tryDebrid(finalLink)?.let { return it }
            }

            val foundExtractor = findExtractorForLink(finalLink, server)

            if (foundExtractor != null) {
                Log.i("BetterStreamflix", "[EXTRACTOR] -> Starting: ${foundExtractor.name} (URL: $finalLink)")
                val video = foundExtractor.extract(finalLink)
                // Don't log the full source URL — signed streams carry secret tokens.
                val src = runCatching { Uri.parse(video.source) }.getOrNull()
                Log.i(
                    "BetterStreamflix",
                    "[VIDEO] -> Extracted: ${src?.scheme ?: "?"}://${src?.host ?: "?"} (${video.source.length} chars)"
                )
                return video
            }

            throw Exception("No extractors found for URL: $finalLink")
        }

        /**
         * Picks the extractor responsible for [link]. Pure string matching (no I/O) so it
         * stays unit-testable. Matching is host-boundary aware: a host only matches when
         * it equals a known host or is a subdomain of it — prefix matching the raw URL let
         * lookalike hosts like "vidxgo.co.evil.com" hijack an extractor.
         */
        internal fun findExtractorForLink(link: String, server: Video.Server? = null): Extractor? {
            val urlRegex = Regex("^(https?://)?(www\\.)?")
            val compareUrl = link.lowercase().replace(urlRegex, "")
            // Host portion only (no path/query/port).
            val linkHost = compareUrl.substringBefore('/').substringBefore('?').substringBefore(':')
            val linkPath = compareUrl.substringAfter('/', "")

            fun hostPartOf(url: String): String =
                url.lowercase().replace(urlRegex, "")
                    .substringBefore('/').substringBefore('?').substringBefore(':')

            // Some mainUrls carry a required path (e.g. "jkanime.net/jkplayer/") —
            // matching must keep that path-prefix semantics, not just the host.
            fun pathPrefixOf(url: String): String =
                url.lowercase().replace(urlRegex, "").substringBefore('?')
                    .substringAfter('/', "").removeSuffix("/")

            fun registrableNameOf(host: String): String {
                val labels = host.split('.').filter { it.isNotBlank() }
                return if (labels.size >= 2) labels[labels.size - 2] else host
            }

            fun matchesHost(url: String): Boolean {
                val host = hostPartOf(url)
                if (host.isBlank() || !(linkHost == host || linkHost.endsWith(".$host"))) {
                    return false
                }
                val pathPrefix = pathPrefixOf(url)
                return pathPrefix.isEmpty() || linkPath.startsWith(pathPrefix)
            }

            fun matchesRegistrableName(url: String): Boolean {
                val name = registrableNameOf(hostPartOf(url))
                if (name.isBlank() || registrableNameOf(linkHost) != name) return false
                val pathPrefix = pathPrefixOf(url)
                return pathPrefix.isEmpty() || linkPath.startsWith(pathPrefix)
            }

            // Some extractors resolve mainUrl from provider state — a throwing getter
            // must not kill the whole dispatch loop.
            fun mainUrlOf(e: Extractor): String? = runCatching { e.mainUrl }.getOrNull()
            fun aliasesOf(e: Extractor): List<String> =
                runCatching { e.aliasUrls }.getOrDefault(emptyList())

            // Pass 1: exact host or a subdomain of a known host/alias.
            for (extractor in extractors) {
                val mainUrl = mainUrlOf(extractor)
                if ((mainUrl != null && matchesHost(mainUrl)) ||
                    aliasesOf(extractor).any { alias ->
                        val aliasHost = hostPartOf(alias)
                        // Skip broken aliases with no real host (e.g. "mivalyo" / "_").
                        aliasHost.isNotBlank() && aliasHost != "_" && aliasHost.contains('.') &&
                            matchesHost(alias)
                    }
                ) {
                    return extractor
                }
            }

            // Pass 2: same registrable name under a different TLD (rotating domains).
            for (extractor in extractors) {
                val mainUrl = mainUrlOf(extractor)
                if ((mainUrl != null && matchesRegistrableName(mainUrl)) ||
                    aliasesOf(extractor).any { alias ->
                        val aliasHost = hostPartOf(alias)
                        aliasHost.isNotBlank() && aliasHost != "_" && !aliasHost.contains("://") &&
                            matchesRegistrableName(alias)
                    }
                ) {
                    return extractor
                }
            }

            // Pass 3: rotating-domain regexes must hit the registrable part of the
            // host (last two labels), not just any label or the path — otherwise
            // lookalikes like "filemoon.evil.com" would match "^filemoon".
            val registrablePart = linkHost.split('.').filter { it.isNotBlank() }
                .let { if (it.size >= 2) it.takeLast(2).joinToString(".") else linkHost }
            for (extractor in extractors) {
                if (extractor.rotatingDomain.any { it.containsMatchIn(registrablePart) }) {
                    return extractor
                }
            }

            for (extractor in extractors) {
                val serverName = server?.name?.lowercase() ?: ""
                val extractorName = runCatching { extractor.name.lowercase() }.getOrNull()
                if (extractorName != null && serverName.contains(extractorName)) {
                    return extractor
                }
            }

            return null
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
