package com.betterstreamflix.app.fragments.support

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.betterstreamflix.app.databinding.FragmentSupportDetailsMobileBinding
import com.betterstreamflix.app.support.SupportHubBinder
import com.betterstreamflix.app.support.SupportLinkOpener
import com.betterstreamflix.app.support.SupportProvider
import com.betterstreamflix.app.utils.ExpMotion

open class SupportDetailsMobileFragment : Fragment() {

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
