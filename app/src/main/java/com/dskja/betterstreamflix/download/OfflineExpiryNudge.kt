package com.dskja.betterstreamflix.download

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.ExpDialogChrome

/**
 * One-shot keep / delete dialog for stale offline downloads.
 * Call [maybeShow] from Downloads onResume — never from progress tickers.
 */
object OfflineExpiryNudge {

    fun maybeShow(
        fragment: Fragment,
        stale: List<DownloadItemEntity>,
        onKeep: (List<DownloadItemEntity>) -> Unit,
        onDelete: (List<DownloadItemEntity>) -> Unit,
    ) {
        if (!fragment.isAdded || stale.isEmpty()) return
        if (!OfflineExpiryPolicy.shouldPrompt()) return
        val context = fragment.requireContext()
        OfflineExpiryPolicy.recordShown()
        val message = buildMessage(context, stale)
        AlertDialog.Builder(context)
            .setTitle(R.string.offline_expiry_nudge_title)
            .setMessage(message)
            .setPositiveButton(R.string.offline_expiry_nudge_keep) { _, _ ->
                OfflineExpiryPolicy.recordDismissed()
                onKeep(stale)
            }
            .setNegativeButton(R.string.offline_expiry_nudge_delete) { _, _ ->
                OfflineExpiryPolicy.recordDismissed()
                onDelete(stale)
            }
            .setNeutralButton(R.string.offline_expiry_nudge_later) { _, _ ->
                OfflineExpiryPolicy.recordDismissed()
            }
            .setOnCancelListener {
                OfflineExpiryPolicy.recordDismissed()
            }
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    ExpDialogChrome.polishButtons(dialog)
                }
                dialog.show()
            }
    }

    private fun buildMessage(context: Context, stale: List<DownloadItemEntity>): String {
        val (titles, extra) = OfflineExpiryPolicy.formatTitleLines(stale)
        val header = context.resources.getQuantityString(
            R.plurals.offline_expiry_nudge_body,
            stale.size,
            stale.size,
        )
        val list = buildString {
            titles.forEach { append("\n• ").append(it) }
            if (extra > 0) {
                append('\n')
                append(context.getString(R.string.offline_expiry_nudge_more, extra))
            }
        }
        return header + list
    }
}
