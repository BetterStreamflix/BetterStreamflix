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
import com.dskja.betterstreamflix.databinding.DialogSupportThanksBinding
import com.dskja.betterstreamflix.support.SupportLinkOpener
import com.dskja.betterstreamflix.support.SupportProvider
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign

class SupportThanksDialog(
    context: Context,
) : Dialog(context, R.style.SupportDialogTheme) {

    private val binding = DialogSupportThanksBinding.inflate(LayoutInflater.from(context))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCancelable(true)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            attributes = attributes?.apply {
                windowAnimations = R.style.SupportDialogAnimation
            }
        }

        if (ExperimentalMobileDesign.enabled()) {
            (binding.root.getChildAt(0) as? android.view.View)
                ?.setBackgroundResource(ExperimentalMobileDesign.dialogBackground())
            ExperimentalMobileDesign.applyReducedGlass(binding.root)
            ExpMotion.enterScreen(binding.root)
            ExpMotion.revealHeader(
                binding.ivSupportThanksHeart,
                binding.vSupportThanksAccentRule,
                binding.tvSupportThanksTitle,
                binding.tvSupportThanksBody,
            )
            ExpMotion.pulseAccentRule(binding.vSupportThanksAccentRule)
            ExpMotion.popIn(binding.ivSupportThanksHeart)
            binding.btnSupportThanksBack.setBackgroundResource(
                ExperimentalMobileDesign.primaryButtonBackground(),
            )
            binding.btnSupportThanksCommunity.setBackgroundResource(
                ExperimentalMobileDesign.chipBackground(),
            )
            binding.btnSupportThanksBack.applyExpPress()
            binding.btnSupportThanksCommunity.applyExpPress()
            listOf(binding.btnSupportThanksBack, binding.btnSupportThanksCommunity)
                .forEachIndexed { index, btn ->
                    btn.postDelayed({ ExpMotion.popIn(btn) }, 40L * index)
                }
        }

        binding.btnSupportThanksBack.setOnClickListener {
            ExpMotion.hapticTap(it)
            dismiss()
        }
        binding.btnSupportThanksCommunity.setOnClickListener {
            ExpMotion.hapticTap(it)
            SupportLinkOpener.openProvider(context, SupportProvider.DISCORD)
            dismiss()
        }
        binding.btnSupportThanksBack.requestFocus()
    }
}
