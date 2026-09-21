package com.dskja.betterstreamflix.cast

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity

/** Full-screen Cast media controls (required by CastOptions notification target). */
class CastExpandedControllerActivity : ExpandedControllerActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (ExperimentalMobileDesign.enabled()) {
            setTheme(ExperimentalMobileDesign.themeRes())
        }
        super.onCreate(savedInstanceState)
        if (ExperimentalMobileDesign.enabled()) {
            ExperimentalMobileDesign.applyDynamicColors(this)
            window?.decorView?.let { decor ->
                ExperimentalMobileDesign.applyReducedGlass(decor)
                ExpMotion.enterScreen(decor)
                decor.post { polishCastControls(decor) }
            }
        }
    }

    private fun polishCastControls(root: View) {
        val onSurface = com.google.android.material.color.MaterialColors.getColor(
            root,
            com.google.android.material.R.attr.colorOnSurface,
        )
        val titleViews = mutableListOf<TextView>()
        val chipViews = mutableListOf<View>()
        fun walk(view: View) {
            when (view) {
                is TextView -> {
                    if (view.text.isNotBlank() && view.visibility == View.VISIBLE) {
                        titleViews += view
                    }
                }
            }
            val chipTarget = view is android.widget.ImageButton ||
                view is android.widget.ImageView && view.isClickable ||
                view is android.widget.Button
            if (chipTarget) {
                view.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
                view.applyExpPress()
                when (view) {
                    is android.widget.ImageButton ->
                        view.imageTintList =
                            android.content.res.ColorStateList.valueOf(onSurface)
                    is android.widget.ImageView ->
                        view.imageTintList =
                            android.content.res.ColorStateList.valueOf(onSurface)
                }
                chipViews += view
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    walk(view.getChildAt(i))
                }
            }
        }
        walk(root)
        titleViews.take(2).forEachIndexed { index, tv ->
            tv.postDelayed({ ExpMotion.revealHeader(tv) }, 40L * index)
        }
        chipViews.take(12).forEachIndexed { index, chip ->
            chip.postDelayed({ ExpMotion.popIn(chip) }, 50L + 28L * index)
        }
    }
}
