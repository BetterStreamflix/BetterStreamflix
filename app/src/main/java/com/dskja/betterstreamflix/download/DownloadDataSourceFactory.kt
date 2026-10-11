package com.dskja.betterstreamflix.download

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.dskja.betterstreamflix.net.ThroughputTransferListener
import com.dskja.betterstreamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Builds HTTP data sources that inject per-download headers from [DownloadHeaderStore].
 *
 * [activeMedia3Id] / [activeHeaders] are enqueue-time hints only; [HeaderInjectingDataSource]
 * resolves URL-scoped headers so parallel downloads do not bleed. Prefer clearing
 * [activeMedia3Id] after enqueue when possible (DL-P3-N).
 */
class DownloadDataSourceFactory(
    private val context: Context,
) : DataSource.Factory {
    private val appContext = context.applicationContext

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Enqueue-time hint; not a process-wide active download selector. */
    @Volatile
    var activeMedia3Id: String? = null

    @Volatile
    var activeHeaders: Map<String, String> = emptyMap()

    override fun createDataSource(): DataSource {
        val media3Id = activeMedia3Id
        val stored = media3Id?.let { DownloadHeaderStore.get(appContext, it) }.orEmpty()
        val headers = if (stored.isNotEmpty()) stored else activeHeaders
        val factory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(
                headers["User-Agent"]
                    ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
            )
            .setTransferListener(ThroughputTransferListener.asDownload())
        if (headers.isNotEmpty()) {
            factory.setDefaultRequestProperties(headers)
        }
        // URL-scoped headers win over the shared defaults — fixes header bleed
        // when several downloads run in parallel on this factory.
        return HeaderInjectingDataSource(appContext, factory.createDataSource())
    }
}
