package com.dskja.betterstreamflix.fragments.search

import android.content.Context
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.SearchSort
import com.dskja.betterstreamflix.utils.SearchSortMode

object SearchSortUi {

    fun styleChip(chip: TextView, selected: Boolean) {
        chip.isSelected = selected
        chip.setBackgroundResource(
            if (selected) R.drawable.bg_search_sort_chip_selected else R.drawable.bg_search_sort_chip
        )
        chip.setTextColor(
            if (selected) {
                chip.context.getColor(R.color.cinema_cta_ink)
            } else {
                chip.context.getColor(R.color.cinema_text_soft)
            }
        )
        chip.refreshDrawableState()
    }

    fun yearChipLabel(context: Context, year: Int?): String =
        if (year == null) {
            context.getString(R.string.search_filter_year)
        } else {
            context.getString(R.string.search_filter_year_selected, year)
        }

    fun bindChips(
        chipDefault: TextView,
        chipNewest: TextView,
        chipYear: TextView,
        mode: SearchSortMode,
        year: Int?,
    ) {
        styleChip(chipDefault, mode == SearchSortMode.PROVIDER_DEFAULT)
        styleChip(chipNewest, mode == SearchSortMode.NEWEST_FIRST)
        styleChip(chipYear, year != null)
        chipYear.text = yearChipLabel(chipYear.context, year)
    }

    fun showYearPicker(
        context: Context,
        currentYear: Int?,
        resultYears: List<Int>,
        onPicked: (Int?) -> Unit,
    ) {
        val window = SearchSort.yearPickerOptions(emptyList())
        val options = (resultYears + window).distinct().sortedDescending()
        val labels = mutableListOf(context.getString(R.string.search_filter_all_years))
        labels += options.map { it.toString() }
        val checked = when (currentYear) {
            null -> 0
            else -> options.indexOf(currentYear).let { if (it >= 0) it + 1 else 0 }
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.search_filter_year_title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                onPicked(if (which == 0) null else options[which - 1])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.option_cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    ExpDialogChrome.polishShown(dialog)
                    dialog.listView?.apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()
                    }
                }
                dialog.show()
            }
    }
}
