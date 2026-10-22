package com.betterstreamflix.app.net

import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Media3 [TransferListener] factories that feed [NetworkThroughputHistory]
 * from download and playback HTTP transfers (accumulated bytes / wall duration).
 */
@OptIn(UnstableApi::class)
object ThroughputTransferListener {

    fun asDownload(): TransferListener = SourceBound(NetworkThroughputHistory.Source.DOWNLOAD)

    fun asPlayback(): TransferListener = SourceBound(NetworkThroughputHistory.Source.PLAYBACK)

    /** Progress-poll path when TransferListener samples are sparse (Media3 offline). */
    fun recordProgressDelta(
        deltaBytes: Long,
        deltaMs: Long,
        source: NetworkThroughputHistory.Source,
    ) {
        if (deltaBytes <= 0L || deltaMs <= 0L) return
        NetworkThroughputHistory.recordBytes(deltaBytes, deltaMs, source)
    }

    private class SourceBound(
        private val bound: NetworkThroughputHistory.Source,
    ) : TransferListener {
        private val openAt = ConcurrentHashMap<DataSource, Long>()
        private val bytes = ConcurrentHashMap<DataSource, Long>()

        override fun onTransferInitializing(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
        ) = Unit

        override fun onTransferStart(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
        ) {
            if (!isNetwork) return
            openAt[source] = SystemClock.elapsedRealtime()
            bytes[source] = 0L
        }

        override fun onBytesTransferred(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
            bytesTransferred: Int,
        ) {
            if (!isNetwork || bytesTransferred <= 0) return
            bytes.merge(source, bytesTransferred.toLong()) { a, b -> a + b }
        }

        override fun onTransferEnd(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
        ) {
            if (!isNetwork) return
            val started = openAt.remove(source) ?: return
            val transferred = bytes.remove(source) ?: 0L
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
            NetworkThroughputHistory.recordBytes(transferred, elapsed, bound)
        }
    }
}
