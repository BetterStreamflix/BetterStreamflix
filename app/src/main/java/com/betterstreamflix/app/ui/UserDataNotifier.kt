package com.betterstreamflix.app.ui

import com.betterstreamflix.app.BetterStreamflixApp
import com.betterstreamflix.app.widgets.WidgetUpdater
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce

object UserDataNotifier {
    private val _updates = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1
    )

    val updates = _updates
        .debounce(300)

    fun notifyChanged() {
        _updates.tryEmit(Unit)
        // Refresh phone home-screen widgets when CW / favorites change.
        runCatching { WidgetUpdater.requestRefresh(BetterStreamflixApp.instance) }
    }
}