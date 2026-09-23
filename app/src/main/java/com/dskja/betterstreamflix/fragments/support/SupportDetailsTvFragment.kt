package com.dskja.betterstreamflix.fragments.support

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.dskja.betterstreamflix.databinding.FragmentSupportDetailsMobileBinding
import com.dskja.betterstreamflix.support.SupportHubBinder
import com.dskja.betterstreamflix.support.SupportLinkOpener
import com.dskja.betterstreamflix.support.SupportProvider
import com.dskja.betterstreamflix.utils.ExpMotion

/**
 * TV Support Details — same content/layout as mobile, hosted from Leanback nav.
 * Kept as a sibling (not subclass) because [SupportDetailsMobileFragment] is final.
 */
class SupportDetailsTvFragment : Fragment() {

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
        binding.btnSupportDetailsCta.post { binding.btnSupportDetailsCta.requestFocus() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
