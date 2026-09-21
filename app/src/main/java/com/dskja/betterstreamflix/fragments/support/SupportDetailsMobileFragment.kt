package com.dskja.betterstreamflix.fragments.support

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.FragmentSupportDetailsMobileBinding
import com.dskja.betterstreamflix.support.SupportHubBinder
import com.dskja.betterstreamflix.support.SupportLinkOpener
import com.dskja.betterstreamflix.support.SupportProvider
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign

class SupportDetailsMobileFragment : Fragment() {

    private var _binding: FragmentSupportDetailsMobileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSupportDetailsMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (ExperimentalMobileDesign.enabled()) {
            ExpMotion.enterScreen(binding.root)
            ExperimentalMobileDesign.applyReducedGlass(binding.root)
            binding.root.setBackgroundResource(R.drawable.bg_exp_canvas_atmosphere)
            binding.btnSupportDetailsBack.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
            ExpMotion.revealHeader(
                binding.tvSupportDetailsTitle,
                binding.vSupportDetailsAccentRule,
                binding.tvSupportDetailsBody,
            )
            ExpMotion.pulseAccentRule(binding.vSupportDetailsAccentRule)
            ExpMotion.revealHeader(binding.llSupportDetailsImpact)
            ExpMotion.popIn(binding.btnSupportDetailsBack)
            binding.btnSupportDetailsBack.applyExpPress()
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
            binding.tvSupportDetailsTitle.setTextColor(onSurface)
            binding.tvSupportDetailsBody.setTextColor(onVariant)
            binding.btnSupportDetailsBack.imageTintList =
                android.content.res.ColorStateList.valueOf(onSurface)
            binding.btnSupportDetailsCta.setBackgroundResource(
                ExperimentalMobileDesign.primaryButtonBackground(),
            )
            listOf(
                binding.btnSupportDetailsPatreon,
                binding.btnSupportDetailsSponsors,
                binding.btnSupportDetailsDiscord,
                binding.btnSupportDetailsTelegram,
            ).forEach { btn ->
                btn.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
            }
            listOf(
                binding.btnSupportDetailsCta,
                binding.btnSupportDetailsPatreon,
                binding.btnSupportDetailsSponsors,
                binding.btnSupportDetailsDiscord,
                binding.btnSupportDetailsTelegram,
            ).forEachIndexed { index, btn ->
                btn.applyExpPress()
                btn.postDelayed({ ExpMotion.popIn(btn) }, 36L * (index + 1))
            }
            ExpMotion.popIn(binding.llSupportDetailsImpact)
        }
        binding.btnSupportDetailsBack.setOnClickListener {
            ExpMotion.hapticTap(it)
            findNavController().navigateUp()
        }
        binding.btnSupportDetailsCta.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openProvider(requireContext(), SupportProvider.BUY_ME_A_COFFEE)
        }
        binding.btnSupportDetailsPatreon.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openProvider(requireContext(), SupportProvider.PATREON)
        }
        binding.btnSupportDetailsSponsors.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openProvider(requireContext(), SupportProvider.GITHUB_SPONSORS)
        }
        binding.btnSupportDetailsDiscord.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openProvider(requireContext(), SupportProvider.DISCORD)
        }
        binding.btnSupportDetailsTelegram.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openProvider(requireContext(), SupportProvider.TELEGRAM)
        }
        SupportHubBinder.bindImpact(requireContext(), binding.llSupportDetailsImpact)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
