package com.betterstreamflix.app.ui.support

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Window
import android.view.WindowManager
import com.betterstreamflix.app.R
import com.betterstreamflix.app.databinding.DialogSupportThanksBinding
import com.betterstreamflix.app.support.SupportLinkOpener
import com.betterstreamflix.app.support.SupportProvider
import com.betterstreamflix.app.support.SupportersCatalog
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.utils.ExpPressEffects.applyExpPress

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

        val names = SupportersCatalog.displayNames(context)
        binding.tvSupportThanksBody.text = if (names.isNotBlank()) {
            context.getString(R.string.support_thanks_body_with_supporters, names)
        } else {
            context.getString(R.string.support_thanks_body)
        }

        (binding.root.getChildAt(0) as? android.view.View)
            ?.setBackgroundResource(R.drawable.bg_profile_dialog)
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
            R.drawable.bg_button_mobile,
        )
        binding.btnSupportThanksCommunity.setBackgroundResource(
            R.drawable.bg_outlined_chip,
        )
        binding.btnSupportThanksBack.applyExpPress()
        binding.btnSupportThanksCommunity.applyExpPress()
        listOf(binding.btnSupportThanksBack, binding.btnSupportThanksCommunity)
            .forEachIndexed { index, btn ->
                btn.postDelayed({ ExpMotion.popIn(btn) }, 40L * index)
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
