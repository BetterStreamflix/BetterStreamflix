package com.dskja.betterstreamflix.fragments.providers

import com.dskja.betterstreamflix.utils.ExpDialogChrome

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.databinding.FragmentProvidersMobileBinding
import com.dskja.betterstreamflix.models.Provider as ModelProvider
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpNavAutoHide
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import kotlinx.coroutines.launch
import java.util.Locale

class ProvidersMobileFragment : Fragment() {

    private var _binding: FragmentProvidersMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<ProvidersViewModel>()

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProvidersMobileBinding.bind(
            inflater.inflate(
                R.layout.fragment_providers_mobile,
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
        ExpMotion.staggerFirstFill(binding.rvProviders)
        if (ExperimentalMobileDesign.enabled()) {
            ExpMotion.revealHeader(
                binding.root.findViewById(R.id.tv_providers_eyebrow),
                binding.root.findViewById(R.id.tv_providers_label),
                binding.root.findViewById(R.id.tv_providers_subtitle),
                binding.root.findViewById(R.id.v_providers_rule),
            )
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_providers_rule))
        }

        initializeProviders()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    ProvidersViewModel.State.Loading -> binding.isLoading.apply {
                        ExpMotion.fadeInAndShow(root)
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is ProvidersViewModel.State.SuccessLoading -> {
                        displayProviders(state.providers)
                        ExpMotion.fadeOutAndHide(binding.isLoading.root)
                    }
                    is ProvidersViewModel.State.FailedLoading -> {
                        if (!ExperimentalMobileDesign.enabled()) {
                            Toast.makeText(
                                requireContext(),
                                state.error.message ?: "",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        binding.isLoading.apply {
                            com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, false)
                            gIsLoadingRetry.visibility = View.VISIBLE
                            com.dskja.betterstreamflix.utils.ExpPressEffects.animateLoadingError(root)
                            val doRetry = { viewModel.getProviders() }
                            btnIsLoadingRetry.setOnClickListener { doRetry() }
                            btnIsLoadingClearCache.setOnClickListener {
                                com.dskja.betterstreamflix.utils.CacheUtils.clearAppCache(requireContext())
                                ExpDialogChrome.notify(requireContext(), R.string.clear_cache_done, R.string.loading_error_clear_cache)
                                doRetry()
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                com.dskja.betterstreamflix.utils.LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
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
            class Language(
                val code: String,
                val name: String,
            )

            val languages = Provider.providers.keys
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

            val labels = mutableListOf(
                context.getString(R.string.providers_all_languages),
                context.getString(R.string.providers_favorites),
            ).apply {
                addAll(languages.map { it.name })
            }
            val spinnerAdapter = ArrayAdapter(
                this.context,
                android.R.layout.simple_spinner_item,
                labels.toTypedArray(),
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setAdapter(spinnerAdapter)

            if (ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.spinnerBackground())
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { applyExpPress() }
                runCatching {
                    setPopupBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
                }
                if (getTag(R.id.exp_enter_animated_tag) != true) {
                    setTag(R.id.exp_enter_animated_tag, true)
                    ExpMotion.popIn(this)
                    binding.root.findViewById<View>(R.id.iv_providers_language)?.let {
                        ExpMotion.popIn(it)
                    }
                }
            }

            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    ExpMotion.hapticTap(this@apply)
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
                            val langCode = languages[position - 2].code
                            viewModel.getProviders(langCode)
                            UserPreferences.providerLanguage = langCode
                        }
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            setSelection(
                when (val lang = UserPreferences.providerLanguage) {
                    null -> 0
                    "favorites" -> 1
                    else -> {
                        val index = languages.indexOfFirst { it.code == lang }
                        if (index != -1) index + 2 else 0
                    }
                }
            )
        }

        binding.rvProviders.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(32.dp(requireContext()))
            )
        }
    }

    private fun displayProviders(providers: List<ModelProvider>) {
        appAdapter.submitList(providers.onEach {
            it.itemType = AppAdapter.Type.PROVIDER_MOBILE_ITEM
        })
        val empty = providers.isEmpty()
        ExpEmptyChrome.bind(
            emptyView = binding.root.findViewById(R.id.tv_providers_empty),
            emptyRule = binding.root.findViewById(R.id.v_providers_empty_rule),
            emptyCta = binding.root.findViewById(R.id.btn_providers_empty_cta),
            visible = empty,
            tintOnSurfaceVariant = false,
            onCtaClick = { binding.sProvidersLanguage.setSelection(0) },
        )
        binding.rvProviders.visibility = if (empty) View.GONE else View.VISIBLE
    }
}