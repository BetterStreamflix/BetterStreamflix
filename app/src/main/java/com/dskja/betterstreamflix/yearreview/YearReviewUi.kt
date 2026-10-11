package com.dskja.betterstreamflix.yearreview

import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings entry UI for the local year review (Mobile + TV).
 */
object YearReviewUi {

    fun show(fragment: Fragment) {
        val context = fragment.requireContext()
        val themed = ExpDialogChrome.themedContext(context)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val content = LayoutInflater.from(themed)
            .inflate(R.layout.dialog_year_review, null, false)

        val subtitle = content.findViewById<TextView>(R.id.tv_year_review_subtitle)
        val hoursView = content.findViewById<TextView>(R.id.tv_year_review_hours)
        val countsView = content.findViewById<TextView>(R.id.tv_year_review_counts)
        val topView = content.findViewById<TextView>(R.id.tv_year_review_top_series)
        val bingeView = content.findViewById<TextView>(R.id.tv_year_review_binge)
        val emptyView = content.findViewById<TextView>(R.id.tv_year_review_empty)
        val loading = content.findViewById<ProgressBar>(R.id.pb_year_review_loading)
        val topHeading = content.findViewById<TextView>(R.id.tv_year_review_top_heading)
        val bingeHeading = content.findViewById<TextView>(R.id.tv_year_review_binge_heading)
        val hoursLabel = content.findViewById<TextView>(R.id.tv_year_review_hours_label)

        hoursView.alpha = 0f
        countsView.alpha = 0f
        topView.alpha = 0f
        bingeView.alpha = 0f

        var loadedStats: YearReviewStats? = null

        val builder = if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(themed)
        } else {
            AlertDialog.Builder(themed)
        }

        val dialog = builder
            .setTitle(context.getString(R.string.year_review_title, year))
            .setView(content)
            .setPositiveButton(R.string.year_review_share, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val shareBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            shareBtn.isEnabled = false
            shareBtn.setOnClickListener {
                val stats = loadedStats ?: return@setOnClickListener
                ExpMotion.hapticTap(shareBtn)
                YearReviewShare.share(context.applicationContext, stats)
            }
        }

        dialog.show()

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                YearReviewRepository.load(context.applicationContext, year)
            }
            if (!fragment.isAdded) return@launch
            loading.visibility = View.GONE
            loadedStats = stats

            subtitle.text = context.getString(
                R.string.year_review_subtitle,
                stats.profileName,
            )

            if (!stats.hasActivity) {
                hoursView.visibility = View.GONE
                hoursLabel.visibility = View.GONE
                countsView.visibility = View.GONE
                topHeading.visibility = View.GONE
                topView.visibility = View.GONE
                bingeHeading.visibility = View.GONE
                bingeView.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
                return@launch
            }

            hoursView.text = YearReviewCardRenderer.formatHours(stats.totalHours)
            countsView.text = context.getString(
                R.string.year_review_counts_line,
                stats.movieCount,
                stats.episodeCount,
            )
            topView.text = if (stats.topSeries.isEmpty()) {
                context.getString(R.string.year_review_empty_series)
            } else {
                stats.topSeries.mapIndexed { index, series ->
                    context.getString(
                        R.string.year_review_series_line,
                        index + 1,
                        series.title,
                        series.episodeCount,
                    )
                }.joinToString("\n")
            }

            val binge = stats.longestBinge
            bingeView.text = if (binge == null) {
                context.getString(R.string.year_review_empty_binge)
            } else {
                val df = SimpleDateFormat("MMM d", Locale.getDefault())
                context.getString(
                    R.string.year_review_binge_line,
                    binge.title,
                    binge.episodeCount,
                    YearReviewCardRenderer.formatHours(binge.estimatedMinutes / 60.0),
                    df.format(Date(binge.startMs)),
                    df.format(Date(binge.endMs)),
                )
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true

            // Light presence motion for the stat blocks.
            listOf(hoursView, countsView, topView, bingeView).forEachIndexed { index, view ->
                view.animate()
                    .alpha(1f)
                    .setStartDelay(40L * index)
                    .setDuration(220L)
                    .start()
            }
        }
    }
}
