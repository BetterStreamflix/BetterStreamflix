package com.dskja.betterstreamflix.fragments.support

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.FragmentSupportTvBinding
import com.dskja.betterstreamflix.support.SupportHubBinder
import com.dskja.betterstreamflix.support.SupportLinkOpener
import com.dskja.betterstreamflix.support.SupportPromptPolicy
import com.dskja.betterstreamflix.support.SupportProvider
import com.dskja.betterstreamflix.support.SupportUiBinder
import com.dskja.betterstreamflix.ui.support.SupportThanksDialog
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign

class SupportTvFragment : Fragment() {

    private var _binding: FragmentSupportTvBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSupportTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        SupportPromptPolicy.markHubVisited()

        binding.btnSupportBack.setOnClickListener {
            findNavController().navigateUp()
        }

        val hero = binding.includeSupportHero
        hero.btnSupportHeroPrimary.setOnClickListener {
            SupportLinkOpener.openProvider(requireContext(), SupportProvider.BUY_ME_A_COFFEE)
        }
        // TV has no details destination — scroll to the inline “why” card instead.
        hero.btnSupportHeroSecondary.setOnClickListener {
            binding.svSupport.post {
                binding.tvSupportWhy?.let { why ->
                    binding.svSupport.smoothScrollTo(0, why.top)
                    why.requestFocus()
                }
            }
        }
        hero.btnSupportHeroSecondary.setText(R.string.support_hero_cta_secondary)
        SupportUiBinder.applyFocusScale(hero.btnSupportHeroPrimary)
        SupportUiBinder.applyFocusScale(hero.btnSupportHeroSecondary)

        SupportUiBinder.bindProviderCards(
            requireContext(),
            binding.llSupportProviders,
            horizontal = true,
            animate = ExperimentalMobileDesign.enabled(),
        )
        SupportHubBinder.bindImpact(requireContext(), binding.llSupportImpact)
        SupportHubBinder.bindFaq(requireContext(), binding.llSupportFaq)

        if (ExperimentalMobileDesign.enabled()) {
            ExpMotion.enterScreen(binding.root)
            ExperimentalMobileDesign.applyReducedGlass(binding.root)
            binding.root.setBackgroundResource(R.drawable.bg_exp_canvas_atmosphere)
            ExpMotion.revealHeader(binding.tvSupportTitle)
            val onSurface = com.google.android.material.color.MaterialColors.getColor(
                requireContext(),
                com.google.android.material.R.attr.colorOnSurface,
                0xFFF5F5F5.toInt(),
            )
            binding.tvSupportTitle.setTextColor(onSurface)
            binding.btnSupportBack.imageTintList =
                android.content.res.ColorStateList.valueOf(onSurface)
            binding.btnSupportBack.setBackgroundResource(
                ExperimentalMobileDesign.iconChipBackground(),
            )
            ExpMotion.popIn(binding.btnSupportBack)
            hero.root.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
            ExperimentalMobileDesign.applyReducedGlass(hero.root)
            hero.btnSupportHeroPrimary.setBackgroundResource(
                ExperimentalMobileDesign.primaryButtonBackground(),
            )
            hero.btnSupportHeroSecondary.setBackgroundResource(
                ExperimentalMobileDesign.chipBackground(),
            )
            ExpMotion.revealHeader(
                hero.btnSupportHeroPrimary,
                hero.btnSupportHeroSecondary,
            )
            ExpMotion.revealHeader(
                hero.tvSupportHeroTitle,
                hero.vSupportHeroRule,
                hero.tvSupportHeroBody,
            )
            ExpMotion.pulseAccentRule(hero.vSupportHeroRule)
            with(ExpPressEffects) {
                binding.btnSupportBack.applyExpPress()
                hero.btnSupportHeroPrimary.applyExpPress()
                hero.btnSupportHeroSecondary.applyExpPress()
            }
            listOf(
                binding.llSupportProviders,
                binding.llSupportImpact,
                binding.llSupportFaq,
            ).forEachIndexed { index, section ->
                section.alpha = 0f
                section.postDelayed({ ExpMotion.popIn(section) }, 40L * (index + 1))
            }
        }

        hero.btnSupportHeroPrimary.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (SupportLinkOpener.consumeAppreciationPending()) {
            runCatching { SupportThanksDialog(requireContext()).show() }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
