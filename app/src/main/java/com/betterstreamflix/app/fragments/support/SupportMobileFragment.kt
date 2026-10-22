package com.betterstreamflix.app.fragments.support

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.betterstreamflix.app.R
import com.betterstreamflix.app.databinding.FragmentSupportMobileBinding
import com.betterstreamflix.app.support.SupportHubBinder
import com.betterstreamflix.app.support.SupportLinkOpener
import com.betterstreamflix.app.support.SupportPromptPolicy
import com.betterstreamflix.app.support.SupportProvider
import com.betterstreamflix.app.support.SupportUiBinder
import com.betterstreamflix.app.ui.support.SupportThanksDialog
import com.betterstreamflix.app.utils.ExpMotion

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
