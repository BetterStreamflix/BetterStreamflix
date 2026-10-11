package com.dskja.betterstreamflix.utils

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Bound extractor HTTP so a hung hoster fails inside the player failover window
 * instead of waiting on the platform socket timeout.
 */
fun OkHttpClient.Builder.withExtractorTimeouts(): OkHttpClient.Builder = apply {
    connectTimeout(10, TimeUnit.SECONDS)
    readTimeout(15, TimeUnit.SECONDS)
    callTimeout(20, TimeUnit.SECONDS)
}

/** Detect timeout-class failures so callers can rethrow instead of soft-empty. */
object ExtractorTimeouts {
    fun isTimeout(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            when (cur) {
                is TimeoutCancellationException,
                is SocketTimeoutException,
                is TimeoutException,
                is InterruptedIOException -> return true
            }
            val msg = cur.message.orEmpty()
            if (msg.contains("timeout", ignoreCase = true) ||
                msg.contains("timed out", ignoreCase = true)
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }
}
