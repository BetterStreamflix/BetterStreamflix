package com.dskja.betterstreamflix.fragments.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.recyclerview.widget.RecyclerView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.FragmentSearchTvBinding
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.Http409CacheGuard
import com.dskja.betterstreamflix.utils.LoggingUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.VoiceRecognitionHelper
import com.dskja.betterstreamflix.utils.hideKeyboard
import com.dskja.betterstreamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.providers.IptvProvider

class SearchTvFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentSearchTvBinding? = null
    private val binding get() = _binding!!

    private val database get() = AppDatabase.getInstance(requireContext())
    private val viewModel by viewModelsFactory {
        SearchViewModel(runCatching { database }.getOrNull())
    }
    private var currentGridColumns: Int = 1
    private var gridConfiguredForColumns: Int = -1
    private var gridSubmitAttempts: Int = 0

    private val voicePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (::voiceHelper.isInitialized) voiceHelper.onPermissionResult(granted)
        }

    private val appAdapter by lazy {
        AppAdapter().apply {
            onMovieClickListener = { movie ->
                switchProviderIfNeeded(movie.providerName)
                findNavController().navigate(
                    SearchTvFragmentDirections.actionSearchToMovie(id = movie.id)
                )
            }
            onTvShowClickListener = { tvShow ->
                switchProviderIfNeeded(tvShow.providerName)
                findNavController().navigate(
                    SearchTvFragmentDirections.actionSearchToTvShow(
                        id = tvShow.id,
                        poster = tvShow.poster,
                        banner = tvShow.banner,
                    )
                )
            }
        }
    }

    private lateinit var voiceHelper: VoiceRecognitionHelper

    private fun switchProviderIfNeeded(providerName: String?) {
        val targetName = providerName?.takeIf { it.isNotBlank() } ?: return
        if (targetName == UserPreferences.currentProvider?.name) return

        val targetProvider = Provider.providers.keys.find { it.name == targetName } ?: return
        UserPreferences.currentProvider = targetProvider
        Toast.makeText(
            requireContext(),
            getString(R.string.switching_to_provider, targetName),
            Toast.LENGTH_SHORT,
        ).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeSearch()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state
                .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .collect { state ->
                val ui = _binding ?: return@collect
                try {
                    renderSearchState(ui, state)
                } catch (t: Throwable) {
                    Log.e(TAG, "search render failed", t)
                    runCatching { clearSearchGrid() }
                    runCatching { hideSearchLoading(ui) }
                }
            }
        }
    }

    private fun renderSearchState(ui: FragmentSearchTvBinding, state: State) {
                when (state) {
                    is State.Searching, is State.GlobalSearching -> {
                        // Drop opposite-shaped rows before local↔global column changes.
                        clearSearchGrid()
                        showSearchLoading(ui)
                        ui.root.findViewById<View>(R.id.tv_search_empty)?.visibility = View.GONE
                        ui.root.findViewById<View>(R.id.v_search_empty_rule)?.visibility = View.GONE
                        ui.root.findViewById<View>(R.id.btn_search_empty_cta)?.visibility = View.GONE
                        appAdapter.isLoading = false
                        appAdapter.setOnLoadMoreListener(null)
                    }
                    is State.SearchingMore -> appAdapter.isLoading = true
                    is State.SuccessSearching -> {
                        displaySearch(state.results, state.hasMore)
                        appAdapter.isLoading = false
                    }
                    is State.SuccessGlobalSearching -> {
                        displayGlobalSearch(state.providerResults)
                        appAdapter.isLoading = false
                    }
                    is State.FailedSearching -> {
                        disarmSearchLoading(ui)
                        ui.isLoading.root.visibility = View.VISIBLE
                        if (http409Guard.handle(requireContext(), state.error) {
                                if (appAdapter.isLoading) appAdapter.isLoading = false
                                retryLastSearch()
                            }) {
                            return
                        }
                        Toast.makeText(requireContext(), state.error.message ?: "", Toast.LENGTH_SHORT).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            ui.isLoading.apply {
                                com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, false)
                                gIsLoadingRetry.visibility = View.VISIBLE
                                com.dskja.betterstreamflix.utils.ExpPressEffects.animateLoadingError(root)
                                btnIsLoadingRetry.setOnClickListener { retryLastSearch() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    ExpDialogChrome.notify(requireContext(), R.string.clear_cache_done, R.string.loading_error_clear_cache)
                                    retryLastSearch()
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    LoggingUtils.showErrorDialog(requireContext(), state.error)
                                }
                                ui.vgvSearch.visibility = View.INVISIBLE
                                ui.etSearch.nextFocusDownId = ui.isLoading.btnIsLoadingRetry.id
                                ui.isLoading.btnIsLoadingRetry.nextFocusUpId = ui.etSearch.id
                                pointSortChips(
                                    SearchTvRows.chipDownId(
                                        hasGridItems = false,
                                        emptyCtaVisible = false,
                                        gridId = ui.vgvSearch.id,
                                        emptyCtaId = ui.isLoading.btnIsLoadingRetry.id,
                                        fallbackId = ui.isLoading.btnIsLoadingRetry.id,
                                    )
                                )
                            }
                        }
                    }
                }
    }

    override fun onResume() {
        super.onResume()
        refreshSortChips()
    }

    override fun onDestroyView() {
        if (::voiceHelper.isInitialized) {
            runCatching { voiceHelper.stopRecognition() }
        }
        super.onDestroyView()
        _binding = null
    }

    private fun retryLastSearch() {
        val query = viewModel.query
        if (binding.llGlobalSearch.isChecked) {
            val currentLanguage = UserPreferences.currentProvider?.language ?: "es"
            viewModel.searchGlobal(query, currentLanguage)
        } else {
            viewModel.search(query)
        }
    }

    private fun submitSearch(): Boolean {
        val query = binding.etSearch.text?.toString().orEmpty()
        hideKeyboard()

        if (binding.llGlobalSearch.isChecked) {
            if (query.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.search_empty_query), Toast.LENGTH_SHORT).show()
                return true
            }
            SearchRecentStore.remember(requireContext(), query)
            val currentLanguage = UserPreferences.currentProvider?.language ?: "es"
            viewModel.searchGlobal(query, currentLanguage)
        } else {
            if (query.isNotBlank()) {
                SearchRecentStore.remember(requireContext(), query)
            }
            viewModel.search(query)
        }
        refreshRecentSearches()
        return true
    }

    private fun initializeSearch() {
        val isIptv = UserPreferences.currentProvider is IptvProvider
        val hintStringRes = if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
        binding.etSearch.hint = getString(hintStringRes)

        binding.llGlobalSearch.nextFocusUpId = binding.etSearch.id
        binding.llGlobalSearch.nextFocusDownId = R.id.chip_search_sort_default
        binding.vgvSearch.nextFocusUpId = R.id.chip_search_sort_default
        updateGlobalSearchContentDescription()
        binding.llGlobalSearch.setOnCheckedChangeListener { _, _ ->
            updateGlobalSearchContentDescription()
        }

        binding.etSearch.apply {
            setOnEditorActionListener { _, actionId, event ->
                val isSubmitAction =
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                        actionId == EditorInfo.IME_ACTION_DONE ||
                        actionId == EditorInfo.IME_NULL
                val isSubmitKey =
                    event?.action == KeyEvent.ACTION_DOWN &&
                        (event.keyCode == KeyEvent.KEYCODE_ENTER ||
                            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)

                if (isSubmitAction || isSubmitKey) {
                    return@setOnEditorActionListener submitSearch()
                }
                return@setOnEditorActionListener false
            }

            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }

                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    return@setOnKeyListener focusSearchContent()
                }

                if (
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                    keyCode == KeyEvent.KEYCODE_SEARCH
                ) {
                    return@setOnKeyListener submitSearch()
                }

                false
            }

            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (s.isNullOrBlank()) {
                        val iptv = UserPreferences.currentProvider is IptvProvider
                        val hintRes =
                            if (iptv) R.string.search_input_hint_iptv else R.string.search_input_hint
                        binding.etSearch.hint = getString(hintRes)
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        val blink = AlphaAnimation(1f, 0.3f).apply {
            duration = 500
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }

        voiceHelper = VoiceRecognitionHelper(
            fragment = this,
            permissionLauncher = voicePermissionLauncher,
            onResult = { query ->
                val ui = _binding ?: return@VoiceRecognitionHelper
                ui.btnSearchVoice.clearAnimation()
                ui.etSearch.setText(query)
                // Honor global-search SwitchCompat the same way submitSearch does.
                submitSearch()
            },
            onError = { msg ->
                val ui = _binding ?: return@VoiceRecognitionHelper
                com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(
                    requireContext(),
                    msg,
                    R.string.voice_search,
                )
                ui.btnSearchVoice.clearAnimation()
                val isIptv = UserPreferences.currentProvider is IptvProvider
                val hintStringRes =
                    if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
                ui.etSearch.hint = getString(hintStringRes)
            },
            onListeningStateChanged = { isListening ->
                val ui = _binding ?: return@VoiceRecognitionHelper
                if (isListening) {
                    ui.btnSearchVoice.startAnimation(blink)
                    ui.etSearch.hint = getString(R.string.voice_prompt)
                } else {
                    ui.btnSearchVoice.clearAnimation()
                    val isIptv = UserPreferences.currentProvider is IptvProvider
                    val hintStringRes =
                        if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
                    ui.etSearch.hint = getString(hintStringRes)
                }
            }
        )

        binding.btnSearchVoice.apply {
            val voiceVisible = voiceHelper.isAvailable()
            visibility = if (voiceVisible) View.VISIBLE else View.GONE
            binding.btnSearchClear.nextFocusRightId =
                if (voiceVisible) id else View.NO_ID
            setOnClickListener {
                if (!voiceHelper.isListening) voiceHelper.startWithPermissionCheck()
            }
        }

        listOf(binding.btnSearchClear, binding.btnSearchVoice, binding.llGlobalSearch).forEach { view ->
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                    focusSearchContent()
                } else {
                    false
                }
            }
        }

        binding.btnSearchClear.setOnClickListener {
            binding.etSearch.setText("")
            val isIptv = UserPreferences.currentProvider is IptvProvider
            val hintStringRes =
                if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
            binding.etSearch.hint = getString(hintStringRes)
            viewModel.search("")
        }

        binding.vgvSearch.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(resources.getDimension(R.dimen.search_spacing).toInt())
            addOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(
                    parent: RecyclerView,
                    child: RecyclerView.ViewHolder?,
                    position: Int,
                    subposition: Int,
                ) {
                    child?.itemView?.nextFocusUpId =
                        if (position in 0 until currentGridColumns) R.id.chip_search_sort_default
                        else View.NO_ID
                }
            })
        }

        refreshRecentSearches()
        initializeSearchSortControls()
        // Loader covers the grid and is focusable in XML. On Fire OS that full-screen
        // target crashes Leanback focus search as soon as Search opens.
        disarmSearchLoading(binding)
        binding.etSearch.post {
            val ui = _binding ?: return@post
            if (ui.etSearch.isShown) ui.etSearch.requestFocus()
        }
    }

    private fun initializeSearchSortControls() {
        val chipDefault = binding.root.findViewById<android.widget.TextView>(R.id.chip_search_sort_default) ?: return
        val chipNewest = binding.root.findViewById<android.widget.TextView>(R.id.chip_search_sort_newest) ?: return
        val chipYear = binding.root.findViewById<android.widget.TextView>(R.id.chip_search_filter_year) ?: return

        refreshSortChips()

        chipDefault.setOnClickListener {
            viewModel.setSortMode(com.dskja.betterstreamflix.utils.SearchSortMode.PROVIDER_DEFAULT)
            refreshSortChips()
        }
        chipNewest.setOnClickListener {
            viewModel.setSortMode(com.dskja.betterstreamflix.utils.SearchSortMode.NEWEST_FIRST)
            refreshSortChips()
        }
        chipYear.setOnClickListener {
            SearchSortUi.showYearPicker(
                context = requireContext(),
                currentYear = viewModel.currentYearFilter(),
                resultYears = viewModel.yearsInCurrentResults(),
            ) { year ->
                viewModel.setYearFilter(year)
                refreshSortChips()
            }
        }
    }

    private fun refreshRecentSearches() {
        val strip = binding.root.findViewById<View>(R.id.hsv_search_recent) ?: return
        val row = binding.root.findViewById<android.widget.LinearLayout>(R.id.ll_search_recent) ?: return
        val recent = SearchRecentStore.list(requireContext())
        if (recent.isEmpty()) {
            strip.visibility = View.GONE
            row.removeAllViews()
            binding.root.findViewById<View>(R.id.v_search_recent_edge_fade_start)?.visibility = View.GONE
            binding.root.findViewById<View>(R.id.v_search_recent_edge_fade_end)?.visibility = View.GONE
            binding.etSearch.nextFocusDownId = binding.llGlobalSearch.id
            binding.llGlobalSearch.nextFocusUpId = binding.etSearch.id
            return
        }
        strip.visibility = View.VISIBLE
        binding.root.findViewById<View>(R.id.v_search_recent_edge_fade_start)?.visibility = View.GONE
        binding.root.findViewById<View>(R.id.v_search_recent_edge_fade_end)?.visibility = View.GONE
        row.removeAllViews()
        val padH = (16 * resources.displayMetrics.density).toInt()
        val padV = (10 * resources.displayMetrics.density).toInt()
        val gap = (10 * resources.displayMetrics.density).toInt()

        val label = android.widget.TextView(requireContext()).apply {
            text = getString(R.string.search_recent_title)
            setTextColor(0x99FFFFFF.toInt())
            textSize = 14f
            setPadding(0, padV, gap, padV)
        }
        row.addView(label)

        var firstChip: View? = null
        recent.forEach { query ->
            val chip = android.widget.TextView(requireContext()).apply {
                id = View.generateViewId()
                text = query
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.bg_btn_exoplayer_tv)
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener {
                    binding.etSearch.setText(query)
                    SearchRecentStore.remember(requireContext(), query)
                    if (binding.llGlobalSearch.isChecked) {
                        val lang = UserPreferences.currentProvider?.language ?: "es"
                        viewModel.searchGlobal(query, lang)
                    } else {
                        viewModel.search(query)
                    }
                    refreshRecentSearches()
                }
            }
            if (firstChip == null) firstChip = chip
            val lp = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = gap }
            row.addView(chip, lp)
        }

        val clear = android.widget.TextView(requireContext()).apply {
            text = getString(R.string.search_recent_clear)
            textSize = 14f
            setTextColor(0xB3FFFFFF.toInt())
            setPadding(padH, padV, padH, padV)
            contentDescription = getString(R.string.search_recent_clear_cd)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                SearchRecentStore.clear(requireContext())
                refreshRecentSearches()
            }
        }
        row.addView(clear)

        firstChip?.let { chip ->
            binding.etSearch.nextFocusDownId = chip.id
            chip.nextFocusUpId = binding.etSearch.id
            chip.nextFocusDownId = binding.llGlobalSearch.id
            binding.llGlobalSearch.nextFocusUpId = chip.id
        }
    }

    private fun focusSearchContent(): Boolean {
        val ui = _binding ?: return false
        val gridVisible = ui.vgvSearch.visibility == View.VISIBLE
        // Prefer a real poster/shelf child — the VerticalGridView itself is focusable and
        // would swallow Back into an empty/Loading grid (Leanback focus trap).
        val focusableChild = if (gridVisible && appAdapter.itemCount > 0) {
            ui.vgvSearch.findFocus()?.takeIf { it !== ui.vgvSearch && it.hasFocusable() }
                ?: ui.vgvSearch.getChildAt(0)?.takeIf { it.hasFocusable() }
        } else {
            null
        }
        return when {
            focusableChild != null -> focusableChild.requestFocus()
            ui.llGlobalSearch.visibility == View.VISIBLE -> {
                ui.llGlobalSearch.requestFocus()
            }
            else -> false
        }
    }

    private fun disarmSearchLoading(ui: FragmentSearchTvBinding) {
        ui.isLoading.root.apply {
            if (hasFocus()) ui.etSearch.requestFocus()
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
        }
    }

    private fun showSearchLoading(ui: FragmentSearchTvBinding) {
        disarmSearchLoading(ui)
        ui.isLoading.root.visibility = View.VISIBLE
        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(ui.isLoading.root, true)
        ui.isLoading.gIsLoadingRetry.visibility = View.GONE
    }

    private fun hideSearchLoading(ui: FragmentSearchTvBinding) {
        disarmSearchLoading(ui)
        ui.isLoading.root.visibility = View.GONE
        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(ui.isLoading.root, false)
    }

    private fun clearSearchGrid() {
        val ui = _binding ?: return
        if (ui.vgvSearch.isComputingLayout) {
            ui.vgvSearch.post { clearSearchGrid() }
            return
        }
        appAdapter.submitList(emptyList())
        appAdapter.setOnLoadMoreListener(null)
        gridConfiguredForColumns = -1
        ui.vgvSearch.visibility = View.INVISIBLE
        pointSortChips(ui.etSearch.id)
    }

    private fun submitSearchGrid(columns: Int, items: List<AppAdapter.Item>) {
        val ui = _binding ?: return
        val grid = ui.vgvSearch
        val needsMeasure = grid.width <= 0 && gridConfiguredForColumns != columns
        if ((grid.isComputingLayout || needsMeasure) && gridSubmitAttempts < 8) {
            gridSubmitAttempts++
            grid.post { submitSearchGrid(columns, items) }
            return
        }
        gridSubmitAttempts = 0
        // Hide the loader before the grid lays out. A focusable overlay sibling
        // makes Leanback throw while setNumColumns / submitList run.
        hideSearchLoading(ui)
        if (gridConfiguredForColumns != columns) {
            // Never call setNumColumns while the opposite item shape is still bound.
            if (appAdapter.itemCount > 0) {
                appAdapter.submitList(emptyList())
            }
            currentGridColumns = columns
            val applied = runCatching { grid.setNumColumns(columns) }.isSuccess
            if (applied) gridConfiguredForColumns = columns
        }
        appAdapter.submitList(items)
        grid.visibility = View.VISIBLE
    }

    private fun refreshSortChips() {
        val root = _binding?.root ?: return
        val chipDefault = root.findViewById<android.widget.TextView>(R.id.chip_search_sort_default) ?: return
        val chipNewest = root.findViewById<android.widget.TextView>(R.id.chip_search_sort_newest) ?: return
        val chipYear = root.findViewById<android.widget.TextView>(R.id.chip_search_filter_year) ?: return
        SearchSortUi.bindChips(
            chipDefault = chipDefault,
            chipNewest = chipNewest,
            chipYear = chipYear,
            mode = viewModel.currentSortMode(),
            year = viewModel.currentYearFilter(),
        )
    }

    private fun pointSortChips(downId: Int) {
        val ui = _binding ?: return
        listOf(
            R.id.chip_search_sort_default,
            R.id.chip_search_sort_newest,
            R.id.chip_search_filter_year,
        ).forEach { id ->
            ui.root.findViewById<View>(id)?.nextFocusDownId = downId
        }
    }

    private fun bindSearchEmpty(ui: FragmentSearchTvBinding, visible: Boolean) {
        val emptyView = ui.root.findViewById<View>(R.id.tv_search_empty)
        val cta = ui.root.findViewById<android.widget.TextView>(R.id.btn_search_empty_cta)
        ExpEmptyChrome.bind(
            emptyView = emptyView,
            emptyRule = ui.root.findViewById(R.id.v_search_empty_rule),
            emptyCta = cta,
            visible = visible,
        )
        if (!visible || cta == null) return
        // Shared empty chrome hides the action unless the experimental mobile theme is on.
        cta.visibility = View.VISIBLE
        cta.nextFocusUpId = R.id.chip_search_filter_year
        cta.setOnClickListener {
            val binding = _binding ?: return@setOnClickListener
            binding.etSearch.setText("")
            viewModel.search("")
            binding.etSearch.requestFocus()
        }
    }

    private fun displaySearch(list: List<AppAdapter.Item>, hasMore: Boolean) {
        val ui = _binding ?: return
        val plan = SearchTvRows.local(viewModel.query, list)
        submitSearchGrid(plan.columns, plan.items)
        val showEmpty = plan.items.isEmpty() && viewModel.query.isNotBlank()
        bindSearchEmpty(ui, showEmpty)
        pointSortChips(
            SearchTvRows.chipDownId(
                hasGridItems = plan.items.isNotEmpty(),
                emptyCtaVisible = showEmpty,
                gridId = ui.vgvSearch.id,
                emptyCtaId = R.id.btn_search_empty_cta,
                fallbackId = ui.etSearch.id,
            )
        )

        if (hasMore && viewModel.query.isNotBlank() && plan.items.size < SearchTvRows.MAX_LOCAL) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMore() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    private fun displayGlobalSearch(providerResults: List<ProviderResult>) {
        val ui = _binding ?: return
        val plan = SearchTvRows.global(providerResults) { name, count ->
            val resultText = if (count == 1) getString(R.string.result) else getString(R.string.results)
            "$name - $count $resultText"
        }
        val stillWorking = providerResults.any { it.state is ProviderResult.State.Loading }
        // Loading / error / zero-hit providers are omitted. Empty CATEGORY_TV_ITEM
        // rows hide their HorizontalGridView and kill the process on DPAD.
        if (plan.items.isEmpty()) {
            clearSearchGrid()
            if (stillWorking) showSearchLoading(ui) else hideSearchLoading(ui)
        } else {
            submitSearchGrid(plan.columns, plan.items)
        }
        appAdapter.setOnLoadMoreListener(null)

        val showEmpty = !stillWorking && plan.items.isEmpty() && viewModel.query.isNotBlank()
        bindSearchEmpty(ui, showEmpty)
        pointSortChips(
            SearchTvRows.chipDownId(
                hasGridItems = plan.items.isNotEmpty(),
                emptyCtaVisible = showEmpty,
                gridId = ui.vgvSearch.id,
                emptyCtaId = R.id.btn_search_empty_cta,
                fallbackId = ui.etSearch.id,
            )
        )
    }

    private fun updateGlobalSearchContentDescription() {
        val switch = _binding?.llGlobalSearch ?: return
        switch.contentDescription = getString(
            if (switch.isChecked) R.string.global_search_cd_on else R.string.global_search_cd_off,
        )
    }

    private companion object {
        const val TAG = "SearchTvFragment"
    }
}
