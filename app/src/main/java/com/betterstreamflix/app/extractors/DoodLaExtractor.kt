package com.betterstreamflix.app.extractors

import android.webkit.CookieManager
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.betterstreamflix.app.models.Video
import com.betterstreamflix.app.utils.DnsResolver
import com.betterstreamflix.app.utils.NetworkClient
import com.betterstreamflix.app.utils.StringConverterFactory
import com.betterstreamflix.app.utils.withExtractorTimeouts
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.Url
import java.net.URI

open class DoodLaExtractor : Extractor() {

    override val name = "DoodStream"
    override val mainUrl = "https://dood.la"
    override val aliasUrls = listOf(
        "https://dsvplay.com",
        "https://mikaylaarealike.com",
        "https://myvidplay.com",
        "https://playmogo.com",
        "https://do7go.com",
        "https://d000d.com"
    )

    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // Match the working iOS flow more closely: keep cookies between the
    // embed and /pass_md5 requests and send full browser playback headers.
    private val playbackUserAgent =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 " +
            "Mobile/15E148 Safari/604.1"

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)
        val embedUrl = link.replace("/d/", "/e/")
        val response = service.get(embedUrl, browserHeaders + mapOf("Referer" to link))
        val document = response.body() ?: throw Exception("Failed to load embed page")

        val finalUrl = response.raw().request.url.toString()
        val finalBaseUrl = getBaseUrl(finalUrl)
        val html = document.toString()

        val md5Path = DoodLaPlayback.findMd5Path(html)
            ?: throw Exception("Could not find md5 path")
        val pageToken = DoodLaPlayback.findPageToken(html)
        val md5Url = finalBaseUrl + md5Path

        val passHeaders = mapOf(
            "User-Agent" to browserHeaders.getValue("User-Agent"),
            "Accept" to "*/*",
            "Range" to "bytes=0-",
            "Referer" to finalUrl
        )
        val videoPrefix = service.getString(md5Url, passHeaders).trim()
        if (videoPrefix.isBlank()) throw Exception("Empty Dood pass_md5 response")

        val token = pageToken ?: md5Url.substringAfterLast("/")
        val url = DoodLaPlayback.buildPlaybackUrl(
            videoPrefix = videoPrefix,
            hash = createHashTable(),
            token = token,
            expiryMs = System.currentTimeMillis(),
        )

        val cookieHeader = CookieManager.getInstance()
            .getCookie(finalUrl)
            .orEmpty()
            .trim()

        return Video(
            source = url,
            headers = DoodLaPlayback.buildPlaybackHeaders(
                finalUrl = finalUrl,
                finalBaseUrl = finalBaseUrl,
                playbackUserAgent = playbackUserAgent,
                cookieHeader = cookieHeader,
            )
        )
    }

    private fun createHashTable(): String {
        return buildString {
            repeat(10) {
                append(alphabet.random())
            }
        }
    }

    private fun getBaseUrl(url: String) = URI(url).let { "${it.scheme}://${it.host}" }


    class DoodLiExtractor : DoodLaExtractor() {
        override var mainUrl = "https://dood.li"
    }

    class DoodExtractor : DoodLaExtractor() {
        override val mainUrl = "https://vide0.net"
    }


    private interface Service {

        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder()
                    .cookieJar(NetworkClient.cookieJar)
                    .dns(DnsResolver.doh)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .withExtractorTimeouts()
                    .build()

                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(StringConverterFactory.create())
                    .client(client)
                    .build()

                return retrofit.create(Service::class.java)
            }
        }


        @GET
        suspend fun get(
            @Url url: String,
            @HeaderMap headers: Map<String, String>,
        ): Response<Document>

        @GET
        suspend fun getString(
            @Url url: String,
            @HeaderMap headers: Map<String, String>,
        ): String
    }
}
