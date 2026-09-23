package com.dskja.betterstreamflix.fragments.season

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object SeasonSwitcher {
    fun bind(
        fragment: Fragment,
        spinner: Spinner,
        database: AppDatabase,
        tvShowId: String,
        tvShowTitle: String,
        tvShowPoster: String?,
        tvShowBanner: String?,
        currentSeasonId: String,
        currentSeasonNumber: Int,
        currentSeasonTitle: String?,
        /** Preferred leanback control: opens a single-choice dialog. */
        dialogButton: TextView? = null,
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val seasons = withContext(Dispatchers.IO) {
                var list = database.seasonDao().getByTvShowId(tvShowId).sortedBy { it.number }
                if (list.isEmpty()) {
                    runCatching {
                        UserPreferences.currentProvider?.getTvShow(tvShowId)?.seasons.orEmpty()
                    }.getOrDefault(emptyList()).also { fetched ->
                        if (fetched.isNotEmpty()) {
                            fetched.forEach { season ->
                                season.tvShow = season.tvShow ?: com.dskja.betterstreamflix.models.TvShow(
                                    id = tvShowId,
                                    title = tvShowTitle,
                                )
                            }
                            database.seasonDao().insertAll(fetched)
                            list = fetched.sortedBy { it.number }
                        }
                    }
                }
                // Prefer remapping to an existing season by number when the nav id
                // is missing from Room — never append a duplicate synthetic Season N.
                var resolvedId = currentSeasonId
                if (list.none { it.id == currentSeasonId }) {
                    val byNumber = list.firstOrNull { it.number == currentSeasonNumber }
                    if (byNumber != null) {
                        resolvedId = byNumber.id
                    } else {
                        list = (list + Season(
                            id = currentSeasonId,
                            number = currentSeasonNumber,
                            title = currentSeasonTitle,
                        )).sortedBy { it.number }
                    }
                }
                ResolvedSeasons(list, resolvedId)
            }

            if (seasons.list.size <= 1) {
                spinner.visibility = View.GONE
                dialogButton?.visibility = View.GONE
                return@launch
            }

            val seasonList = seasons.list
            val effectiveSeasonId = seasons.resolvedId
            val labels = seasonList.map { season ->
                season.title?.takeIf { it.isNotBlank() }
                    ?: fragment.getString(R.string.season_number, season.number)
            }
            val selectedIndex = seasonList.indexOfFirst { it.id == effectiveSeasonId }
                .takeIf { it >= 0 }
                ?: seasonList.indexOfFirst { it.number == currentSeasonNumber }
                    .coerceAtLeast(0)

            fun navigateTo(season: Season) {
                if (season.id == effectiveSeasonId) return
                val title = season.title?.takeIf { it.isNotBlank() }
                    ?: fragment.getString(R.string.season_number, season.number)
                fragment.findNavController().navigate(
                    R.id.season,
                    bundleOf(
                        "tvShowId" to tvShowId,
                        "tvShowTitle" to tvShowTitle,
                        "tvShowPoster" to tvShowPoster,
                        "tvShowBanner" to tvShowBanner,
                        "seasonId" to season.id,
                        "seasonNumber" to season.number,
                        "seasonTitle" to title,
                    ),
                    NavOptions.Builder()
                        .setPopUpTo(R.id.season, true)
                        .build(),
                )
            }

            fun showSeasonDialog() {
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(R.string.tv_show_seasons)
                    .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
                        seasonList.getOrNull(which)?.let(::navigateTo)
                        dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }

            val leanback = DeviceCapabilities.isLeanbackDevice(fragment.requireContext()) ||
                DeviceCapabilities.isAmazonFireTv(fragment.requireContext())

            if (leanback && dialogButton != null) {
                spinner.visibility = View.GONE
                dialogButton.visibility = View.VISIBLE
                dialogButton.text = labels.getOrNull(selectedIndex)
                    ?: fragment.getString(R.string.season_switch_content_description)
                dialogButton.contentDescription =
                    fragment.getString(R.string.season_switch_content_description)
                dialogButton.setOnClickListener { view ->
                    com.dskja.betterstreamflix.utils.ExpMotion.hapticTap(view)
                    showSeasonDialog()
                }
                if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled()) {
                    with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                        dialogButton.applyExpPress()
                    }
                }
                return@launch
            }

            val wasGone = spinner.visibility != View.VISIBLE
            spinner.visibility = View.VISIBLE
            dialogButton?.visibility = View.GONE
            if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled()) {
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { spinner.applyExpPress() }
                if (wasGone) {
                    com.dskja.betterstreamflix.utils.ExpMotion.popIn(spinner)
                }
                runCatching {
                    spinner.setPopupBackgroundResource(
                        com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.glassCardBackground(),
                    )
                }
            }
            spinner.adapter = ArrayAdapter(
                fragment.requireContext(),
                android.R.layout.simple_spinner_item,
                labels,
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinner.setSelection(selectedIndex, false)

            if (leanback) {
                // Spinner dropdowns are awkward on DPAD; prefer a single-choice AlertDialog.
                spinner.onItemSelectedListener = null
                spinner.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        showSeasonDialog()
                    }
                    true
                }
                spinner.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_UP &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == KeyEvent.KEYCODE_ENTER ||
                            keyCode == KeyEvent.KEYCODE_SPACE)
                    ) {
                        showSeasonDialog()
                        true
                    } else {
                        false
                    }
                }
            } else {
                spinner.setOnTouchListener(null)
                spinner.setOnKeyListener(null)
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        com.dskja.betterstreamflix.utils.ExpMotion.hapticTap(spinner)
                        seasonList.getOrNull(position)?.let(::navigateTo)
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }
        }
    }

    private data class ResolvedSeasons(
        val list: List<Season>,
        val resolvedId: String,
    )
}
