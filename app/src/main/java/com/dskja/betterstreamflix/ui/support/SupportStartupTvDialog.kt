package com.dskja.betterstreamflix.ui.support

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Window
import android.view.WindowManager
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.DialogSupportStartupTvBinding
import com.dskja.betterstreamflix.support.SupportLinkOpener
import com.dskja.betterstreamflix.support.SupportProvider
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.UserPreferences

class SupportStartupTvDialog(
    context: Context,
    private val onOpenSupportHub: (() -> Unit)? = null,
) : Dialog(context, R.style.SupportDialogTheme) {

    private val binding = DialogSupportStartupTvBinding.inflate(LayoutInflater.from(context))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCancelable(true)
        setCanceledOnTouchOutside(true)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            attributes = attributes?.apply {
                windowAnimations = R.style.SupportDialogAnimation
            }
        }

        binding.cbSupportStartupNever.isChecked = UserPreferences.neverShowSupportOnStart

        if (ExperimentalMobileDesign.enabled()) {
            (binding.root.getChildAt(0) as? android.view.View)
                ?.setBackgroundResource(ExperimentalMobileDesign.dialogBackground())
            ExperimentalMobileDesign.applyReducedGlass(binding.root)
            ExpMotion.enterScreen(binding.root)
            ExpMotion.revealHeader(
                binding.tvSupportStartupTitle,
                binding.vSupportStartupAccentRule,
                binding.tvSupportStartupBody,
            )
            ExpMotion.pulseAccentRule(binding.vSupportStartupAccentRule)
            binding.btnSupportStartupClose.setBackgroundResource(
                ExperimentalMobileDesign.iconChipBackground(),
            )
            binding.btnSupportStartupClose.alpha = 1f
            binding.btnSupportStartupPrimary.setBackgroundResource(
                ExperimentalMobileDesign.primaryButtonBackground(),
            )
            listOf(
                binding.btnSupportStartupSponsors,
                binding.btnSupportStartupBmc,
                binding.btnSupportStartupCommunity,
                binding.btnSupportStartupRepo,
            ).forEach { btn ->
                btn.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
            }
            val ctas = listOf(
                binding.btnSupportStartupClose,
                binding.btnSupportStartupPrimary,
                binding.btnSupportStartupSponsors,
                binding.btnSupportStartupBmc,
                binding.btnSupportStartupCommunity,
                binding.btnSupportStartupRepo,
            )
            ctas.forEach { it.applyExpPress() }
            binding.cbSupportStartupNever.applyExpPress()
            (ctas + binding.cbSupportStartupNever).forEachIndexed { index, btn ->
                btn.postDelayed({ ExpMotion.popIn(btn) }, 36L * index)
            }
        }

        binding.btnSupportStartupClose.setOnClickListener {
            ExpMotion.hapticTap(it)
            persistNeverAgainIfChecked()
            dismiss()
        }
        binding.btnSupportStartupPrimary.setOnClickListener {
            ExpMotion.hapticTap(it)
            persistNeverAgainIfChecked()
            dismiss()
            onOpenSupportHub?.invoke()
        }
        binding.btnSupportStartupSponsors.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openProvider(context, SupportProvider.GITHUB_SPONSORS)
        }
        binding.btnSupportStartupBmc.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openProvider(context, SupportProvider.BUY_ME_A_COFFEE)
        }
        binding.btnSupportStartupCommunity.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openProvider(context, SupportProvider.DISCORD)
        }
        binding.btnSupportStartupRepo.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openProvider(context, SupportProvider.GITHUB_REPOSITORY)
        }

        binding.btnSupportStartupPrimary.requestFocus()

        setOnDismissListener { persistNeverAgainIfChecked() }
    }

    private fun persistNeverAgainIfChecked() {
        if (binding.cbSupportStartupNever.isChecked) {
            UserPreferences.neverShowSupportOnStart = true
        }
    }
}
