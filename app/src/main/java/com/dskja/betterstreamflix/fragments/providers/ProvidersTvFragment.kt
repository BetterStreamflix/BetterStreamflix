package com.dskja.betterstreamflix.fragments.providers

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.databinding.FragmentProvidersTvBinding
import com.dskja.betterstreamflix.models.Provider as ModelProvider
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.LoggingUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.launch
import java.util.Locale

class ProvidersTvFragment : Fragment() {

    private var _binding: FragmentProvidersTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<ProvidersViewModel>()

    private val appAdapter = AppAdapter()

    private data class Language(
        val code: String,
        val name: String,
    )

    private var languageOptions: List<Language> = emptyList()
    private var languageLabels: List<String> = emptyList()
    private var selectedLanguageIndex: Int = 0

    private var allProviders: List<ModelProvider> = emptyList()
    private var searchQuery: String = ""
    private var hasTakenListFocus: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProvidersTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeProviders()
        initializeProviderSearch()
        disarmProviderLoading()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    ProvidersViewModel.State.Loading -> binding.isLoading.apply {
                        disarmProviderLoading()
                        root.visibility = View.VISIBLE
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is ProvidersViewModel.State.SuccessLoading -> {
                        displayProviders(state.providers)
                        binding.isLoading.root.visibility = View.GONE
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(
                            binding.isLoading.root, false,
                        )
                    }
                    is ProvidersViewModel.State.FailedLoading -> {
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.isLoading.apply {
                            com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, false)
                            gIsLoadingRetry.visibility = View.VISIBLE
                            com.dskja.betterstreamflix.utils.ExpPressEffects.animateLoadingError(root)
                            btnIsLoadingRetry.setOnClickListener {
                                viewModel.getProviders()
                            }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(
                                    requireContext(),
                                    getString(R.string.clear_cache_done),
                                    R.string.loading_error_clear_cache,
                                )
                                viewModel.getProviders()
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
                            binding.rvProviders.visibility = View.GONE
                            btnIsLoadingRetry.requestFocus()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun initializeProviders() {
        binding.sProvidersLanguage.apply {
            languageOptions = Provider.providers.keys
                .distinctBy { it.language }
                .map {
                    val locale = Locale.forLanguageTag(it.language)
                    Language(
                        code = it.language,
                        name = locale.getDisplayLanguage(locale)
                            .replaceFirstChar { char -> char.titlecase() },
                    )
                }
                .sortedBy { it.name.lowercase() }

            languageLabels = mutableListOf(
                context.getString(R.string.providers_all_languages),
                context.getString(R.string.providers_favorites),
            ).apply {
                addAll(languageOptions.map { it.name })
            }

            val spinnerAdapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                languageLabels.toTypedArray(),
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setAdapter(spinnerAdapter)

            selectedLanguageIndex = when (val lang = UserPreferences.providerLanguage) {
                null -> 0
                "favorites" -> 1
                else -> {
                    val index = languageOptions.indexOfFirst { it.code == lang }
                    if (index != -1) index + 2 else 0
                }
            }

            val leanback = DeviceCapabilities.isLeanbackDevice(requireContext()) ||
                DeviceCapabilities.isAmazonFireTv(requireContext())

            if (leanback) {
                onItemSelectedListener = null
                setSelection(selectedLanguageIndex, false)
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        showLanguageDialog()
                    }
                    true
                }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_UP &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == KeyEvent.KEYCODE_ENTER ||
                            keyCode == KeyEvent.KEYCODE_SPACE)
                    ) {
                        showLanguageDialog()
                        true
                    } else {
                        false
                    }
                }
                applyLanguageFilter(selectedLanguageIndex)
            } else {
                setOnTouchListener(null)
                setOnKeyListener(null)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        applyLanguageFilter(position)
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
                setSelection(selectedLanguageIndex)
            }
        }

        binding.rvProviders.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(
                    requireContext().resources.getDimension(R.dimen.providers_spacing).toInt()
                )
            )
        }
    }

    private fun initializeProviderSearch() {
        binding.etProvidersSearch.apply {
            setOnEditorActionListener { _, actionId, event ->
                val isSubmitAction = actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE
                val isSubmitKey = event?.action == KeyEvent.ACTION_DOWN &&
                    (event.keyCode == KeyEvent.KEYCODE_ENTER ||
                        event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                if (isSubmitAction || isSubmitKey) {
                    if (binding.rvProviders.isVisible && appAdapter.itemCount > 0) {
                        binding.rvProviders.requestFocus()
                    } else if (binding.btnProvidersEmptyCta.isVisible) {
                        binding.btnProvidersEmptyCta.requestFocus()
                    }
                    true
                } else {
                    false
                }
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    moveProviderSearchDown()
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString().orEmpty()
                    val showClear = searchQuery.isNotBlank()
                    binding.btnProvidersSearchClear.isVisible = showClear
                    binding.etProvidersSearch.nextFocusRightId =
                        if (showClear) binding.btnProvidersSearchClear.id else View.NO_ID
                    applyProviderFilter(requestListFocus = false)
                }
            })
        }

        binding.btnProvidersSearchClear.setOnClickListener {
            binding.etProvidersSearch.setText("")
            binding.etProvidersSearch.requestFocus()
        }
        binding.btnProvidersSearchClear.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                moveProviderSearchDown()
                true
            } else {
                false
            }
        }
    }

    private fun disarmProviderLoading() {
        val root = _binding?.isLoading?.root ?: return
        root.isFocusable = false
        root.isFocusableInTouchMode = false
        root.isClickable = false
    }

    private fun moveProviderSearchDown() {
        val ui = _binding ?: return
        when (
            ProvidersSearch.focusDown(
                hasRows = ui.rvProviders.isVisible && appAdapter.itemCount > 0,
                emptyCtaVisible = ui.btnProvidersEmptyCta.isVisible,
            )
        ) {
            ProvidersSearch.FocusDown.LIST -> ui.rvProviders.requestFocus()
            ProvidersSearch.FocusDown.EMPTY_CTA -> ui.btnProvidersEmptyCta.requestFocus()
            ProvidersSearch.FocusDown.STAY -> Unit
        }
    }

    private fun showLanguageDialog() {
        if (languageLabels.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.providers_choose_title)
            .setSingleChoiceItems(
                languageLabels.toTypedArray(),
                selectedLanguageIndex,
            ) { dialog, which ->
                applyLanguageFilter(which)
                binding.sProvidersLanguage.setSelection(which, false)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyLanguageFilter(position: Int) {
        selectedLanguageIndex = position
        hasTakenListFocus = false
        when (position) {
            0 -> {
                viewModel.getProviders()
                UserPreferences.providerLanguage = null
            }
            1 -> {
                viewModel.getProviders("favorites")
                UserPreferences.providerLanguage = "favorites"
            }
            else -> {
                val langCode = languageOptions.getOrNull(position - 2)?.code ?: return
                viewModel.getProviders(langCode)
                UserPreferences.providerLanguage = langCode
            }
        }
    }

    private fun displayProviders(providers: List<ModelProvider>) {
        allProviders = providers
        applyProviderFilter(requestListFocus = !hasTakenListFocus)
    }

    private fun applyProviderFilter(requestListFocus: Boolean) {
        val filtered = ProvidersSearch.filter(allProviders, searchQuery)
        appAdapter.submitList(filtered.onEach {
            it.itemType = AppAdapter.Type.PROVIDER_TV_ITEM
        })
        val empty = filtered.isEmpty()
        val searching = searchQuery.isNotBlank()
        binding.rvProviders.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvProvidersEmpty.setText(
            if (searching) R.string.providers_search_empty else R.string.providers_empty,
        )
        binding.btnProvidersEmptyCta.setText(
            if (searching) R.string.providers_search_clear else R.string.exp_empty_all_languages,
        )
        ExpEmptyChrome.bind(
            emptyView = binding.tvProvidersEmpty,
            emptyRule = binding.vProvidersEmptyRule,
            emptyCta = binding.btnProvidersEmptyCta,
            visible = empty,
            tintOnSurfaceVariant = false,
        )
        if (empty) {
            binding.btnProvidersEmptyCta.visibility = View.VISIBLE
            binding.btnProvidersEmptyCta.setOnClickListener {
                if (searchQuery.isNotBlank()) {
                    binding.etProvidersSearch.setText("")
                    binding.etProvidersSearch.requestFocus()
                } else {
                    applyLanguageFilter(0)
                }
            }
        }
        val downId = when (ProvidersSearch.focusDown(hasRows = !empty, emptyCtaVisible = empty)) {
            ProvidersSearch.FocusDown.LIST -> binding.rvProviders.id
            ProvidersSearch.FocusDown.EMPTY_CTA -> binding.btnProvidersEmptyCta.id
            ProvidersSearch.FocusDown.STAY -> binding.etProvidersSearch.id
        }
        binding.etProvidersSearch.nextFocusDownId = downId
        binding.btnProvidersSearchClear.nextFocusDownId = downId
        when {
            !empty && requestListFocus -> {
                binding.rvProviders.requestFocus()
                hasTakenListFocus = true
            }
            empty && searching && binding.btnProvidersEmptyCta.isVisible -> {
                // Keep focus on the search field while typing; CTA is reachable via D-pad down.
            }
        }
    }
}
