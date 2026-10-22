package com.betterstreamflix.app.sync

import android.content.Context
import com.betterstreamflix.app.R
import com.betterstreamflix.app.remotefind.RemoteFindHost
import com.betterstreamflix.app.remotefind.RemoteFindSession
import com.betterstreamflix.app.utils.ExpDialogChrome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Cross-companion guards: mutual exclusion between Cast Room and Watch Together,
 * and profile-switch cleanup for in-memory handoff/party/room sessions.
 */
object CompanionSessions {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Block starting Cast Room host while a Watch Together session is active (and vice versa). */
    fun refuseIfOtherCompanionActive(
        context: Context,
        starting: Kind,
    ): Boolean {
        return when (starting) {
            Kind.CAST_ROOM -> {
                if (WatchPartySession.isHost() ||
                    WatchPartySession.isGuest() ||
                    WatchPartySession.isRemote()
                ) {
                    ExpDialogChrome.notify(context, R.string.companion_busy_watch_together)
                    true
                } else {
                    false
                }
            }
            Kind.WATCH_TOGETHER -> {
                if (CastRoomSession.isHost() || CastRoomSession.isController()) {
                    ExpDialogChrome.notify(context, R.string.companion_busy_cast_room)
                    true
                } else {
                    false
                }
            }
        }
    }

    fun onProfileSwitched() {
        runCatching { CastRoomUi.onProfileSwitched() }
        WatchPartySession.clear()
        ioScope.launch { runCatching { WatchPartySync.stop() } }
        SessionHandoffSession.clear()
        CastHandoffSession.clear()
        runCatching {
            if (RemoteFindSession.isHost()) {
                RemoteFindHost.stop()
            }
            RemoteFindSession.clear()
        }
    }

    enum class Kind {
        CAST_ROOM,
        WATCH_TOGETHER,
    }
}
