package com.dskja.betterstreamflix.taste

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shared overflow dialog: More like this / Not interested / Hide.
 */
object TasteSignalsUi {

    fun showFor(
        fragment: Fragment,
        item: AppAdapter.Item,
        onChanged: (() -> Unit)? = null,
        onMoreLike: ((AppAdapter.Item) -> Unit)? = null,
    ) {
        if (item !is Movie && item !is TvShow) return
        val context = fragment.requireContext()
        val themed = ExpDialogChrome.themedContext(context)
        val title = when (item) {
            is Movie -> item.title
            is TvShow -> item.title
            else -> ""
        }
        val labels = arrayOf(
            context.getString(R.string.taste_action_more_like),
            context.getString(R.string.taste_action_not_interested),
            context.getString(R.string.taste_action_hide),
        )
        val builder = if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(themed)
        } else {
            AlertDialog.Builder(themed)
        }
        builder
            .setTitle(title.ifBlank { context.getString(R.string.taste_actions_title) })
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> {
                        TasteFeedback.moreLike(context, item)
                        onMoreLike?.invoke(item) ?: onChanged?.invoke()
                    }
                    1 -> {
                        TasteFeedback.notInterested(context, item)
                        onChanged?.invoke()
                    }
                    2 -> {
                        TasteFeedback.hide(context, item)
                        onChanged?.invoke()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener { ExpDialogChrome.polishShown(dialog) }
                dialog.show()
            }
    }

    fun showClearHidden(
        context: Context,
        onCleared: (() -> Unit)? = null,
    ) {
        val themed = ExpDialogChrome.themedContext(context)
        val count = TasteGraph.negativeCount(context)
        val builder = if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(themed)
        } else {
            AlertDialog.Builder(themed)
        }
        builder
            .setTitle(R.string.taste_clear_hidden_title)
            .setMessage(
                context.getString(R.string.taste_clear_hidden_message, count),
            )
            .setPositiveButton(R.string.taste_clear_hidden_confirm) { _, _ ->
                TasteGraph.clearNegatives(context)
                onCleared?.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener { ExpDialogChrome.polishShown(dialog) }
                dialog.show()
            }
    }

    fun showClearAll(
        context: Context,
        onCleared: (() -> Unit)? = null,
    ) {
        val themed = ExpDialogChrome.themedContext(context)
        val builder = if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(themed)
        } else {
            AlertDialog.Builder(themed)
        }
        builder
            .setTitle(R.string.taste_clear_all_title)
            .setMessage(R.string.taste_clear_all_message)
            .setPositiveButton(R.string.taste_clear_all_confirm) { _, _ ->
                TasteGraph.clearAll(context)
                onCleared?.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener { ExpDialogChrome.polishShown(dialog) }
                dialog.show()
            }
    }
}
