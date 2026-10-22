package com.betterstreamflix.app.download.ui

import android.content.Context
import com.betterstreamflix.app.R
import com.betterstreamflix.app.download.DownloadItemEntity
import com.betterstreamflix.app.download.OfflineLibrary
import com.betterstreamflix.app.utils.ExpDialogChrome

object OfflineLibraryActions {
    fun showSeriesEpisodePicker(
        context: Context,
        series: OfflineLibrary.Entry.SeriesItem,
        onPick: (DownloadItemEntity) -> Unit,
    ) {
        val labels = series.episodes.map(OfflineLibrary::episodeLabel).toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(
                context.getString(
                    R.string.offline_library_series_episodes_title,
                    series.title,
                ),
            )
            .setItems(labels) { _, which ->
                series.episodes.getOrNull(which)?.let(onPick)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener { ExpDialogChrome.polishButtons(dialog) }
                dialog.show()
            }
    }
}
