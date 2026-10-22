package com.betterstreamflix.app.ui

import android.content.Context
import android.graphics.drawable.PictureDrawable
import android.webkit.CookieManager
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.caverock.androidsvg.SVG
import com.betterstreamflix.app.utils.ArtworkRequestHeaders
import com.betterstreamflix.app.utils.DeviceCapabilities
import com.betterstreamflix.app.utils.NetworkClient
import okhttp3.*
import okhttp3.Dns
import okhttp3.OkHttpClient.Builder
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@GlideModule
class GlideCustomModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        if (!DeviceCapabilities.shouldReduceHomeEffects(context)) return
        // Fire Stick / low-RAM: shrink Glide pools; MemoryCategory.LOW applied in app onCreate.
        val calculator = MemorySizeCalculator.Builder(context).build()
        val memCache = (calculator.memoryCacheSize * 0.5f).toLong().coerceAtLeast(1024L * 1024L)
        val bitmapPool = (calculator.bitmapPoolSize * 0.5f).toLong().coerceAtLeast(1024L * 1024L)
        builder.setMemoryCache(LruResourceCache(memCache))
        builder.setBitmapPool(LruBitmapPool(bitmapPool))
    }

    private fun getOkHttpClient(context: Context): OkHttpClient {
        val appCache = Cache(File(context.cacheDir, "glide-okhttp-cache"), 10 * 1024 * 1024)

        val logging = HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC)

        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
        )
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, SecureRandom()) }
        val trustManager = trustAllCerts[0] as X509TrustManager

        return Builder()
            .cache(appCache)
            .cookieJar(imageCookieJar)
            .addInterceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()

                if (original.header("User-Agent") == null) {
                    requestBuilder.header("User-Agent", NetworkClient.USER_AGENT)
                }
                if (original.header("Accept") == null) {
                    // Never advertise AVIF first — many phone/TV Glide/Bitmap paths
                    // cannot decode it, so CDNs return undecodable bodies and every
                    // provider's posters stay blank.
                    requestBuilder.header(
                        "Accept",
                        "image/webp,image/png,image/jpeg,image/*,*/*;q=0.8",
                    )
                }
                if (original.header("Accept-Language") == null) {
                    requestBuilder.header(
                        "Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
                    )
                }
                chain.proceed(requestBuilder.build())
            }
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val headers = ArtworkRequestHeaders.headersFor(request.url)
                val strippedUrl = ArtworkRequestHeaders.stripHeaders(request.url)
                val fixedRequest = if (headers.isNotEmpty() || strippedUrl != request.url) {
                    request.newBuilder()
                        .url(strippedUrl)
                        .apply {
                            headers.forEach { (name, value) -> header(name, value) }
                        }
                        .build()
                } else {
                    request
                }
                chain.proceed(fixedRequest)
            }
            .addInterceptor(logging)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            // Image CDN hosts (TMDb, provider CDNs) are fine on system DNS.
            // Routing every poster through DoH multiplies timeout blanks when
            // DoH is slow — DnsResolver still backs provider HTTP elsewhere.
            .dns(Dns.SYSTEM)
            .build()
    }

    override fun registerComponents(
        context: Context, glide: Glide, registry: com.bumptech.glide.Registry
    ) {
        val okHttpClient = getOkHttpClient(context)
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            AnimeOnlineNinjaCronetUrlLoader.Factory(context, okHttpClient),
        )
        registry.append(
            InputStream::class.java,
            SVG::class.java,
            SvgDecoder(),
        )
        registry.register(
            SVG::class.java,
            PictureDrawable::class.java,
            SvgDrawableTranscoder(),
        )
    }

    private val imageCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            NetworkClient.cookieJar.saveFromResponse(url, cookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return NetworkClient.cookieJar.loadForRequest(url)
        }
    }

}
