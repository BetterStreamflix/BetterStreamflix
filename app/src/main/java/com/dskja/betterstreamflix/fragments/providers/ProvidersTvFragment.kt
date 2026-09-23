package com.dskja.betterstreamflix.fragments.providers

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    ProvidersViewModel.State.Loading -> binding.isLoading.apply {
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
        appAdapter.submitList(providers.onEach {
            it.itemType = AppAdapter.Type.PROVIDER_TV_ITEM
        })
        val empty = providers.isEmpty()
        binding.rvProviders.visibility = if (empty) View.GONE else View.VISIBLE
        ExpEmptyChrome.bind(
            emptyView = binding.root.findViewById(R.id.tv_providers_empty),
            emptyRule = binding.root.findViewById(R.id.v_providers_empty_rule),
            emptyCta = binding.root.findViewById(R.id.btn_providers_empty_cta),
            visible = empty,
            tintOnSurfaceVariant = false,
            onCtaClick = { applyLanguageFilter(0) },
        )
if (!empty) {
            binding.rvProviders.requestFocus()
        }
    }
}
