package com.dskja.betterstreamflix.extractors

import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.DnsResolver
import com.dskja.betterstreamflix.utils.JsUnpacker
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

class FastreamExtractor : Extractor() {
    override val name = "Fastream"
    override val mainUrl = "https://fastream.to"

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .dns(DnsResolver.doh)
        .build()

    private val service = Retrofit.Builder()
        .baseUrl(mainUrl)
        .addConverterFactory(ScalarsConverterFactory.create())
        .client(client)
        .build()
        .create(FastreamService::class.java)

    private interface FastreamService {
        @GET
        suspend fun get(
            @Url url: String,
            @Header("User-Agent") userAgent: String,
        ): String
    }

    override suspend fun extract(link: String): Video {
        val html = service.get(link, USER_AGENT)

        val scriptData = html
            .substringAfter("eval(function(p,a,c,k,e,d)")
            .substringBefore("</script>")
            .let { "eval(function(p,a,c,k,e,d)$it" }

        if (!scriptData.startsWith("eval")) throw Exception("Packed JS not found")
        val unpacked = JsUnpacker(scriptData).unpack() ?: throw Exception("Unpack failed")

        var m3u8: String? = null
        if ("jwplayer" in unpacked && "sources" in unpacked && "file" in unpacked) {
            m3u8 = Regex("""sources:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
        }
        if (m3u8 == null) throw Exception("Stream URL not found")

        return Video(
            source = m3u8,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Sec-Fetch-Dest" to "empty",
                "Accept" to "*/*",
                "Accept-Language" to "",
            ),
        )
    }
}
