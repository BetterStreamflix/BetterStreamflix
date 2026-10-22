package com.betterstreamflix.app.fragments.search

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.betterstreamflix.app.R
import com.betterstreamflix.app.utils.ExpMotion

/**
 * Shared binder for the Search Memories chip strip (Mobile + TV).
 */
object SearchMemoriesUi {

    data class BindResult(
        val firstChip: View?,
        val queries: Set<String>,
    )

    fun bind(
        context: Context,
        row: LinearLayout,
        memories: List<SearchMemories.Entry>,
        padH: Int,
        padV: Int,
        gap: Int,
        tvFocusable: Boolean,
        chipBackground: (SearchMemories.Outcome) -> Int,
        onRetry: (SearchMemories.Entry) -> Unit,
        onClear: () -> Unit,
        onLongPressMoreLike: ((SearchMemories.Entry) -> Unit)? = null,
        animateNew: ((TextView, Int) -> Unit)? = null,
        previousQueries: Set<String> = emptySet(),
        labelColor: Int? = null,
        clearColor: Int? = null,
        applyPress: ((View) -> Unit)? = null,
    ): BindResult {
        row.removeAllViews()
        val label = TextView(context).apply {
            text = context.getString(R.string.search_memories_title)
            setTextColor(
                labelColor
                    ?: ContextCompat.getColor(context, R.color.cinema_text_muted),
            )
            textSize = if (tvFocusable) 14f else 12f
            setPadding(0, padV, gap, padV)
        }
        row.addView(label)

        var firstChip: View? = null
        memories.forEachIndexed { index, entry ->
            val chip = TextView(context).apply {
                if (tvFocusable) id = View.generateViewId()
                text = labelFor(context, entry)
                textSize = if (tvFocusable) 15f else 13f
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        when (entry.outcome) {
                            SearchMemories.Outcome.FAILED -> R.color.cinema_text_soft
                            SearchMemories.Outcome.EMPTY -> R.color.cinema_text_soft
                            SearchMemories.Outcome.SUCCESS -> R.color.cinema_text
                        },
                    ),
                )
                setBackgroundResource(chipBackground(entry.outcome))
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
                if (tvFocusable) isFocusableInTouchMode = true
                contentDescription = contentDescriptionFor(context, entry)
                setOnClickListener {
                    ExpMotion.hapticTap(it)
                    it.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).withEndAction {
                        it.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    }.start()
                    onRetry(entry)
                }
                if (onLongPressMoreLike != null && entry.outcome == SearchMemories.Outcome.SUCCESS) {
                    setOnLongClickListener {
                        ExpMotion.hapticTap(it)
                        onLongPressMoreLike(entry)
                        true
                    }
                    // Keep retry CD and append more-like affordance for TalkBack.
                    contentDescription = buildString {
                        append(contentDescriptionFor(context, entry))
                        append(". ")
                        append(
                            context.getString(
                                R.string.taste_memory_more_like_cd,
                                entry.query,
                            ),
                        )
                    }
                }
            }
            applyPress?.invoke(chip)
            if (firstChip == null) firstChip = chip
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = gap }
            row.addView(chip, lp)
            if (entry.query !in previousQueries) {
                animateNew?.invoke(chip, index)
            }
        }

        val clear = TextView(context).apply {
            text = context.getString(R.string.search_memories_clear)
            textSize = if (tvFocusable) 14f else 12f
            setTextColor(
                clearColor
                    ?: ContextCompat.getColor(context, R.color.cinema_text_muted),
            )
            setPadding(padH, padV, padH, padV)
            contentDescription = context.getString(R.string.search_memories_clear_cd)
            isClickable = true
            isFocusable = true
            if (tvFocusable) isFocusableInTouchMode = true
            setOnClickListener {
                ExpMotion.hapticTap(it)
                onClear()
            }
        }
        applyPress?.invoke(clear)
        row.addView(clear)
        return BindResult(firstChip = firstChip, queries = memories.map { it.query }.toSet())
    }

    fun labelFor(context: Context, entry: SearchMemories.Entry): String = when (entry.outcome) {
        SearchMemories.Outcome.FAILED ->
            context.getString(R.string.search_memories_chip_failed, entry.query)
        SearchMemories.Outcome.EMPTY ->
            context.getString(R.string.search_memories_chip_empty, entry.query)
        SearchMemories.Outcome.SUCCESS -> entry.query
    }

    fun contentDescriptionFor(context: Context, entry: SearchMemories.Entry): String =
        context.getString(R.string.search_memories_retry_cd, entry.query)
}
