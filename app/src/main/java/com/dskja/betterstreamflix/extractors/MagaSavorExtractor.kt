package com.dskja.betterstreamflix.extractors

import com.dskja.betterstreamflix.utils.withExtractorTimeouts
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.DecryptHelper
import java.util.regex.Pattern
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

class MagaSavorExtractor : Extractor() {
    override val name = "MagaSavors"
    override val mainUrl = "https://magasavor.net"

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)
        val source = service.get(link, mainUrl)
        val scriptJson = source.selectFirst("script[type=application/json]")?.data()?.trim().orEmpty()
        val decryptedContent = DecryptHelper.firstPlayable(
            listOfNotNull(DecryptHelper.findEncodedRegex(source.html()), scriptJson.takeIf { it.isNotBlank() }),
        ) ?: throw Exception("MagaSavor source not found")
        val m3u8 = decryptedContent.get("source")?.asString.orEmpty()
        if (m3u8.isBlank()) throw Exception("MagaSavor source not found")

        return Video(
            source = m3u8,
            subtitles = listOf(),
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl,
                "User-Agent" to USER_AGENT,
            ),
        )

    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(OkHttpClient.Builder().withExtractorTimeouts().build())
                .addConverterFactory(JsoupConverterFactory.create())
                .build()
                .create(Service::class.java)
        }

        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("Accept") accept: String = "text/html",
            @Header("User-Agent") userAgent: String = USER_AGENT,
        ): Document
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    }
}
