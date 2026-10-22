package com.betterstreamflix.app.home

import android.content.Context
import android.view.Gravity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.betterstreamflix.app.R
import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.ui.UserDataNotifier

/** Shared pin feedback + Settings picker for Shelf Pinning. */
object HomeShelfPinUi {

    fun labelFor(context: Context, shelfId: String): String {
        val res = context.resources
        return when (shelfId) {
            Category.CONTINUE_WATCHING -> res.getString(R.string.home_continue_watching)
            Category.NEXT_EPISODES -> res.getString(R.string.home_next_episodes)
            Category.RECENTLY_WATCHED -> res.getString(R.string.home_recently_watched)
            Category.RECENTLY_ADDED -> res.getString(R.string.home_recently_added)
            Category.NEW_THIS_WEEK -> res.getString(R.string.home_new_this_week)
            Category.FAMILY_FRIDAY -> res.getString(R.string.home_family_friday)
            Category.HOME_LIVE, Category.HOME_LIVE_ID -> res.getString(R.string.home_live_shelf)
            Category.DOWNLOADS, Category.DOWNLOADS_ID -> res.getString(R.string.home_downloads_shelf)
            Category.SMART_CHILL_EVENING -> res.getString(R.string.home_smart_chill_evening)
            Category.SMART_WITH_KIDS -> res.getString(R.string.home_smart_with_kids)
            Category.SMART_DE_AUDIO -> res.getString(R.string.home_smart_de_audio)
            Category.FAVORITE_MOVIES -> res.getString(R.string.home_favorite_movies)
            Category.FAVORITE_TV_SHOWS -> res.getString(R.string.home_favorite_tv_shows)
            else -> shelfId
        }
    }

    fun pinsSummary(context: Context): String {
        val pins = HomeShelfLayout.pins()
        if (pins.isEmpty()) {
            return context.getString(R.string.settings_home_shelf_pins_summary_empty)
        }
        val labels = pins.mapIndexed { index, id ->
            "${index + 1}. ${labelFor(context, id)}"
        }
        return context.getString(
            R.string.settings_home_shelf_pins_summary,
            labels.joinToString(" · "),
        )
    }

    fun applyToggle(context: Context, shelfId: String): HomeShelfLayout.ToggleResult {
        val result = HomeShelfLayout.togglePin(shelfId)
        when (result) {
            is HomeShelfLayout.ToggleResult.Pinned -> {
                toast(context, context.getString(R.string.home_shelf_pinned, labelFor(context, shelfId)))
                UserDataNotifier.notifyChanged()
            }
            is HomeShelfLayout.ToggleResult.Unpinned -> {
                toast(context, context.getString(R.string.home_shelf_unpinned, labelFor(context, shelfId)))
                UserDataNotifier.notifyChanged()
            }
            is HomeShelfLayout.ToggleResult.MaxReached -> {
                toast(
                    context,
                    context.getString(R.string.home_shelf_pin_max, HomeShelfLayout.MAX_PINS),
                )
            }
            HomeShelfLayout.ToggleResult.NotPinnable -> Unit
        }
        return result
    }

    fun showPicker(
        context: Context,
        availableIds: List<String> = HomeShelfLayout.WELL_KNOWN_PINNABLE,
        onChanged: () -> Unit = {},
    ) {
        val options = availableIds.distinct()
        val selected = HomeShelfLayout.pins().toMutableList()
        val checked = BooleanArray(options.size) { index ->
            selected.any { it.equals(options[index], ignoreCase = true) }
        }
        val labels = options.map { labelFor(context, it) }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(R.string.settings_home_shelf_pins_title)
            .setMessage(
                context.getString(
                    R.string.settings_home_shelf_pins_message,
                    HomeShelfLayout.MAX_PINS,
                ),
            )
            .setMultiChoiceItems(labels, checked) { dialog, which, isChecked ->
                val id = options[which]
                if (isChecked) {
                    if (selected.any { it.equals(id, ignoreCase = true) }) return@setMultiChoiceItems
                    if (selected.size >= HomeShelfLayout.MAX_PINS) {
                        checked[which] = false
                        (dialog as AlertDialog).listView?.setItemChecked(which, false)
                        toast(
                            context,
                            context.getString(
                                R.string.home_shelf_pin_max,
                                HomeShelfLayout.MAX_PINS,
                            ),
                        )
                    } else {
                        selected.add(id)
                        checked[which] = true
                    }
                } else {
                    selected.removeAll { it.equals(id, ignoreCase = true) }
                    checked[which] = false
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                HomeShelfLayout.setPins(selected)
                UserDataNotifier.notifyChanged()
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(context: Context, message: CharSequence) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).apply {
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 120)
        }.show()
    }
}
