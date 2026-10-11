package com.dskja.betterstreamflix.net

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.util.ArrayDeque
import kotlin.math.roundToLong

/**
 * Process-wide + lightly persisted throughput history for Honest ETA.
 *
 * Samples come from download/playback TransferListeners and download progress deltas.
 * Estimates prefer a recent EWMA over fantasy Content-Length math.
 */
object NetworkThroughputHistory {

    enum class Source { DOWNLOAD, PLAYBACK }

    enum class NetworkClass { WIFI, CELLULAR, OTHER, UNKNOWN }

    data class Sample(
        val bytesPerSec: Long,
        val atElapsedMs: Long,
        val source: Source,
        val networkClass: NetworkClass,
    )

    /** Overridable for JVM unit tests. */
    @Volatile
    var elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() }

    private const val PREFS = "network_throughput_history"
    private const val KEY_EWMA = "ewma_bps"
    private const val KEY_SAMPLES = "sample_count"
    private const val KEY_WIFI_EWMA = "ewma_bps_wifi"
    private const val KEY_CELL_EWMA = "ewma_bps_cell"

    private const val MAX_SAMPLES = 48
    private const val MIN_BYTES_FOR_SAMPLE = 16_384L
    private const val MIN_MS_FOR_SAMPLE = 200L
    private const val MAX_BPS = 200L * 1024L * 1024L // 200 MiB/s ceiling
    private const val MIN_BPS = 8_192L // 8 KiB/s floor for recorded samples
    private const val EWMA_ALPHA = 0.28
    private const val MIN_SAMPLES_FOR_ESTIMATE = 3
    private const val RECENT_WINDOW_MS = 90_000L

    private val lock = Any()
    private val samples = ArrayDeque<Sample>(MAX_SAMPLES)

    @Volatile
    private var ewmaBps: Double = 0.0
    @Volatile
    private var wifiEwmaBps: Double = 0.0
    @Volatile
    private var cellEwmaBps: Double = 0.0
    @Volatile
    private var sampleCount: Int = 0
    @Volatile
    private var prefs: SharedPreferences? = null
    @Volatile
    private var appContext: Context? = null

    fun attach(context: Context) {
        val app = context.applicationContext
        appContext = app
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        synchronized(lock) {
            ewmaBps = p.getLong(KEY_EWMA, 0L).toDouble()
            wifiEwmaBps = p.getLong(KEY_WIFI_EWMA, 0L).toDouble()
            cellEwmaBps = p.getLong(KEY_CELL_EWMA, 0L).toDouble()
            sampleCount = p.getInt(KEY_SAMPLES, 0).coerceAtLeast(0)
        }
    }

    fun clearForTest() {
        synchronized(lock) {
            samples.clear()
            ewmaBps = 0.0
            wifiEwmaBps = 0.0
            cellEwmaBps = 0.0
            sampleCount = 0
        }
    }

    fun recordBytes(
        bytes: Long,
        durationMs: Long,
        source: Source,
        networkClass: NetworkClass = currentNetworkClass(),
    ) {
        if (bytes < MIN_BYTES_FOR_SAMPLE || durationMs < MIN_MS_FOR_SAMPLE) return
        val bps = ((bytes * 1000.0) / durationMs.toDouble()).roundToLong()
            .coerceIn(MIN_BPS, MAX_BPS)
        recordSample(bps, source, networkClass)
    }

    fun recordSample(
        bytesPerSec: Long,
        source: Source,
        networkClass: NetworkClass = currentNetworkClass(),
    ) {
        if (bytesPerSec < MIN_BPS) return
        val clamped = bytesPerSec.coerceIn(MIN_BPS, MAX_BPS)
        val now = elapsedRealtimeMs()
        synchronized(lock) {
            samples.addLast(Sample(clamped, now, source, networkClass))
            while (samples.size > MAX_SAMPLES) samples.removeFirst()
            ewmaBps = if (ewmaBps <= 0.0) {
                clamped.toDouble()
            } else {
                EWMA_ALPHA * clamped + (1.0 - EWMA_ALPHA) * ewmaBps
            }
            when (networkClass) {
                NetworkClass.WIFI ->
                    wifiEwmaBps = blend(wifiEwmaBps, clamped.toDouble())
                NetworkClass.CELLULAR ->
                    cellEwmaBps = blend(cellEwmaBps, clamped.toDouble())
                else -> Unit
            }
            sampleCount += 1
            persistLocked()
        }
    }

    /**
     * Best-effort bytes/sec estimate. Returns null when history is too cold
     * (honest: do not invent a speed).
     */
    fun estimateBytesPerSec(
        preferNetworkClass: NetworkClass = currentNetworkClass(),
        allowColdEwma: Boolean = false,
    ): Long? {
        synchronized(lock) {
            val now = elapsedRealtimeMs()
            val recent = samples.filter { now - it.atElapsedMs <= RECENT_WINDOW_MS }
            val classRecent = recent.filter { it.networkClass == preferNetworkClass }
            val pool = when {
                classRecent.size >= 2 -> classRecent
                recent.isNotEmpty() -> recent
                else -> emptyList()
            }
            if (pool.size >= MIN_SAMPLES_FOR_ESTIMATE) {
                val sorted = pool.map { it.bytesPerSec }.sorted()
                return sorted[sorted.size / 2]
            }
            val classEwma = when (preferNetworkClass) {
                NetworkClass.WIFI -> wifiEwmaBps
                NetworkClass.CELLULAR -> cellEwmaBps
                else -> 0.0
            }
            val ewma = when {
                classEwma > 0.0 -> classEwma
                ewmaBps > 0.0 -> ewmaBps
                else -> 0.0
            }
            if (ewma <= 0.0) return null
            if (sampleCount < MIN_SAMPLES_FOR_ESTIMATE && !allowColdEwma) return null
            return ewma.roundToLong().coerceIn(MIN_BPS, MAX_BPS)
        }
    }

    fun sampleCount(): Int = sampleCount

    fun currentNetworkClass(context: Context? = appContext): NetworkClass {
        val ctx = context ?: return NetworkClass.UNKNOWN
        return runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching NetworkClass.UNKNOWN
            val network = cm.activeNetwork ?: return@runCatching NetworkClass.UNKNOWN
            val caps = cm.getNetworkCapabilities(network) ?: return@runCatching NetworkClass.UNKNOWN
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkClass.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkClass.CELLULAR
                else -> NetworkClass.OTHER
            }
        }.getOrDefault(NetworkClass.UNKNOWN)
    }

    /** Test harness: seed EWMA/samples without TransferListener. */
    internal fun seedForTest(
        bytesPerSec: Long,
        count: Int = MIN_SAMPLES_FOR_ESTIMATE,
        networkClass: NetworkClass = NetworkClass.WIFI,
    ) {
        clearForTest()
        repeat(count) {
            recordSample(bytesPerSec, Source.DOWNLOAD, networkClass)
        }
    }

    private fun blend(prev: Double, next: Double): Double =
        if (prev <= 0.0) next else EWMA_ALPHA * next + (1.0 - EWMA_ALPHA) * prev

    private fun persistLocked() {
        val p = prefs ?: return
        p.edit()
            .putLong(KEY_EWMA, ewmaBps.roundToLong())
            .putLong(KEY_WIFI_EWMA, wifiEwmaBps.roundToLong())
            .putLong(KEY_CELL_EWMA, cellEwmaBps.roundToLong())
            .putInt(KEY_SAMPLES, sampleCount.coerceAtMost(10_000))
            .apply()
    }
}
