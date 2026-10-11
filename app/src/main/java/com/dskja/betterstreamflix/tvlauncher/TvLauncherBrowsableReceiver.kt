package com.dskja.betterstreamflix.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat

/**
 * Removes Preview / Watch Next programs when the user dismisses them from the
 * Android TV home screen (must not re-insert until content changes).
 */
class TvLauncherBrowsableReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!TvLauncherSurface.isEnabled(context)) return
        when (intent?.action) {
            TvContractCompat.ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED -> {
                val id = intent.getLongExtra(TvContractCompat.EXTRA_WATCH_NEXT_PROGRAM_ID, -1L)
                Log.i(TAG, "Watch Next browsable disabled id=$id")
                TvLauncherPublisher.removeWatchNextProgram(context, id)
            }
            TvContractCompat.ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED -> {
                val id = intent.getLongExtra(TvContractCompat.EXTRA_PREVIEW_PROGRAM_ID, -1L)
                Log.i(TAG, "Preview program browsable disabled id=$id")
                TvLauncherPublisher.removePreviewProgram(context, id)
            }
            TvContractCompat.ACTION_INITIALIZE_PROGRAMS -> {
                Log.i(TAG, "Initialize programs — publishing Watch Next / channels")
                TvLauncherSync.requestPublish(context)
            }
            else -> Unit
        }
    }

    companion object {
        private const val TAG = "TvLauncherBrowsable"
    }
}
