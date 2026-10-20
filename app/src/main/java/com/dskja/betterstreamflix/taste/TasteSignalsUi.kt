package com.dskja.betterstreamflix.taste

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/**
 * Shared overflow dialog: More like this / Not interested / Hide (+ optional Favorite).
 */
object TasteSignalsUi {

    fun showFor(
        fragment: Fragment,
        item: AppAdapter.Item,
        onChanged: (() -> Unit)? = null,
        onMoreLike: ((AppAdapter.Item) -> Unit)? = null,
        includeFavorite: Boolean = false,
        onFavoriteToggle: (() -> Unit)? = null,
        snackbarAnchor: View? = null,
    ) {
        if (item !is Movie && item !is TvShow) return
        val context = fragment.requireContext()
        val themed = ExpDialogChrome.themedContext(context)
        val title = when (item) {
            is Movie -> item.title
            is TvShow -> item.title
            else -> ""
        }
        val labels = buildList {
            if (includeFavorite) {
                val inList = when (item) {
                    is Movie -> item.isFavorite
                    is TvShow -> item.isFavorite
                    else -> false
                }
                add(
                    context.getString(
                        if (inList) R.string.home_swiper_in_my_list
                        else R.string.home_swiper_my_list,
                    ),
                )
            }
            add(context.getString(R.string.taste_action_more_like))
            add(context.getString(R.string.taste_action_not_interested))
            add(context.getString(R.string.taste_action_hide))
        }.toTypedArray()
        val favoriteOffset = if (includeFavorite) 1 else 0
        val builder = if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(themed)
        } else {
            AlertDialog.Builder(themed)
        }
        builder
            .setTitle(title.ifBlank { context.getString(R.string.taste_actions_title) })
            .setItems(labels) { _, which ->
                when {
                    includeFavorite && which == 0 -> onFavoriteToggle?.invoke()
                    which == favoriteOffset -> {
                        TasteFeedback.moreLike(context, item)
                        onMoreLike?.invoke(item) ?: onChanged?.invoke()
                    }
                    which == favoriteOffset + 1 -> {
                        TasteFeedback.notInterested(context, item)
                        showUndoSnackbar(
                            fragment,
                            snackbarAnchor,
                            context.getString(R.string.taste_not_interested_snackbar),
                            item,
                            TasteSignalKind.NOT_INTERESTED,
                            onChanged,
                        )
                        onChanged?.invoke()
                    }
                    which == favoriteOffset + 2 -> {
                        TasteFeedback.hide(context, item)
                        showUndoSnackbar(
                            fragment,
                            snackbarAnchor,
                            context.getString(R.string.taste_hidden_snackbar),
                            item,
                            TasteSignalKind.HIDDEN,
                            onChanged,
                        )
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

    private fun showUndoSnackbar(
        fragment: Fragment,
        anchor: View?,
        message: String,
        item: AppAdapter.Item,
        kind: TasteSignalKind,
        onChanged: (() -> Unit)?,
    ) {
        val view = anchor
            ?: fragment.view
            ?: return
        Snackbar.make(view, message, 5_000)
            .setAction(R.string.taste_undo_hide) {
                val app = fragment.requireContext().applicationContext
                // Undo: drop the signal we just wrote for this identity+kind.
                val key = when (item) {
                    is Movie -> "movie:${item.id}"
                    is TvShow -> "tvshow:${item.id}"
                    else -> return@setAction
                }
                val kept = TasteGraphStore.loadSignals(app).filterNot {
                    it.identityKey == key && it.kind == kind
                }
                TasteGraphStore.saveSignals(app, kept)
                TasteGraph.invalidate()
                onChanged?.invoke()
            }
            .show()
    }

    fun showClearHidden(
        context: Context,
        onCleared: (() -> Unit)? = null,
    ) {
        val themed = ExpDialogChrome.themedContext(context)
        val builder = if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(themed)
        } else {
            AlertDialog.Builder(themed)
        }
        val view = android.view.LayoutInflater.from(themed)
            .inflate(R.layout.dialog_taste_hidden, null, false)
        val countView = view.findViewById<android.widget.TextView>(R.id.tv_taste_hidden_count)
        val emptyView = view.findViewById<android.widget.TextView>(R.id.tv_taste_hidden_empty)
        val list = view.findViewById<android.widget.LinearLayout>(R.id.ll_taste_hidden_list)

        val dialog = builder
            .setTitle(R.string.taste_clear_hidden_title)
            .setView(view)
            .setPositiveButton(R.string.taste_clear_hidden_confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun negativeEntries(): List<TasteSignalEntry> =
            TasteGraphStore.loadSignals(context).filter {
                it.kind == TasteSignalKind.HIDDEN || it.kind == TasteSignalKind.NOT_INTERESTED
            }.distinctBy { it.identityKey }

        fun refreshList() {
            val entries = negativeEntries()
            countView.text = context.getString(
                R.string.taste_clear_hidden_message,
                entries.size,
            )
            emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            list.removeAllViews()
            entries.take(40).forEach { entry ->
                val row = android.view.LayoutInflater.from(themed)
                    .inflate(R.layout.item_taste_hidden, list, false)
                row.findViewById<android.widget.TextView>(R.id.tv_taste_hidden_title).text =
                    entry.title.ifBlank { entry.identityKey }
                row.findViewById<android.widget.TextView>(R.id.btn_taste_hidden_restore)
                    .setOnClickListener {
                        val app = context.applicationContext
                        val kept = TasteGraphStore.loadSignals(app)
                            .filterNot { it.identityKey == entry.identityKey }
                        TasteGraphStore.saveSignals(app, kept)
                        TasteGraph.invalidate()
                        refreshList()
                        onCleared?.invoke()
                    }
                list.addView(row)
            }
            val hasEntries = entries.isNotEmpty()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = hasEntries
        }

        dialog.setOnShowListener {
            ExpDialogChrome.polishShown(dialog)
            refreshList()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                TasteGraph.clearNegatives(context)
                refreshList()
                onCleared?.invoke()
                dialog.dismiss()
            }
        }
        dialog.show()
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
