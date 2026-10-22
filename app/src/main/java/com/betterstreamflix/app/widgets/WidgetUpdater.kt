package com.betterstreamflix.app.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Broadcasts [AppWidgetManager.ACTION_APPWIDGET_UPDATE] for both home-screen widgets. */
object WidgetUpdater {

    fun requestRefresh(context: Context?) {
        val app = context?.applicationContext ?: return
        // Skip leanback-only installs — widgets are a phone surface.
        if (app.packageManager.hasSystemFeature("android.software.leanback") &&
            !app.packageManager.hasSystemFeature("android.hardware.touchscreen")
        ) {
            return
        }
        notifyProvider(app, ContinueWatchingWidgetProvider::class.java)
        notifyProvider(app, LiveFavoritesWidgetProvider::class.java)
    }

    private fun notifyProvider(context: Context, clazz: Class<*>) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, clazz))
        if (ids.isEmpty()) return
        val intent = Intent(context, clazz).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        context.sendBroadcast(intent)
    }
}
