package com.betterstreamflix.app.yearreview

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.betterstreamflix.app.R

object YearReviewShare {

    fun caption(context: Context, stats: YearReviewStats): String {
        val hours = YearReviewCardRenderer.formatHours(stats.totalHours)
        val top = stats.topSeries.firstOrNull()?.title
        val binge = stats.longestBinge
        val lines = mutableListOf(
            context.getString(R.string.year_review_share_headline, stats.year, hours),
        )
        if (top != null) {
            lines += context.getString(R.string.year_review_share_top, top)
        }
        if (binge != null) {
            lines += context.getString(
                R.string.year_review_share_binge,
                binge.title,
                binge.episodeCount,
            )
        }
        lines += context.getString(R.string.year_review_share_footer)
        return lines.joinToString("\n")
    }

    fun share(context: Context, stats: YearReviewStats) {
        val text = caption(context, stats)
        val file = YearReviewCardRenderer.renderToShareFile(context, stats)
        val uri: Uri? = file?.let {
            runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    it,
                )
            }.getOrNull()
        }

        val intent = if (uri != null) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.year_review_share_subject, stats.year))
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(
                    context.contentResolver,
                    "year_review",
                    uri,
                )
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.year_review_share_subject, stats.year))
                putExtra(Intent.EXTRA_TEXT, text)
            }
        }

        val chooser = Intent.createChooser(
            intent,
            context.getString(R.string.year_review_share_chooser),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
