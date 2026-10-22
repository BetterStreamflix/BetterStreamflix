package com.betterstreamflix.app.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.betterstreamflix.app.R
import com.betterstreamflix.app.activities.main.MainMobileActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ContinueWatchingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                val rows = WidgetLibrary.loadContinueWatching(app, limit = 3)
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

        private val ROW_TITLE_IDS = intArrayOf(
            R.id.widget_cw_title_1,
            R.id.widget_cw_title_2,
            R.id.widget_cw_title_3,
        )
        private val ROW_SUB_IDS = intArrayOf(
            R.id.widget_cw_sub_1,
            R.id.widget_cw_sub_2,
            R.id.widget_cw_sub_3,
        )
        private val ROW_PROGRESS_IDS = intArrayOf(
            R.id.widget_cw_progress_1,
            R.id.widget_cw_progress_2,
            R.id.widget_cw_progress_3,
        )
        private val ROW_CONTAINER_IDS = intArrayOf(
            R.id.widget_cw_row_1,
            R.id.widget_cw_row_2,
            R.id.widget_cw_row_3,
        )
        private val ROW_POSTER_IDS = intArrayOf(
            R.id.widget_cw_poster_1,
            R.id.widget_cw_poster_2,
            R.id.widget_cw_poster_3,
        )

        /** Glide bitmap fetch for widget posters — callers are on Dispatchers.IO. */
        private fun loadPosterBitmap(context: Context, url: String?): android.graphics.Bitmap? {
            if (url.isNullOrBlank()) return null
            val radiusPx = (8f * context.resources.displayMetrics.density).toInt()
            return runCatching {
                com.bumptech.glide.Glide.with(context)
                    .asBitmap()
                    .load(url)
                    .transform(
                        com.bumptech.glide.load.resource.bitmap.CenterCrop(),
                        com.bumptech.glide.load.resource.bitmap.RoundedCorners(radiusPx),
                    )
                    .submit(120, 176)
                    .get()
            }.getOrNull()
        }

        fun buildViews(
            context: Context,
            rows: List<WidgetLibrary.ContinueRow>,
            appWidgetId: Int,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_continue_watching)
            views.setTextViewText(
                R.id.widget_cw_header,
                context.getString(R.string.widget_continue_watching_title),
            )
            val openHome = homePi(context, appWidgetId)
            views.setOnClickPendingIntent(R.id.widget_cw_header, openHome)
            views.setOnClickPendingIntent(R.id.widget_cw_root, openHome)

            if (rows.isEmpty()) {
                views.setViewVisibility(R.id.widget_cw_empty, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_cw_empty,
                    context.getString(R.string.widget_continue_watching_empty),
                )
                ROW_CONTAINER_IDS.forEach { views.setViewVisibility(it, View.GONE) }
                return views
            }

            views.setViewVisibility(R.id.widget_cw_empty, View.GONE)
            ROW_CONTAINER_IDS.forEachIndexed { index, containerId ->
                val row = rows.getOrNull(index)
                if (row == null) {
                    views.setViewVisibility(containerId, View.GONE)
                    return@forEachIndexed
                }
                views.setViewVisibility(containerId, View.VISIBLE)
                val posterView = ROW_POSTER_IDS[index]
                val bitmap = loadPosterBitmap(context, row.poster)
                if (bitmap != null) {
                    views.setImageViewBitmap(posterView, bitmap)
                } else {
                    views.setImageViewResource(posterView, R.drawable.glide_fallback_cover)
                }
                val label = if (row.subtitle.isBlank()) {
                    row.title
                } else {
                    "${row.title} · ${row.subtitle}"
                }
                views.setTextViewText(ROW_TITLE_IDS[index], label)
                views.setTextViewText(
                    ROW_SUB_IDS[index],
                    if (row.progressPercent > 0) {
                        context.getString(R.string.continue_watching_percent, row.progressPercent)
                    } else {
                        row.provider
                    },
                )
                views.setProgressBar(
                    ROW_PROGRESS_IDS[index],
                    100,
                    row.progressPercent.coerceIn(0, 100),
                    false,
                )
                views.setOnClickPendingIntent(
                    containerId,
                    deepLinkPi(context, row.deepLink, appWidgetId, index + 1),
                )
            }
            return views
        }

        private fun homePi(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, MainMobileActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            return PendingIntent.getActivity(
                context,
                2100 + appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
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
                appWidgetId * 10 + requestSalt,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
