package com.dskja.betterstreamflix.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.dskja.betterstreamflix.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LiveFavoritesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                val rows = WidgetLibrary.loadLiveFavorites(app, limit = 4)
                appWidgetIds.forEach { id ->
                    appWidgetManager.updateAppWidget(id, buildViews(app, rows, id))
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        WidgetUpdater.requestRefresh(context)
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val TILE_IDS = intArrayOf(
            R.id.widget_live_tile_1,
            R.id.widget_live_tile_2,
            R.id.widget_live_tile_3,
            R.id.widget_live_tile_4,
        )

        fun buildViews(
            context: Context,
            rows: List<WidgetLibrary.LiveFavoriteRow>,
            appWidgetId: Int,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_live_favorites)
            views.setTextViewText(
                R.id.widget_live_header,
                context.getString(R.string.widget_live_favorites_title),
            )
            val hubPi = deepLinkPi(
                context,
                WidgetDeepLinks.buildLiveHubUri(),
                appWidgetId,
                50,
            )
            views.setOnClickPendingIntent(R.id.widget_live_header, hubPi)
            views.setOnClickPendingIntent(R.id.widget_live_root, hubPi)

            if (rows.isEmpty()) {
                views.setViewVisibility(R.id.widget_live_empty, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_live_empty,
                    context.getString(R.string.widget_live_favorites_empty),
                )
                TILE_IDS.forEach { views.setViewVisibility(it, View.GONE) }
                return views
            }

            views.setViewVisibility(R.id.widget_live_empty, View.GONE)
            TILE_IDS.forEachIndexed { index, tileId ->
                val row = rows.getOrNull(index)
                if (row == null) {
                    views.setViewVisibility(tileId, View.GONE)
                    return@forEachIndexed
                }
                views.setViewVisibility(tileId, View.VISIBLE)
                views.setTextViewText(tileId, row.title)
                views.setOnClickPendingIntent(
                    tileId,
                    deepLinkPi(context, row.deepLink, appWidgetId, index + 1),
                )
            }
            return views
        }

        private fun deepLinkPi(
            context: Context,
            deepLink: String,
            appWidgetId: Int,
            requestSalt: Int,
        ): PendingIntent {
            val intent = WidgetDeepLinks.openIntent(context, deepLink)
            return PendingIntent.getActivity(
                context,
                3000 + appWidgetId * 10 + requestSalt,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
