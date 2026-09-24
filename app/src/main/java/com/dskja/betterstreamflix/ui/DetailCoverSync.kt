package com.dskja.betterstreamflix.ui

import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView

/**
 * Keeps the fragment detail banner as tall as the hero block (logo → overview),
 * so artwork runs continuously down to the tabs instead of stopping at 16:9.
 */
object DetailCoverSync {

    fun bind(recyclerView: RecyclerView, banner: ImageView) {
        recyclerView.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    if (recyclerView.getChildAdapterPosition(view) == 0) {
                        view.post { syncHeight(recyclerView, banner) }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            },
        )
        recyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncHeight(recyclerView, banner)
        }
        recyclerView.post { syncHeight(recyclerView, banner) }
    }

    fun syncHeight(recyclerView: RecyclerView, banner: ImageView) {
        val hero = recyclerView.findViewHolderForAdapterPosition(0)?.itemView ?: return
        val target = hero.height
        if (target <= 0) return
        val lp = banner.layoutParams as? ConstraintLayout.LayoutParams ?: return
        if (lp.height == target && lp.dimensionRatio.isNullOrEmpty()) return
        lp.dimensionRatio = null
        lp.height = target
        lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        lp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        banner.layoutParams = lp
    }
}
