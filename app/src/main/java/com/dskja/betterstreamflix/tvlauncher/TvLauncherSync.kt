package com.dskja.betterstreamflix.tvlauncher

import android.content.Context
import android.util.Log
import com.dskja.betterstreamflix.ui.UserDataNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Schedules TV Watch Next / Preview Channel publishes after library changes. */
object TvLauncherSync {
    private const val TAG = "TvLauncherSync"
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listenJob: Job? = null
    private var publishJob: Job? = null

    fun start(context: Context) {
        val app = context.applicationContext
        if (!TvLauncherSurface.isEnabled(app)) return
        if (!started.compareAndSet(false, true)) {
            requestPublish(app)
            return
        }
        listenJob = scope.launch {
            UserDataNotifier.updates.collect {
                requestPublish(app)
            }
        }
        requestPublish(app)
    }

    fun requestPublish(context: Context) {
        val app = context.applicationContext
        if (!TvLauncherSurface.isEnabled(app)) return
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(750)
            runCatching { TvLauncherPublisher.publish(app) }
                .onFailure { Log.w(TAG, "publish: ${it.message}") }
        }
    }
}
