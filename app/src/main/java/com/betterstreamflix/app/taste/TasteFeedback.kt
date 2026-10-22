package com.betterstreamflix.app.taste

import android.content.Context
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow

/**
 * Write-path helpers for taste feedback from Search / detail surfaces.
 */
object TasteFeedback {

    fun notInterested(context: Context, item: AppAdapter.Item) {
        if (item !is Movie && item !is TvShow) return
        TasteGraph.recordNegative(context, item, TasteSignalKind.NOT_INTERESTED)
        com.betterstreamflix.app.ui.UserDataNotifier.notifyChanged()
    }

    fun hide(context: Context, item: AppAdapter.Item) {
        if (item !is Movie && item !is TvShow) return
        TasteGraph.recordNegative(context, item, TasteSignalKind.HIDDEN)
        com.betterstreamflix.app.ui.UserDataNotifier.notifyChanged()
    }

    fun moreLike(context: Context, item: AppAdapter.Item) {
        if (item !is Movie && item !is TvShow) return
        TasteGraph.recordMoreLike(context, item)
    }

    fun favoriteBoost(context: Context, item: AppAdapter.Item) {
        if (item !is Movie && item !is TvShow) return
        TasteGraph.recordPositive(context, item)
    }

    fun favoriteUnboost(context: Context, item: AppAdapter.Item) {
        if (item !is Movie && item !is TvShow) return
        TasteGraph.recordUnfavorite(context, item)
    }

    fun watchEngagement(context: Context, item: AppAdapter.Item) {
        if (item !is Movie && item !is TvShow) return
        TasteGraph.recordWatchEngagement(context, item)
    }
}
