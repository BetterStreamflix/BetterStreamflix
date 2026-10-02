package com.dskja.betterstreamflix.fragments.support

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.FragmentSupportMobileBinding
import com.dskja.betterstreamflix.support.SupportHubBinder
import com.dskja.betterstreamflix.support.SupportLinkOpener
import com.dskja.betterstreamflix.support.SupportPromptPolicy
import com.dskja.betterstreamflix.support.SupportProvider
import com.dskja.betterstreamflix.support.SupportUiBinder
import com.dskja.betterstreamflix.ui.support.SupportThanksDialog
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign

class SupportMobileFragment : Fragment() {

    private var _binding: FragmentSupportMobileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSupportMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        SupportPromptPolicy.markHubVisited()

        binding.btnSupportBack.setOnClickListener {
            ExpMotion.hapticTap(it)
            findNavController().navigateUp()
        }

        val hero = binding.includeSupportHero
        hero.btnSupportHeroPrimary.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openProvider(requireContext(), SupportProvider.BUY_ME_A_COFFEE)
        }
        hero.btnSupportHeroSecondary.setOnClickListener {
            ExpMotion.hapticTap(it)
            runCatching {
                findNavController().navigate(R.id.support_details)
            }
        }

        SupportUiBinder.bindProviderCards(
            requireContext(),
            binding.llSupportProviders,
            horizontal = false,
            animate = ExperimentalMobileDesign.enabled(),
        )
        SupportHubBinder.bindSupporters(
            requireContext(),
            binding.llSupportSupporters,
            binding.tvSupportSupportersTitle,
            binding.tvSupportSupportersSubtitle,
        )
        SupportHubBinder.bindImpact(requireContext(), binding.llSupportImpact)
        SupportHubBinder.bindFaq(requireContext(), binding.llSupportFaq)

        binding.tvSupportDetailsLink.setOnClickListener {
            ExpMotion.hapticTap(it)
            runCatching { findNavController().navigate(R.id.support_details) }
        }
        binding.tvSupportIssues.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openIssues(requireContext())
        }
        binding.tvSupportReleases.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openReleases(requireContext())
        }

        if (ExperimentalMobileDesign.enabled()) {
            ExpMotion.enterScreen(binding.root)
            ExperimentalMobileDesign.applyReducedGlass(binding.root)
            binding.root.setBackgroundResource(R.drawable.bg_exp_canvas_atmosphere)
            ExpMotion.revealHeader(binding.tvSupportTitle, binding.tvSupportSubtitle)
            binding.vSupportAccentRule.visibility = View.VISIBLE
            ExpMotion.pulseAccentRule(binding.vSupportAccentRule)
            val onSurface = com.google.android.material.color.MaterialColors.getColor(
                requireContext(),
                com.google.android.material.R.attr.colorOnSurface,
                0xFFF5F5F5.toInt(),
            )
            val onVariant = com.google.android.material.color.MaterialColors.getColor(
                requireContext(),
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                0xFFA3A3A3.toInt(),
            )
            binding.tvSupportTitle.setTextColor(onSurface)
            binding.tvSupportSubtitle.setTextColor(onVariant)
            binding.btnSupportBack.imageTintList =
                android.content.res.ColorStateList.valueOf(onSurface)
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
            hero.btnSupportHeroPrimary.setBackgroundResource(
                ExperimentalMobileDesign.primaryButtonBackground(),
            )
            hero.btnSupportHeroSecondary.setBackgroundResource(
                ExperimentalMobileDesign.chipBackground(),
            )
            hero.root.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
            ExperimentalMobileDesign.applyReducedGlass(hero.root)
            binding.btnSupportBack.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
            ExpMotion.popIn(binding.btnSupportBack)
            with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                binding.btnSupportBack.applyExpPress()
                hero.btnSupportHeroPrimary.applyExpPress()
                hero.btnSupportHeroSecondary.applyExpPress()
                binding.tvSupportDetailsLink.applyExpPress()
                binding.tvSupportIssues.applyExpPress()
                binding.tvSupportReleases.applyExpPress()
            }
            binding.llSupportTransparency.setBackgroundResource(
                ExperimentalMobileDesign.glassCardBackground(),
            )
            listOf(
                binding.tvSupportIntro,
                binding.tvSupportOptionsTitle,
                binding.llSupportProviders,
                binding.tvSupportSupportersTitle,
                binding.llSupportSupporters,
                binding.tvSupportTransparencyTitle,
                binding.llSupportTransparency,
                binding.tvSupportImpactTitle,
                binding.llSupportImpact,
                binding.tvSupportFaqTitle,
                binding.llSupportFaq,
                binding.tvSupportDetailsLink,
                binding.tvSupportIssues,
                binding.tvSupportReleases,
            ).forEachIndexed { index, section ->
                section.alpha = 0f
                section.postDelayed({ ExpMotion.popIn(section) }, 36L * (index + 1))
            }
            binding.svSupport.let { scroll ->
                ExpMotion.startAnimation(scroll, R.anim.exp_fade_slide_up)
            }
        }
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
