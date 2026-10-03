package com.dskja.betterstreamflix.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Manifest receiver for download-manager chooser picks. */
class ExternalDownloaderChosenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ExternalDownloadHandoff.ACTION_DOWNLOADER_CHOSEN) return
        ExternalDownloadHandoff.chosenComponentReceiver().onReceive(context, intent)
    }
}
