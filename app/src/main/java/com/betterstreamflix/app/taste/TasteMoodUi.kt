package com.betterstreamflix.app.taste

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.betterstreamflix.app.R
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.utils.ExpMotion

/**
 * Binds mood chips into the Search mood strip (Mobile + TV).
 */
object TasteMoodUi {

    data class Bound(
        val firstChip: View?,
        val lastChip: View?,
        val chips: List<View>,
    )

    fun bind(
        context: Context,
        row: LinearLayout,
        selected: TasteMood,
        padH: Int,
        padV: Int,
        gap: Int,
        tvFocusable: Boolean,
        onSelect: (TasteMood) -> Unit,
        applyPress: ((View) -> Unit)? = null,
    ): View? = bindDetailed(
        context, row, selected, padH, padV, gap, tvFocusable, onSelect, applyPress,
    ).firstChip

    fun bindDetailed(
        context: Context,
        row: LinearLayout,
        selected: TasteMood,
        padH: Int,
        padV: Int,
        gap: Int,
        tvFocusable: Boolean,
        onSelect: (TasteMood) -> Unit,
        applyPress: ((View) -> Unit)? = null,
    ): Bound {
        row.removeAllViews()
        val label = TextView(context).apply {
            text = context.getString(R.string.taste_mood_label)
            setTextColor(ContextCompat.getColor(context, R.color.cinema_text_muted))
            textSize = if (tvFocusable) 14f else 12f
            setPadding(0, padV, gap, padV)
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(label)

        val moods = TasteMood.entriesForProfile(ProfileManager.isKidsActive())
        // If Intense was sticky from an adult session, clamp for Kids.
        val effectiveSelected =
            if (selected in moods) selected else TasteMood.ALL

        val chips = mutableListOf<View>()
        for (mood in moods) {
            val chip = TextView(context).apply {
                if (tvFocusable) id = View.generateViewId()
                text = labelFor(context, mood)
                textSize = if (tvFocusable) 15f else 13f
                val selectedNow = mood == effectiveSelected
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (selectedNow) R.color.cinema_cta_ink else R.color.cinema_text_soft,
                    ),
                )
                setBackgroundResource(
                    if (selectedNow) R.drawable.bg_search_sort_chip_selected
                    else R.drawable.bg_search_sort_chip,
                )
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
                if (tvFocusable) isFocusableInTouchMode = true
                contentDescription = context.getString(
                    R.string.taste_mood_chip_cd,
                    labelFor(context, mood),
                )
                setOnClickListener {
                    ExpMotion.hapticTap(it)
                    onSelect(mood)
                }
            }
            applyPress?.invoke(chip)
            chips += chip
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = gap }
            row.addView(chip, lp)
        }
        // Horizontal DPAD chain among mood chips.
        if (tvFocusable && chips.isNotEmpty()) {
            chips.forEachIndexed { index, chip ->
                chip.nextFocusLeftId = chips.getOrNull(index - 1)?.id ?: View.NO_ID
                chip.nextFocusRightId = chips.getOrNull(index + 1)?.id ?: View.NO_ID
            }
        }
        return Bound(
            firstChip = chips.firstOrNull(),
            lastChip = chips.lastOrNull(),
            chips = chips,
        )
    }

    fun labelFor(context: Context, mood: TasteMood): String = when (mood) {
        TasteMood.ALL -> context.getString(R.string.taste_mood_all)
        TasteMood.CHILL -> context.getString(R.string.taste_mood_chill)
        TasteMood.INTENSE -> context.getString(R.string.taste_mood_intense)
        TasteMood.KIDS -> context.getString(R.string.taste_mood_kids)
        TasteMood.DE_AUDIO -> context.getString(R.string.taste_mood_de)
    }
}
