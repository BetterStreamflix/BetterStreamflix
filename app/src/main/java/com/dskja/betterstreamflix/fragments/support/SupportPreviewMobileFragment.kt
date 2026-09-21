package com.dskja.betterstreamflix.fragments.support

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.FragmentSupportPreviewMobileBinding
import com.dskja.betterstreamflix.databinding.IncludeSupportHeroBinding
import com.dskja.betterstreamflix.support.SupportHubBinder
import com.dskja.betterstreamflix.support.SupportLinkOpener
import com.dskja.betterstreamflix.support.SupportProvider
import com.dskja.betterstreamflix.support.SupportUiBinder
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign

/**
 * Internal component showcase — only reachable when Experimental UI is enabled.
 */
class SupportPreviewMobileFragment : Fragment() {

    private var _binding: FragmentSupportPreviewMobileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSupportPreviewMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (ExperimentalMobileDesign.enabled()) {
            ExpMotion.enterScreen(binding.root)
            ExperimentalMobileDesign.applyReducedGlass(binding.root)
            binding.root.setBackgroundResource(R.drawable.bg_exp_canvas_atmosphere)
            binding.btnSupportPreviewBack.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
            ExpMotion.revealHeader(
                binding.tvSupportPreviewTitle,
                binding.vSupportPreviewAccentRule,
            )
            ExpMotion.pulseAccentRule(binding.vSupportPreviewAccentRule)
            ExpMotion.popIn(binding.btnSupportPreviewBack)
            binding.btnSupportPreviewBack.applyExpPress()
            val onSurface = com.google.android.material.color.MaterialColors.getColor(
                requireContext(),
                com.google.android.material.R.attr.colorOnSurface,
                0xFFF5F5F5.toInt(),
            )
            binding.tvSupportPreviewTitle.setTextColor(onSurface)
            binding.btnSupportPreviewBack.imageTintList =
                android.content.res.ColorStateList.valueOf(onSurface)
            val sections = listOf(
                binding.previewHero.root,
                binding.previewBanner.root,
                binding.llPreviewProviders,
                binding.llPreviewImpact,
                binding.llPreviewFaq,
            )
            sections.forEachIndexed { index, section ->
                section.alpha = 0f
                section.postDelayed({ ExpMotion.popIn(section) }, 48L * (index + 1))
            }
            binding.previewBanner.root.setBackgroundResource(
                ExperimentalMobileDesign.glassCardBackground(),
            )
            with(ExpPressEffects) {
                binding.previewBanner.root.applyExpPress()
            }
        }
        binding.btnSupportPreviewBack.setOnClickListener {
            ExpMotion.hapticTap(it)
            findNavController().navigateUp()
        }
        val hero = IncludeSupportHeroBinding.bind(binding.previewHero.root)
        if (ExperimentalMobileDesign.enabled()) {
            hero.root.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
            ExperimentalMobileDesign.applyReducedGlass(hero.root)
            ExpMotion.revealHeader(
                hero.tvSupportHeroTitle,
                hero.vSupportHeroRule,
                hero.tvSupportHeroBody,
            )
            ExpMotion.pulseAccentRule(hero.vSupportHeroRule)
            hero.btnSupportHeroPrimary.setBackgroundResource(
                ExperimentalMobileDesign.primaryButtonBackground(),
            )
            hero.btnSupportHeroSecondary.setBackgroundResource(
                ExperimentalMobileDesign.chipBackground(),
            )
            hero.btnSupportHeroPrimary.applyExpPress()
            hero.btnSupportHeroSecondary.applyExpPress()
        }
        hero.btnSupportHeroPrimary.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openProvider(requireContext(), SupportProvider.BUY_ME_A_COFFEE)
        }
        hero.btnSupportHeroSecondary.setOnClickListener {
            ExpMotion.hapticTap(it)
            runCatching { findNavController().navigate(R.id.support_details) }
        }
        SupportUiBinder.bindProviderCards(
            requireContext(),
            binding.llPreviewProviders,
            horizontal = false,
            animate = true,
        )
        SupportHubBinder.bindImpact(requireContext(), binding.llPreviewImpact)
        SupportHubBinder.bindFaq(requireContext(), binding.llPreviewFaq)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
