package com.dskja.betterstreamflix.platform.playerbackend

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Manifest-registered receiver so [Intent.EXTRA_CHOSEN_COMPONENT] from the system
 * chooser is delivered even if the player process was killed while the sheet was open.
 */
class ExternalPlayerChosenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ExternalStreamHandoff.ACTION_PLAYER_CHOSEN) return
        ExternalStreamHandoff.chosenComponentReceiver().onReceive(context, intent)
    }
}
