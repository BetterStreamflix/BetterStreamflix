package com.betterstreamflix.app.ui

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Material caps [BottomNavigationView] at 6 items; our mobile shell needs 7
 * (Home / Live / Movies / TV / Favorites / Downloads / Settings).
 * Inflating 7 items otherwise crashes at startup (BETTERSTREAMFLIX-3N).
 */
class WideBottomNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.bottomNavigationStyle,
) : BottomNavigationView(context, attrs, defStyleAttr) {
    override fun getMaxItemCount(): Int = 7
}
