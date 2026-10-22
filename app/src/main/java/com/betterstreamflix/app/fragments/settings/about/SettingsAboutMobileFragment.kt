package com.betterstreamflix.app.fragments.settings.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.betterstreamflix.app.BuildConfig
import com.betterstreamflix.app.R
import com.betterstreamflix.app.databinding.FragmentAboutMobileBinding
import com.betterstreamflix.app.support.SupportHubBinder
import com.betterstreamflix.app.support.SupportLinkOpener
import com.betterstreamflix.app.support.SupportProvider
import com.betterstreamflix.app.support.SupportUrls
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.utils.ExpPressEffects.applyExpPress

class SettingsAboutMobileFragment : Fragment() {

    private var _binding: FragmentAboutMobileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAboutMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvAboutVersion.text = getString(
            com.betterstreamflix.app.R.string.settings_about_version_name,
            BuildConfig.VERSION_NAME,
        )

        SupportHubBinder.bindSupporters(
            requireContext(),
            binding.llAboutSupporters,
            binding.tvAboutSupportersTitle,
            binding.tvAboutSupportersSubtitle,
        )

        ExpMotion.enterScreen(binding.root)
        ExpMotion.revealHeader(
            binding.tvAboutTitle,
            binding.tvAboutBrand,
            binding.root.findViewById(com.betterstreamflix.app.R.id.v_about_accent_rule),
            binding.tvAboutVersion,
        )
        ExpMotion.pulseAccentRule(binding.root.findViewById(com.betterstreamflix.app.R.id.v_about_accent_rule))
        binding.root.setBackgroundResource(com.betterstreamflix.app.R.drawable.bg_exp_canvas_atmosphere)
        binding.btnAboutBack.setBackgroundResource(R.drawable.bg_detail_icon_chip)
        ExpMotion.popIn(binding.btnAboutBack)
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
        val primary = com.google.android.material.color.MaterialColors.getColor(
            requireContext(),
            androidx.appcompat.R.attr.colorPrimary,
            0xFFE50914.toInt(),
        )
        binding.tvAboutTitle.setTextColor(onSurface)
        binding.tvAboutBrand.setTextColor(onSurface)
        binding.tvAboutVersion.setTextColor(onVariant)
        binding.btnAboutBack.imageTintList =
            android.content.res.ColorStateList.valueOf(onSurface)
        listOf(
            binding.btnAboutBack,
            binding.btnAboutGithub,
            binding.btnAboutBmc,
            binding.btnAboutSponsors,
            binding.btnAboutPatreon,
            binding.btnAboutDiscord,
            binding.btnAboutTelegram,
            binding.btnAboutUpstream,
            binding.btnAboutSupportHub,
        ).forEach { it.applyExpPress() }
        binding.btnAboutSupportHub.setBackgroundResource(
            R.drawable.bg_button_mobile,
        )
        listOf(
            binding.btnAboutGithub,
            binding.btnAboutBmc,
            binding.btnAboutSponsors,
            binding.btnAboutPatreon,
            binding.btnAboutDiscord,
            binding.btnAboutTelegram,
            binding.btnAboutUpstream,
        ).forEach { row ->
            row.setBackgroundResource(R.drawable.bg_outlined_chip)
            (row.getChildAt(0) as? android.widget.ImageView)?.imageTintList =
                android.content.res.ColorStateList.valueOf(primary)
        }
        listOf(
            binding.btnAboutSupportHub,
            binding.llAboutSupporters,
            binding.btnAboutGithub,
            binding.btnAboutBmc,
            binding.btnAboutSponsors,
            binding.btnAboutPatreon,
            binding.btnAboutDiscord,
            binding.btnAboutTelegram,
            binding.btnAboutUpstream,
        ).forEachIndexed { index, row ->
            row.postDelayed({ ExpMotion.popIn(row) }, 40L * index)
        }

        binding.btnAboutBack.setOnClickListener {
            ExpMotion.hapticTap(it)
            findNavController().navigateUp()
        }

        binding.btnAboutGithub.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.open(requireContext(), SupportUrls.GITHUB_REPOSITORY_URL)
        }
        binding.btnAboutBmc.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openProvider(requireContext(), SupportProvider.BUY_ME_A_COFFEE)
        }
        binding.btnAboutSponsors.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openProvider(requireContext(), SupportProvider.GITHUB_SPONSORS)
        }
        binding.btnAboutPatreon.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openProvider(requireContext(), SupportProvider.PATREON)
        }
        binding.btnAboutDiscord.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openProvider(requireContext(), SupportProvider.DISCORD)
        }
        binding.btnAboutTelegram.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openTelegram(requireContext())
        }
        binding.btnAboutUpstream.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.open(requireContext(), SupportUrls.UPSTREAM_REPOSITORY_URL)
        }
        binding.btnAboutSupportHub.setOnClickListener {
            ExpMotion.hapticTap(it)
            runCatching { findNavController().navigate(com.betterstreamflix.app.R.id.support) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
