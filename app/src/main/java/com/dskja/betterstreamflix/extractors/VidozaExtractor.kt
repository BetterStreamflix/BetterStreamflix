package com.dskja.betterstreamflix.extractors

import okhttp3.OkHttpClient
import com.dskja.betterstreamflix.utils.withExtractorTimeouts
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.dskja.betterstreamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URL

class VidozaExtractor : Extractor() {

    override val name = "Vidoza"

    override val mainUrl = "https://vidoza.net"
    override val aliasUrls = listOf<String>("https://videzz.net")

    override suspend fun extract(link: String): Video {
        val service = VoeExtractorService.build(mainUrl)
        val source = service.getSource(link.replace(mainUrl, ""))
        val videoUrl = source.select("source").attr("src")
        if (videoUrl.isBlank()) {
            throw Exception("Vidoza source not found")
        }
        val origin = runCatching {
            val parsed = URL(link)
            "${parsed.protocol}://${parsed.host}"
        }.getOrDefault(mainUrl)
        return Video(
            source = videoUrl,
            subtitles = listOf(),
            headers = mapOf(
                "Referer" to link,
                "Origin" to origin,
            ),
        )
    }


    private interface VoeExtractorService {

        companion object {
            fun build(baseUrl: String): VoeExtractorService {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(OkHttpClient.Builder().withExtractorTimeouts().build()).build()

                return retrofit.create(VoeExtractorService::class.java)
            }
        }

        @GET
        suspend fun getSource(@Url url: String): Document
    }
}
