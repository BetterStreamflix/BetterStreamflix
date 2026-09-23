package com.dskja.betterstreamflix.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.fragment.app.FragmentActivity
import com.bumptech.glide.Glide
import com.dskja.betterstreamflix.fragments.player.PlayerMobileFragment
import com.dskja.betterstreamflix.fragments.player.PlayerTvFragment
import java.io.File

object CacheUtils {
    private const val TAG = "CacheUtils"

    /**
     * Only these top-level [Context.getCacheDir] children are safe to wipe.
     * Unknown files (e.g. active `stream.m3u8`, tmp subtitle downloads) must survive.
     */
    private val SAFE_CACHE_SUBDIRS = setOf(
        "image_manager_disk_cache", // Glide default disk cache
        "glide-okhttp-cache",       // GlideCustomModule OkHttp cache
        "okhttpcache",              // provider OkHttp caches
        "mkissa_okhttpcache",
        "http_cache",
        "WebView",
        "org.chromium.android_webview",
    )

    fun clearAppCache(context: Context) {
        if (isPlaybackActive(context)) {
            Log.i(TAG, "Skip cache wipe — playback is active")
            return
        }

        Log.d(TAG, "Inizio pulizia cache (safe subdirs only)...")
        try {
            deleteKnownSafeCacheChildren(context.cacheDir)
            deleteKnownSafeCacheChildren(context.externalCacheDir)
            Log.d(TAG, "Cache sicura eliminata.")
        } catch (e: Exception) {
            Log.e(TAG, "Errore eliminazione cache interna: ${e.message}")
        }

        try {
            Glide.get(context).clearMemory()
            Thread {
                try {
                    Glide.get(context).clearDiskCache()
                    Log.d(TAG, "Cache Glide eliminata.")
                } catch (e: Exception) {
                    Log.e(TAG, "Errore eliminazione cache Glide: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Errore Glide: ${e.message}")
        }

        clearWebViewCacheOnMainThread(context)
    }

    private fun deleteKnownSafeCacheChildren(cacheRoot: File?) {
        if (cacheRoot == null || !cacheRoot.isDirectory) return
        val children = cacheRoot.listFiles() ?: return
        for (child in children) {
            if (!child.isDirectory) {
                // Never delete unknown top-level files (playback temps, etc.).
                Log.d(TAG, "Skip unknown cache file: ${child.name}")
                continue
            }
            if (child.name !in SAFE_CACHE_SUBDIRS) {
                Log.d(TAG, "Skip unknown cache dir: ${child.name}")
                continue
            }
            runCatching {
                child.deleteRecursively()
                Log.d(TAG, "Deleted cache dir: ${child.name}")
            }.onFailure {
                Log.e(TAG, "Failed deleting ${child.name}: ${it.message}")
            }
        }
    }

    private fun clearWebViewCacheOnMainThread(context: Context) {
        val clear: () -> Unit = {
            try {
                WebView(context.applicationContext).apply {
                    clearCache(true)
                    destroy()
                }
                Log.d(TAG, "Cache WebView eliminata.")
            } catch (e: Exception) {
                Log.e(TAG, "Errore WebView: ${e.message}")
            }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clear()
        } else {
            Handler(Looper.getMainLooper()).post(clear)
        }
    }

    private fun isPlaybackActive(context: Context): Boolean {
        val activity = context.findActivity() as? FragmentActivity ?: return false
        val current = activity.getCurrentFragment()
        return current is PlayerMobileFragment || current is PlayerTvFragment
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    fun getCacheSize(context: Context): Long {
        var size: Long = 0
        try {
            size += getFolderSize(context.cacheDir)
            size += getFolderSize(context.externalCacheDir)
        } catch (_: Exception) {
        }
        return size
    }

    private fun getFolderSize(file: File?): Long {
        if (file == null || !file.exists()) return 0
        if (!file.isDirectory) return file.length()

        var size: Long = 0
        val files = file.listFiles()
        if (files != null) {
            for (f in files) {
                size += if (f.isDirectory) getFolderSize(f) else f.length()
            }
        }
        return size
    }

    fun autoClearIfNeeded(context: Context, thresholdMb: Long = 50) {
        val currentSize = getCacheSize(context)
        val thresholdBytes = thresholdMb * 1024 * 1024
        val currentMb = currentSize / (1024 * 1024)

        Log.d(TAG, "Controllo cache: Attuale = ${currentMb}MB, Soglia = ${thresholdMb}MB")

        if (currentSize > thresholdBytes) {
            Log.i(TAG, "Soglia superata! Avvio pulizia automatica...")
            clearAppCache(context)
        } else {
            Log.d(TAG, "Soglia non raggiunta. Nessuna pulizia necessaria.")
        }
    }
}
