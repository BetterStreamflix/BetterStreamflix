package com.dskja.betterstreamflix.taste

import android.content.Context
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow

/**
 * Write-path helpers for taste feedback from Search / detail surfaces.
 */
object TasteFeedback {

    fun notInterested(context: Context, item: AppAdapter.Item) {
        if (item !is Movie && item !is TvShow) return
        TasteGraph.recordNegative(context, item, TasteSignalKind.NOT_INTERESTED)
    }

    fun hide(context: Context, item: AppAdapter.Item) {
        if (item !is Movie && item !is TvShow) return
        TasteGraph.recordNegative(context, item, TasteSignalKind.HIDDEN)
    }

    fun moreLike(context: Context, item: AppAdapter.Item) {
        if (item !is Movie && item !is TvShow) return
        TasteGraph.recordMoreLike(context, item)
    }

    fun favoriteBoost(context: Context, item: AppAdapter.Item) {
        if (item !is Movie && item !is TvShow) return
        TasteGraph.recordPositive(context, item)
    }
}
