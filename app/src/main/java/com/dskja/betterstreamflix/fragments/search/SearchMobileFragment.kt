package com.dskja.betterstreamflix.fragments.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.FragmentSearchMobileBinding
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.Http409CacheGuard
import com.dskja.betterstreamflix.utils.ExpNavAutoHide
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.LoggingUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.VoiceRecognitionHelper
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.hideKeyboard
import com.dskja.betterstreamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch
import com.dskja.betterstreamflix.providers.IptvProvider

class SearchMobileFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentSearchMobileBinding? = null
    private val binding get() = _binding!!

    private val database get() = AppDatabase.getInstance(requireContext())
    private val viewModel by viewModelsFactory {
        SearchViewModel(runCatching { database }.getOrNull())
    }

    private var appAdapter = AppAdapter()

    private lateinit var voiceHelper: VoiceRecognitionHelper

    private val voicePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (::voiceHelper.isInitialized) voiceHelper.onPermissionResult(granted)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchMobileBinding.bind(
            inflater.inflate(
                R.layout.fragment_search_mobile,
                container,
                false,
            )
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ExpNavAutoHide.attach(binding.root)
        com.dskja.betterstreamflix.utils.ExpPressEffects.wireLoadingRetry(binding.isLoading.root)
        ExpMotion.enterScreen(binding.root)
        ExperimentalMobileDesign.applyReducedGlass(binding.root)
        ExpMotion.staggerFirstFill(binding.rvSearch)
        if (ExperimentalMobileDesign.enabled()) {
            ExpMotion.revealHeader(
                binding.root.findViewById(R.id.tv_search_eyebrow),
                binding.root.findViewById(R.id.tv_search_brand),
                binding.root.findViewById(R.id.tv_search_tagline),
                binding.root.findViewById(R.id.v_search_rule),
            )
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_search_rule))
            binding.root.findViewById<View>(R.id.cl_search)?.let { ExpMotion.popIn(it) }
            binding.root.findViewById<View>(R.id.sw_global_search)?.let { toggle ->
                toggle.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
                ExpMotion.popIn(toggle)
            }
        }

        initializeSearch()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED).collect { state ->
                val ui = _binding ?: return@collect
                try {
                when (state) {
                    is State.Searching, is State.GlobalSearching -> {
                        binding.isLoading.apply {
                            ExpMotion.fadeInAndShow(root)
                            com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                            gIsLoadingRetry.visibility = View.GONE
                        }
                        binding.root.findViewById<View>(R.id.tv_search_empty)?.isVisible = false
                        binding.root.findViewById<View>(R.id.v_search_empty_rule)?.isVisible = false
                        binding.root.findViewById<View>(R.id.btn_search_empty_cta)?.isVisible = false
                        appAdapter.isLoading = false
                        appAdapter.setOnLoadMoreListener(null)
                    }
                    is State.SearchingMore -> appAdapter.isLoading = true
                    is State.SuccessSearching -> {
                        displaySearch(state.results, state.hasMore)
                        appAdapter.isLoading = false
                        ExpMotion.fadeOutAndHide(ui.isLoading.root)
                    }
                    is State.SuccessGlobalSearching -> {
                        val keepLoader = displayGlobalSearch(state.providerResults)
                        if (keepLoader) {
                            ui.isLoading.root.isVisible = true
                        } else {
                            ExpMotion.fadeOutAndHide(ui.isLoading.root)
                        }
                    }
                    is State.FailedSearching -> {
                        if (http409Guard.handle(requireContext(), state.error) { retryLastSearch() }) {
                                return@collect
                            }
                        if (!ExperimentalMobileDesign.enabled()) {
                            Toast.makeText(
                                requireContext(),
                                state.error.message ?: "",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            ui.isLoading.apply {
                                com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, false)
                                gIsLoadingRetry.visibility = View.VISIBLE
                                com.dskja.betterstreamflix.utils.ExpPressEffects.animateLoadingError(root)
                                val doRetry = { retryLastSearch() }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(requireContext(), getString(com.dskja.betterstreamflix.R.string.clear_cache_done), com.dskja.betterstreamflix.R.string.loading_error_clear_cache)
                                    doRetry()
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    LoggingUtils.showErrorDialog(requireContext(), state.error)
                                }
                            }
                        }
                    }
                }
                } catch (t: Throwable) {
                    android.util.Log.e("SearchMobileFragment", "search render failed", t)
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
        if (binding.swGlobalSearch.isChecked) {
            val currentLanguage = UserPreferences.currentProvider?.language ?: "es"
            viewModel.searchGlobal(query, currentLanguage)
        } else {
            viewModel.search(query)
        }
    }

    private fun initializeSearch() {
        val isIptv = UserPreferences.currentProvider is IptvProvider
        val hintStringRes = if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
        binding.etSearch.hint = getString(hintStringRes)

        binding.etSearch.apply {
            // ========= LÓGICA DE BÚSQUEDA MODIFICADA =========
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    val query = binding.etSearch.text.toString()
                    hideKeyboard()

                    if (query.isBlank()) {
                        if (ExperimentalMobileDesign.enabled()) {
                            com.dskja.betterstreamflix.utils.ExpDialogChrome.showInfo(
                                requireContext(),
                                R.string.search_input_hint,
                                getString(R.string.search_empty_query),
                            ) { ctx ->
                                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                            }
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.search_empty_query), Toast.LENGTH_SHORT).show()
                        }
                        return@setOnEditorActionListener true
                    }

                    if (binding.swGlobalSearch.isChecked) {
                        val currentLanguage = UserPreferences.currentProvider?.language ?: "es"
                        SearchRecentStore.remember(requireContext(), query)
                        viewModel.searchGlobal(query, currentLanguage)
                    } else {
                        SearchRecentStore.remember(requireContext(), query)
                        viewModel.search(query)
                    }
                    refreshRecentSearches()
                    return@setOnEditorActionListener true
                }
                return@setOnEditorActionListener false
            }
            // =================================================

            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if(s.isNullOrBlank()){
                                val isIptv = UserPreferences.currentProvider is IptvProvider
        val hintStringRes = if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
        binding.etSearch.hint = getString(hintStringRes)
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
                binding.btnSearchVoice.clearAnimation()
                binding.etSearch.setText(query)
                SearchRecentStore.remember(requireContext(), query)
                if (binding.swGlobalSearch.isChecked) {
                    val currentLanguage = UserPreferences.currentProvider?.language ?: "es"
                    viewModel.searchGlobal(query, currentLanguage)
                } else {
                    viewModel.search(query)
                }
                refreshRecentSearches()
            },
            onError = { msg ->
                com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(
                    requireContext(),
                    msg,
                    R.string.voice_search,
                )
                binding.btnSearchVoice.clearAnimation()
                        val isIptv = UserPreferences.currentProvider is IptvProvider
        val hintStringRes = if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
        binding.etSearch.hint = getString(hintStringRes)
            },
            onListeningStateChanged = { isListening ->
                if (isListening) {
                    binding.btnSearchVoice.startAnimation(blink)
                    binding.etSearch.hint = getString(R.string.voice_prompt)
                    if (ExperimentalMobileDesign.enabled()) {
                        ExpMotion.popIn(binding.btnSearchVoice)
                    }
                } else {
                    binding.btnSearchVoice.clearAnimation()
                    val isIptv = UserPreferences.currentProvider is IptvProvider
                    val hintStringRes =
                        if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
                    binding.etSearch.hint = getString(hintStringRes)
                }
            }
        )

        binding.btnSearchVoice.apply {
            val showVoice = voiceHelper.isAvailable()
            val wasVisible = visibility == View.VISIBLE
            visibility = if (showVoice) View.VISIBLE else View.GONE
            if (ExperimentalMobileDesign.enabled()) {
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { applyExpPress() }
                if (showVoice && !wasVisible) ExpMotion.popIn(this)
            }

            setOnClickListener {
                ExpMotion.hapticTap(it)
                if (!voiceHelper.isListening) {
                    voiceHelper.startWithPermissionCheck()
                }
            }
        }

        if (ExperimentalMobileDesign.enabled()) {
            val searchField = binding.root.findViewById<View>(R.id.cl_search)
            searchField?.setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
            binding.btnSearchClear.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
            binding.btnSearchVoice.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
            binding.etSearch.setOnFocusChangeListener { _, hasFocus ->
                searchField?.isActivated = hasFocus
                searchField?.animate()
                    ?.scaleX(if (hasFocus) 1.01f else 1f)
                    ?.scaleY(if (hasFocus) 1.01f else 1f)
                    ?.setDuration(160L)
                    ?.start()
                if (hasFocus) {
                    searchField?.elevation = 6f * resources.displayMetrics.density
                } else {
                    searchField?.elevation = 0f
                }
            }
            binding.swGlobalSearch.let { toggle ->
                if (ExperimentalMobileDesign.enabled()) {
                    toggle.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
                    val density = resources.displayMetrics.density
                    toggle.setPadding(
                        (12 * density).toInt(),
                        (8 * density).toInt(),
                        (12 * density).toInt(),
                        (8 * density).toInt(),
                    )
                    with(com.dskja.betterstreamflix.utils.ExpPressEffects) { toggle.applyExpPress() }
                    fun tintTrack(checked: Boolean) {
                        val primary = com.google.android.material.color.MaterialColors.getColor(
                            toggle, androidx.appcompat.R.attr.colorPrimary,
                        )
                        val muted = com.google.android.material.color.MaterialColors.getColor(
                            toggle, com.google.android.material.R.attr.colorOnSurfaceVariant,
                        )
                        toggle.thumbTintList = android.content.res.ColorStateList.valueOf(
                            if (checked) primary else muted,
                        )
                        toggle.trackTintList = android.content.res.ColorStateList.valueOf(
                            if (checked) {
                                androidx.core.graphics.ColorUtils.setAlphaComponent(primary, 0x66)
                            } else {
                                androidx.core.graphics.ColorUtils.setAlphaComponent(muted, 0x44)
                            },
                        )
                    }
                    tintTrack(toggle.isChecked)
                    toggle.setOnCheckedChangeListener { v, checked ->
                        ExpMotion.hapticTap(v)
                        tintTrack(checked)
                    }
                }
                if (toggle.getTag(R.id.exp_enter_animated_tag) != true) {
                    toggle.setTag(R.id.exp_enter_animated_tag, true)
                    ExpMotion.popIn(toggle)
                }
            }
        }

        binding.btnSearchClear.apply {
            if (ExperimentalMobileDesign.enabled()) {
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { applyExpPress() }
            }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                binding.etSearch.setText("")
                val isIptv = UserPreferences.currentProvider is IptvProvider
                val hintStringRes =
                    if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
                binding.etSearch.hint = getString(hintStringRes)
                viewModel.search("")
            }
        }

        binding.rvSearch.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            val columns = (layoutManager as? GridLayoutManager)?.spanCount ?: SearchMobileRows.GRID_SPAN
            (layoutManager as? GridLayoutManager)?.spanSizeLookup =
                object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        val type = runCatching { appAdapter.getItemViewType(position) }
                            .getOrDefault(AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM.ordinal)
                        return SearchMobileRows.spanSize(type, columns)
                    }
                }
            addItemDecoration(
                SpacingItemDecoration(10.dp(requireContext()))
            )
        }
        initializeSearchSortControls()
        refreshRecentSearches()
    }

    private fun initializeSearchSortControls() {
        val chipDefault = binding.root.findViewById<TextView>(R.id.chip_search_sort_default) ?: return
        val chipNewest = binding.root.findViewById<TextView>(R.id.chip_search_sort_newest) ?: return
        val chipYear = binding.root.findViewById<TextView>(R.id.chip_search_filter_year) ?: return

        refreshSortChips()

        chipDefault.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.setSortMode(com.dskja.betterstreamflix.utils.SearchSortMode.PROVIDER_DEFAULT)
            refreshSortChips()
        }
        chipNewest.setOnClickListener {
            ExpMotion.hapticTap(it)
            viewModel.setSortMode(com.dskja.betterstreamflix.utils.SearchSortMode.NEWEST_FIRST)
            refreshSortChips()
        }
        chipYear.setOnClickListener {
            ExpMotion.hapticTap(it)
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
        val row = binding.root.findViewById<LinearLayout>(R.id.ll_search_recent) ?: return
        val recent = SearchRecentStore.list(requireContext())
        if (recent.isEmpty()) {
            strip.isVisible = false
            strip.setTag(R.id.exp_enter_animated_tag, null)
            row.removeAllViews()
            binding.root.findViewById<View>(R.id.v_search_recent_edge_fade_start)?.isVisible = false
            binding.root.findViewById<View>(R.id.v_search_recent_edge_fade_end)?.isVisible = false
            return
        }
        strip.isVisible = true
        binding.root.findViewById<View>(R.id.v_search_recent_edge_fade_start)?.isVisible = true
        binding.root.findViewById<View>(R.id.v_search_recent_edge_fade_end)?.isVisible = true
        @Suppress("UNCHECKED_CAST")
        val previousQueries = (strip.getTag(R.id.exp_search_recent_queries_tag) as? Set<String>) ?: emptySet()
        row.removeAllViews()
        val padH = 12.dp(requireContext())
        val padV = 8.dp(requireContext())
        val gap = 8.dp(requireContext())
        if (ExperimentalMobileDesign.enabled() && strip.getTag(R.id.exp_enter_animated_tag) != true) {
            strip.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.popIn(strip)
        }

        val label = TextView(requireContext()).apply {
            text = getString(R.string.search_recent_title)
            setTextAppearance(R.style.TextAppearance_Lumina_Caption)
            setPadding(0, padV, gap, padV)
        }
        row.addView(label)

        recent.forEachIndexed { index, query ->
            val chip = TextView(requireContext()).apply {
                text = query
                setTextAppearance(R.style.TextAppearance_Lumina_Caption)
                setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorOnSurface,
                    )
                )
                setBackgroundResource(ExperimentalMobileDesign.chipBackground())
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    ExpMotion.hapticTap(it)
                    binding.etSearch.setText(query)
                    SearchRecentStore.remember(requireContext(), query)
                    if (binding.swGlobalSearch.isChecked) {
                        val lang = UserPreferences.currentProvider?.language ?: "es"
                        viewModel.searchGlobal(query, lang)
                    } else {
                        viewModel.search(query)
                    }
                    refreshRecentSearches()
                }
            }
            with(com.dskja.betterstreamflix.utils.ExpPressEffects) { chip.applyExpPress() }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = gap }
            row.addView(chip, lp)
            if (ExperimentalMobileDesign.enabled() && query !in previousQueries) {
                chip.alpha = 0f
                chip.postDelayed({ ExpMotion.popIn(chip) }, 40L * index)
            }
        }
        strip.setTag(R.id.exp_search_recent_queries_tag, recent.toSet())

        val clear = TextView(requireContext()).apply {
            text = getString(R.string.search_recent_clear)
            setTextAppearance(R.style.TextAppearance_Lumina_Caption)
            setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurfaceVariant,
                )
            )
            setBackgroundResource(ExperimentalMobileDesign.chipBackground())
            setPadding(padH, padV, padH, padV)
            contentDescription = getString(R.string.search_recent_clear_cd)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                ExpMotion.hapticTap(it)
                SearchRecentStore.clear(requireContext())
                refreshRecentSearches()
            }
        }
        with(com.dskja.betterstreamflix.utils.ExpPressEffects) { clear.applyExpPress() }
        ExpMotion.popIn(clear)
        row.addView(clear)
    }

    private fun refreshSortChips() {
        val root = _binding?.root ?: return
        val chipDefault = root.findViewById<TextView>(R.id.chip_search_sort_default) ?: return
        val chipNewest = root.findViewById<TextView>(R.id.chip_search_sort_newest) ?: return
        val chipYear = root.findViewById<TextView>(R.id.chip_search_filter_year) ?: return
        SearchSortUi.bindChips(
            chipDefault = chipDefault,
            chipNewest = chipNewest,
            chipYear = chipYear,
            mode = viewModel.currentSortMode(),
            year = viewModel.currentYearFilter(),
        )
    }

    private fun clearSearchField() {
        val ui = _binding ?: return
        ui.etSearch.setText("")
        ui.etSearch.requestFocus()
        viewModel.search("")
    }

    private fun displaySearch(list: List<AppAdapter.Item>, hasMore: Boolean) {
        val rows = SearchMobileRows.local(list)
        binding.root.findViewById<View>(R.id.tv_search_empty)?.let { emptyView ->
            val cta = binding.root.findViewById<View>(R.id.btn_search_empty_cta)
            val emptyRule = binding.root.findViewById<View>(R.id.v_search_empty_rule)
            val showEmpty = rows.isEmpty() && viewModel.query.isNotBlank()
            if (showEmpty) {
                if (ExperimentalMobileDesign.enabled()) {
                    emptyView.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
                }
                if (emptyView.visibility != View.VISIBLE) {
                    emptyView.visibility = View.VISIBLE
                    emptyRule?.isVisible = ExperimentalMobileDesign.enabled()
                    if (ExperimentalMobileDesign.enabled()) {
                        ExpMotion.revealHeader(emptyView, emptyRule, cta)
                        ExpMotion.pulseAccentRule(emptyRule)
                    } else {
                        ExpMotion.fadeInAndShow(emptyView)
                    }
                } else {
                    emptyView.isVisible = true
                    emptyRule?.isVisible = ExperimentalMobileDesign.enabled()
                }
                cta?.let { chip ->
                    val wasVisible = chip.isVisible
                    chip.isVisible = true
                    if (chip is android.widget.TextView) {
                        chip.setText(
                            if (UserPreferences.currentProvider is IptvProvider) {
                                R.string.exp_empty_try_channel
                            } else {
                                R.string.exp_empty_clear_search
                            },
                        )
                    }
                    if (ExperimentalMobileDesign.enabled()) {
                        chip.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
                        with(com.dskja.betterstreamflix.utils.ExpPressEffects) { chip.applyExpPress() }
                        if (!wasVisible) ExpMotion.popIn(chip)
                    }
                    chip.setOnClickListener {
                        ExpMotion.hapticTap(it)
                        clearSearchField()
                    }
                }
            } else {
                if (ExperimentalMobileDesign.enabled() && emptyView.isVisible) {
                    ExpMotion.fadeOutAndHide(emptyView)
                    emptyRule?.let { ExpMotion.fadeOutAndHide(it) }
                    cta?.let { ExpMotion.fadeOutAndHide(it) }
                } else {
                    emptyView.isVisible = false
                    emptyRule?.isVisible = false
                    cta?.isVisible = false
                }
            }
        }
        appAdapter.submitList(rows)

        if (hasMore && viewModel.query.isNotBlank() && rows.size < SearchMobileRows.MAX_LOCAL) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMore() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    /** @return true while Global Search is still working and has nothing to show yet. */
    private fun displayGlobalSearch(providerResults: List<ProviderResult>): Boolean {
        val plan = SearchMobileRows.global(providerResults) { name, count ->
            val resultText = if (count == 1) getString(R.string.result) else getString(R.string.results)
            "$name - $count $resultText"
        }
        appAdapter.submitList(plan.items)
        appAdapter.setOnLoadMoreListener(null)

        val isEmpty = !plan.stillLoading && !plan.hasHits && viewModel.query.isNotBlank()
        binding.root.findViewById<View>(R.id.tv_search_empty)?.let { emptyView ->
            val cta = binding.root.findViewById<View>(R.id.btn_search_empty_cta)
            val emptyRule = binding.root.findViewById<View>(R.id.v_search_empty_rule)
            if (isEmpty) {
                if (ExperimentalMobileDesign.enabled()) {
                    emptyView.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
                }
                if (emptyView.visibility != View.VISIBLE) {
                    emptyView.visibility = View.VISIBLE
                    emptyRule?.isVisible = ExperimentalMobileDesign.enabled()
                    if (ExperimentalMobileDesign.enabled()) {
                        ExpMotion.revealHeader(emptyView, emptyRule, cta)
                        ExpMotion.pulseAccentRule(emptyRule)
                    } else {
                        ExpMotion.fadeInAndShow(emptyView)
                    }
                } else {
                    emptyView.isVisible = true
                    emptyRule?.isVisible = ExperimentalMobileDesign.enabled()
                }
                cta?.let { chip ->
                    val wasVisible = chip.isVisible
                    chip.isVisible = true
                    if (chip is android.widget.TextView) {
                        chip.setText(
                            if (UserPreferences.currentProvider is IptvProvider) {
                                R.string.exp_empty_try_channel
                            } else {
                                R.string.exp_empty_clear_search
                            },
                        )
                    }
                    if (ExperimentalMobileDesign.enabled()) {
                        chip.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
                        with(com.dskja.betterstreamflix.utils.ExpPressEffects) { chip.applyExpPress() }
                        if (!wasVisible) ExpMotion.popIn(chip)
                    }
                    chip.setOnClickListener {
                        ExpMotion.hapticTap(it)
                        clearSearchField()
                    }
                }
            } else {
                if (ExperimentalMobileDesign.enabled() && emptyView.isVisible) {
                    ExpMotion.fadeOutAndHide(emptyView)
                    emptyRule?.let { ExpMotion.fadeOutAndHide(it) }
                    cta?.let { ExpMotion.fadeOutAndHide(it) }
                } else {
                    emptyView.isVisible = false
                    emptyRule?.isVisible = false
                    cta?.isVisible = false
                }
            }
        }
        return plan.stillLoading && !plan.hasHits
    }
}
