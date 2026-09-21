package com.dskja.betterstreamflix.utils

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.google.android.material.color.MaterialColors

/** Spinner dropdown rows with Lumina selected chrome. */
class ExpSpinnerAdapter(
    context: Context,
    private val itemLayout: Int,
    private val dropdownLayout: Int,
    items: List<String>,
    private val selectedIndexProvider: () -> Int,
) : ArrayAdapter<String>(context, itemLayout, items) {

    init {
        setDropDownViewResource(dropdownLayout)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        if (ExperimentalMobileDesign.enabled()) {
            (view as? TextView)?.apply {
                setTextColor(
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface),
                )
                setPadding(
                    paddingLeft.coerceAtLeast((12 * resources.displayMetrics.density).toInt()),
                    paddingTop,
                    (36 * resources.displayMetrics.density).toInt(),
                    paddingBottom,
                )
            }
        }
        return view
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getDropDownView(position, convertView, parent)
        if (!ExperimentalMobileDesign.enabled()) return view
        val selected = position == selectedIndexProvider()
        view.setBackgroundResource(
            if (selected) ExperimentalMobileDesign.primaryButtonBackground()
            else ExperimentalMobileDesign.optionItemBackground(),
        )
        (view as? TextView)?.setTextColor(
            MaterialColors.getColor(
                view,
                if (selected) com.google.android.material.R.attr.colorOnPrimary
                else com.google.android.material.R.attr.colorOnSurface,
            ),
        )
        with(ExpPressEffects) { view.applyExpPress() }
        return view
    }
}
