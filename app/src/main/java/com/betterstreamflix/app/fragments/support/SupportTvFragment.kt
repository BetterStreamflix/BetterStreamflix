package com.betterstreamflix.app.fragments.support

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.betterstreamflix.app.R
import com.betterstreamflix.app.databinding.FragmentSupportTvBinding
import com.betterstreamflix.app.support.SupportHubBinder
import com.betterstreamflix.app.support.SupportLinkOpener
import com.betterstreamflix.app.support.SupportPromptPolicy
import com.betterstreamflix.app.support.SupportProvider
import com.betterstreamflix.app.support.SupportUiBinder
import com.betterstreamflix.app.ui.support.SupportThanksDialog

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
        // Leanback: open Support Details (same content as mobile); no phone tabs.
        hero.btnSupportHeroSecondary.setOnClickListener {
            runCatching {
                findNavController().navigate(R.id.action_support_to_support_details)
            }
        }
        hero.btnSupportHeroSecondary.setText(R.string.support_hero_cta_secondary)
        SupportUiBinder.applyFocusScale(hero.btnSupportHeroPrimary)
        SupportUiBinder.applyFocusScale(hero.btnSupportHeroSecondary)

        SupportUiBinder.bindProviderCards(
            requireContext(),
            binding.llSupportProviders,
            horizontal = true,
            animate = false,
        )
        SupportHubBinder.bindSupporters(
            requireContext(),
            binding.llSupportSupporters,
            binding.tvSupportSupportersTitle,
            binding.tvSupportSupportersSubtitle,
        )
        SupportHubBinder.bindImpact(requireContext(), binding.llSupportImpact)
        SupportHubBinder.bindFaq(requireContext(), binding.llSupportFaq)

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
