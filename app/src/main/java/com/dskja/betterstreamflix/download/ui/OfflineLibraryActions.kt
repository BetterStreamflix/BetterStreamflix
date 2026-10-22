package com.dskja.betterstreamflix.download.ui

import android.content.Context
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.download.DownloadItemEntity
import com.dskja.betterstreamflix.download.OfflineLibrary
import com.dskja.betterstreamflix.utils.ExpDialogChrome

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
