package com.dskja.betterstreamflix.fragments.favorites

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.navigation.fragment.findNavController
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.FragmentFavoritesMobileBinding
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.ui.UserDataNotifier
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.UserDataCache
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FavoritesMobileFragment : Fragment() {

    private var _binding: FragmentFavoritesMobileBinding? = null
    private val binding get() = _binding!!
    private val appAdapter = AppAdapter()
    private var rearrangeMode = false
    private val selectedItems = mutableSetOf<String>()
    private val providerName get() = UserPreferences.currentProvider?.name.orEmpty()
    private val rearrangeBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setRearrangeMode(false)
        }
    }
    private val viewModel: FavoritesViewModel
        get() {
            val key = providerName.ifBlank { "default" }
            val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return FavoritesViewModel(providerName = key) as T
                }
            }
            return androidx.lifecycle.ViewModelProvider(this, factory)[key, FavoritesViewModel::class.java]
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFavoritesMobileBinding.bind(
            inflater.inflate(
                R.layout.fragment_favorites_mobile,
                container,
                false,
            )
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ExpMotion.enterScreen(binding.root)
        ExperimentalMobileDesign.applyReducedGlass(binding.root)
        ExpMotion.staggerFirstFill(binding.rvFavorites)
        val columnCount = maxOf(3, resources.configuration.screenWidthDp / 120)
        val gridLayoutManager = GridLayoutManager(requireContext(), columnCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (appAdapter.items.getOrNull(position) is FavoriteSectionHeader) columnCount else 1
            }
        }
        binding.rvFavorites.apply {
            layoutManager = gridLayoutManager
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(SpacingItemDecoration(8.dp(requireContext())))
        }
        createDragHelper().attachToRecyclerView(binding.rvFavorites)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, rearrangeBackCallback)
        binding.btnFavoritesEdit.setOnClickListener {
            ExpMotion.hapticTap(it)
            setRearrangeMode(true)
        }
        binding.btnFavoritesCancel.setOnClickListener {
            ExpMotion.hapticTap(it)
            setRearrangeMode(false)
        }
        binding.btnFavoritesRemove.setOnClickListener {
            ExpMotion.hapticTap(it)
            removeSelectedFavorites()
        }
        binding.btnFavoritesSort.setOnClickListener {
            ExpMotion.hapticTap(it)
            showSortDialog()
        }
        setRearrangeMode(false)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sections.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect(::display)
        }
    }

    private fun createDragHelper() = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        0,
    ) {
        private var draggedSection: FavoritesViewModel.Section? = null

        override fun isLongPressDragEnabled(): Boolean = rearrangeMode

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val position = viewHolder.bindingAdapterPosition
            return if (!rearrangeMode || appAdapter.items.getOrNull(position) is FavoriteSectionHeader) {
                makeMovementFlags(0, 0)
            } else {
                super.getMovementFlags(recyclerView, viewHolder)
            }
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            val fromSection = sectionAt(from) ?: return false
            if (sectionAt(to) != fromSection || appAdapter.items.getOrNull(to) is FavoriteSectionHeader) {
                return false
            }
            draggedSection = fromSection
            return reorderForDrag(fromSection, from, to)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(160L).start()
            draggedSection?.let(::persistSectionOrder)
            draggedSection = null
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (viewHolder == null) return
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                ExpMotion.hapticTap(viewHolder.itemView)
                viewHolder.itemView.animate()
                    .scaleX(1.04f)
                    .scaleY(1.04f)
                    .translationZ(8f * viewHolder.itemView.resources.displayMetrics.density)
                    .setDuration(140L)
                    .start()
            }
        }
    })

    private fun setRearrangeMode(enabled: Boolean) {
        rearrangeMode = enabled
        rearrangeBackCallback.isEnabled = enabled
        if (!enabled) selectedItems.clear()
        binding.btnFavoritesEdit.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.btnFavoritesSort.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.btnFavoritesCancel.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.btnFavoritesRemove.visibility = if (enabled) View.VISIBLE else View.GONE
        updateToolbarCopy()
        updateRemoveEnabled()
        configureAdapterInteractions()
        val count = appAdapter.itemCount
        if (count > 0) {
            appAdapter.notifyItemRangeChanged(0, count, AppAdapter.PAYLOAD_SELECTION)
        }
    }

    private fun updateToolbarCopy() {
        binding.tvFavoritesTitle.text = when {
            rearrangeMode && selectedItems.isNotEmpty() ->
                getString(R.string.exp_favorites_selected_count, selectedItems.size)
            rearrangeMode -> getString(R.string.favorites_edit)
            else -> getString(R.string.main_menu_favorites)
        }
    }

    private fun updateRemoveEnabled() {
        val hasSelection = selectedItems.isNotEmpty()
        binding.btnFavoritesRemove.isEnabled = rearrangeMode && hasSelection
        binding.btnFavoritesRemove.alpha = if (hasSelection) 1f else 0.4f
        binding.btnFavoritesRemove.setTextColor(
            if (hasSelection) 0xFFFFFFFF.toInt() else 0x4DFFFFFF.toInt(),
        )
        updateToolbarCopy()
    }

    private fun removeSelectedFavorites() {
        if (selectedItems.isEmpty()) return
        val toRemove = appAdapter.items.filter { itemKey(it) in selectedItems }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val appContext = requireContext().applicationContext
            val currentName = UserPreferences.currentProvider?.name
            toRemove.forEach { item ->
                when (item) {
                    is Movie -> removeMovieFavorite(appContext, item, currentName)
                    is TvShow -> removeTvShowFavorite(appContext, item, currentName)
                }
            }
            UserDataNotifier.notifyChanged()
            withContext(Dispatchers.Main) {
                selectedItems.clear()
                setRearrangeMode(false)
            }
        }
    }

    private fun removeMovieFavorite(appContext: android.content.Context, movie: Movie, currentName: String?) {
        val provider = resolveProvider(movie.providerName, currentName) ?: return
        val ownsDb = provider.name == currentName
        val db = if (ownsDb) {
            AppDatabase.getInstance(appContext)
        } else {
            AppDatabase.getInstanceForProvider(provider.name, appContext)
        }
        try {
            db.movieDao().upsertFavorite(movie, false)
            UserDataCache.removeMovieFromFavorites(appContext, provider, movie.id)
            com.dskja.betterstreamflix.platform.simkl.SimklSyncHooks.onListToggle(
                add = false,
                imdbId = movie.imdbId,
                tmdbId = movie.tmdbId,
                isTv = false,
            )
        } finally {
            if (!ownsDb) runCatching { db.close() }
        }
    }

    private fun removeTvShowFavorite(appContext: android.content.Context, tvShow: TvShow, currentName: String?) {
        val provider = resolveProvider(tvShow.providerName, currentName) ?: return
        val ownsDb = provider.name == currentName
        val db = if (ownsDb) {
            AppDatabase.getInstance(appContext)
        } else {
            AppDatabase.getInstanceForProvider(provider.name, appContext)
        }
        try {
            db.tvShowDao().upsertFavorite(tvShow, false)
            UserDataCache.removeTvShowFromFavorites(appContext, provider, tvShow.id)
            com.dskja.betterstreamflix.platform.simkl.SimklSyncHooks.onListToggle(
                add = false,
                imdbId = tvShow.imdbId,
                tmdbId = tvShow.tmdbId,
                isTv = true,
            )
        } finally {
            if (!ownsDb) runCatching { db.close() }
        }
    }

    private fun resolveProvider(itemProviderName: String?, currentName: String?): Provider? {
        val name = itemProviderName?.takeIf { it.isNotBlank() } ?: currentName
        return name?.let(Provider::findByName) ?: UserPreferences.currentProvider
    }

    private fun configureAdapterInteractions() {
        appAdapter.isItemSelectedListener = { itemKey(it) in selectedItems }
        if (rearrangeMode) {
            appAdapter.onMovieClickListener = {
                toggleSelection(FavoritesViewModel.Section.MOVIES, libraryItemId(it.providerName, it.id))
            }
            appAdapter.onTvShowClickListener = {
                toggleSelection(FavoritesViewModel.Section.TV_SHOWS, libraryItemId(it.providerName, it.id))
            }
            appAdapter.onMovieLongClickListener = { }
            appAdapter.onTvShowLongClickListener = { }
        } else {
            appAdapter.onMovieClickListener = null
            appAdapter.onTvShowClickListener = null
            appAdapter.onMovieLongClickListener = null
            appAdapter.onTvShowLongClickListener = null
        }
    }

    private fun toggleSelection(section: FavoritesViewModel.Section, id: String) {
        val key = selectionKey(section, id)
        if (!selectedItems.add(key)) selectedItems.remove(key)
        ExpMotion.hapticTap(binding.root)
        updateRemoveEnabled()
        appAdapter.items.indexOfFirst { itemKey(it) == key }
            .takeIf { it >= 0 }
            ?.let(appAdapter::notifyItemSelectionChanged)
    }

    private fun reorderForDrag(
        section: FavoritesViewModel.Section,
        fromPosition: Int,
        toPosition: Int,
    ): Boolean {
        val source = appAdapter.items.getOrNull(fromPosition) ?: return false
        val sourceKey = itemKey(source) ?: return false
        val movingKeys = selectedItems
            .filterTo(mutableSetOf()) { it.startsWith("${section.key}:") }
            .takeIf { sourceKey in it && it.isNotEmpty() }
            ?: mutableSetOf(sourceKey)
        val sectionItems = itemsInSection(section)
        val moving = sectionItems.filter { itemKey(it) in movingKeys }
        val target = appAdapter.items.getOrNull(toPosition) ?: return false
        if (moving.isEmpty()) return false
        if (target in moving) return true
        val remaining = sectionItems.filterNot { it in moving }.toMutableList()
        val targetIndex = remaining.indexOf(target).takeIf { it >= 0 } ?: return false
        val insertAt = (targetIndex + if (toPosition > fromPosition) 1 else 0).coerceIn(0, remaining.size)
        remaining.addAll(insertAt, moving)
        replaceSectionItems(section, remaining)
        return true
    }

    private fun sectionAt(position: Int): FavoritesViewModel.Section? {
        if (position !in appAdapter.items.indices) return null
        return (position downTo 0)
            .asSequence()
            .mapNotNull { appAdapter.items[it] as? FavoriteSectionHeader }
            .firstOrNull()
            ?.section
    }

    private fun persistSectionOrder(section: FavoritesViewModel.Section) {
        val ids = itemsInSection(section)
            .mapNotNull {
                when (it) {
                    is Movie -> libraryItemId(it.providerName, it.id)
                    is TvShow -> libraryItemId(it.providerName, it.id)
                    else -> null
                }
            }
        viewModel.setManualItemOrder(section, ids)
    }

    private fun itemsInSection(section: FavoritesViewModel.Section): List<AppAdapter.Item> = appAdapter.items
        .dropWhile { it !is FavoriteSectionHeader || it.section != section }
        .drop(1)
        .takeWhile { it !is FavoriteSectionHeader }

    private fun replaceSectionItems(section: FavoritesViewModel.Section, newSectionItems: List<AppAdapter.Item>) {
        val headerIndex = appAdapter.items.indexOfFirst {
            it is FavoriteSectionHeader && it.section == section
        }
        if (headerIndex < 0) return
        val nextHeaderOffset = appAdapter.items
            .drop(headerIndex + 1)
            .indexOfFirst { it is FavoriteSectionHeader }
        val endIndex = if (nextHeaderOffset >= 0) {
            headerIndex + 1 + nextHeaderOffset
        } else {
            appAdapter.items.size
        }
        val reordered = appAdapter.items.toMutableList().apply {
            subList(headerIndex + 1, endIndex).clear()
            addAll(headerIndex + 1, newSectionItems)
        }
        appAdapter.replaceItemOrder(reordered)
    }

    private fun itemKey(item: AppAdapter.Item): String? = when (item) {
        is Movie -> selectionKey(FavoritesViewModel.Section.MOVIES, libraryItemId(item.providerName, item.id))
        is TvShow -> selectionKey(FavoritesViewModel.Section.TV_SHOWS, libraryItemId(item.providerName, item.id))
        else -> null
    }

    private fun libraryItemId(provider: String?, id: String): String =
        if (UserPreferences.isCrossProviderLibrary) {
            "${provider.orEmpty()}:$id"
        } else {
            id
        }

    private fun selectionKey(section: FavoritesViewModel.Section, id: String) = "${section.key}:$id"

    private fun display(sections: List<FavoritesViewModel.FavoriteSection>) {
        val gridItems = sections.flatMap { favoriteSection ->
            if (favoriteSection.items.isEmpty()) return@flatMap emptyList()
            val title = when (favoriteSection.section) {
                FavoritesViewModel.Section.MOVIES -> getString(R.string.home_favorite_movies)
                FavoritesViewModel.Section.TV_SHOWS -> getString(R.string.home_favorite_tv_shows)
            }
            listOf<AppAdapter.Item>(FavoriteSectionHeader(title, favoriteSection.section)) +
                favoriteSection.items.onEach { item ->
                    item.itemType = when (item) {
                        is Movie -> AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                        is TvShow -> AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
                        else -> item.itemType
                    }
                }
        }
        val empty = gridItems.isEmpty()
        ExpEmptyChrome.bind(
            emptyView = binding.tvFavoritesEmpty,
            emptyRule = binding.vFavoritesEmptyRule,
            emptyCta = binding.btnFavoritesEmptyCta,
            visible = empty,
            tintOnSurfaceVariant = false,
            onCtaClick = { findNavController().navigate(R.id.search) },
        )
        binding.rvFavorites.isVisible = !empty
        binding.btnFavoritesEdit.isVisible = !empty && !rearrangeMode
        binding.btnFavoritesSort.isVisible = !empty && !rearrangeMode
        if (empty && rearrangeMode) setRearrangeMode(false)
        appAdapter.submitList(gridItems)
    }

    private fun showSortDialog() {
        val modes = FavoritesViewModel.SortMode.entries
        val labels = arrayOf(
            getString(R.string.favorites_sort_manual),
            getString(R.string.favorites_sort_recent),
            getString(R.string.favorites_sort_title_ascending),
            getString(R.string.favorites_sort_title_descending),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.favorites_sort_title)
            .setSingleChoiceItems(labels, modes.indexOf(viewModel.currentSortMode())) { dialog, which ->
                if (modes[which] != FavoritesViewModel.SortMode.MANUAL) setRearrangeMode(false)
                viewModel.setSortMode(modes[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.option_cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener { ExpDialogChrome.polishShown(dialog) }
                dialog.show()
            }
    }

    override fun onDestroyView() {
        rearrangeBackCallback.isEnabled = false
        setRearrangeMode(false)
        appAdapter.onSaveInstanceState(binding.rvFavorites)
        _binding = null
        super.onDestroyView()
    }
}
