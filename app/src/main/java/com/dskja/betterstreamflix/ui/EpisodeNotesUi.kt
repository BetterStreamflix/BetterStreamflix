package com.dskja.betterstreamflix.ui

import android.app.Activity
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.utils.EpisodeNotesStore
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.dp

/**
 * Mobile + TV editor for short spoiler-free episode notes (“where was I?”).
 */
object EpisodeNotesUi {

    fun showEditor(
        context: Context,
        episode: Episode,
        onChanged: ((String?) -> Unit)? = null,
    ) {
        val host = ExpDialogChrome.themedContext(context)
        val appCtx = context.applicationContext
        val existing = EpisodeNotesStore.get(appCtx, episode.id).orEmpty()

        val pad = 20.dp(host)
        val container = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val hintLabel = TextView(host).apply {
            setText(R.string.episode_note_hint)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(host.getColor(R.color.cinema_text_muted))
        }
        val input = EditText(host).apply {
            setText(existing)
            setSelection(text?.length ?: 0)
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(EpisodeNotesStore.MAX_CHARS))
            setHint(R.string.episode_note_placeholder)
            setTextColor(host.getColor(R.color.cinema_text))
            setHintTextColor(host.getColor(R.color.cinema_text_muted))
            setBackgroundResource(R.drawable.bg_search_edittext)
            val inner = 12.dp(host)
            setPadding(14.dp(host), inner, 14.dp(host), inner)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 10.dp(host) }
        }
        val counter = TextView(host).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
            setTextColor(host.getColor(R.color.cinema_text_muted))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 6.dp(host) }
        }
        fun refreshCounter() {
            counter.text = host.getString(
                R.string.episode_note_counter,
                input.text?.length ?: 0,
                EpisodeNotesStore.MAX_CHARS,
            )
        }
        refreshCounter()
        input.doAfterTextChanged { refreshCounter() }

        container.addView(hintLabel)
        container.addView(input)
        container.addView(counter)

        val frame = FrameLayout(host).apply {
            addView(
                container,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val builder = androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle(R.string.episode_note_title)
            .setView(frame)
            .setPositiveButton(R.string.episode_note_save) { dialog, _ ->
                val provider = UserPreferences.currentProvider?.name.orEmpty()
                val showId = episode.tvShow?.id.orEmpty()
                val saved = EpisodeNotesStore.set(
                    appCtx,
                    episode.id,
                    input.text?.toString(),
                    providerName = provider,
                    showId = showId,
                )
                onChanged?.invoke(saved)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.option_cancel, null)

        if (existing.isNotBlank()) {
            builder.setNeutralButton(R.string.episode_note_clear) { dialog, _ ->
                EpisodeNotesStore.clear(
                    appCtx,
                    episode.id,
                    providerName = UserPreferences.currentProvider?.name.orEmpty(),
                    showId = episode.tvShow?.id.orEmpty(),
                )
                onChanged?.invoke(null)
                dialog.dismiss()
            }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            ExpDialogChrome.polishShown(dialog)
            if (context !is Activity || !context.isFinishing) {
                input.requestFocus()
            }
        }
        dialog.show()
    }

    fun optionLabel(context: Context, episodeId: String): String {
        return if (EpisodeNotesStore.has(context, episodeId)) {
            context.getString(R.string.option_episode_note_edit)
        } else {
            context.getString(R.string.option_episode_note_add)
        }
    }
}
