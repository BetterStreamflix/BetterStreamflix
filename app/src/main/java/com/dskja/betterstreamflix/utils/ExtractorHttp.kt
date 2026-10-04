package com.dskja.betterstreamflix.utils

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
