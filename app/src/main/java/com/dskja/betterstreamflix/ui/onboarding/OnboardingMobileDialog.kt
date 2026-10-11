package com.dskja.betterstreamflix.ui.onboarding

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDialog
import androidx.fragment.app.FragmentActivity
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.DialogOnboardingMobileBinding
import com.dskja.betterstreamflix.onboarding.OnboardingApply
import com.dskja.betterstreamflix.onboarding.OnboardingWizardBinder
import com.dskja.betterstreamflix.utils.ExpMotion

class OnboardingMobileDialog(
    private val activity: FragmentActivity,
    private val onClosed: (() -> Unit)? = null,
) : AppCompatDialog(activity, R.style.SupportDialogTheme) {

    private val binding = DialogOnboardingMobileBinding.inflate(LayoutInflater.from(activity))
    private var binder: OnboardingWizardBinder? = null
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        // Not casually dismissible — Skip requires an explicit confirm.
        setCancelable(false)
        setCanceledOnTouchOutside(false)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            attributes = attributes?.apply {
                windowAnimations = R.style.SupportDialogAnimation
            }
        }

        binder = OnboardingWizardBinder(
            root = binding.root,
            isTv = false,
            onFinish = { draft, skipped ->
                if (finishing) return@OnboardingWizardBinder
                finishing = true
                ExpMotion.hapticTap(binding.root)
                OnboardingApply.finish(activity, draft, skipped)
                dismiss()
            },
        ).also { it.bind() }

        setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_UP
            ) {
                binder?.handleBack() == true
            } else {
                false
            }
        }

        setOnDismissListener { onClosed?.invoke() }
    }
}
