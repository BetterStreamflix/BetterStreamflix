package com.dskja.betterstreamflix.player

import com.dskja.betterstreamflix.utils.NetworkClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * OkHttp client for ExoPlayer segment reads.
 * [NetworkClient.default] caps an entire call at 60s, which aborts a long
 * progressive/HLS read mid-playback. Streaming keeps a connect timeout and a
 * stall (read) timeout, and disables the call-wide cap.
 */
object PlaybackHttp {
    fun newStreamingClient(): OkHttpClient =
        NetworkClient.default.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
}
