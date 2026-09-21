package com.dskja.betterstreamflix.ui

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.view.isVisible
import com.dskja.betterstreamflix.BuildConfig
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.DialogUpdateAppMobileBinding
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.GitHub

class UpdateAppMobileDialog(
    context: Context,
    newReleases: List<GitHub.Release>,
) : Dialog(context) {

    private val binding = DialogUpdateAppMobileBinding.bind(
        LayoutInflater.from(context).inflate(
            ExperimentalMobileDesign.layout(
                R.layout.dialog_update_app_mobile,
                R.layout.dialog_update_app_mobile_exp,
            ),
            null,
        )
    )

    var isLoading: Boolean
        get() = binding.pbUpdateIsLoading.isVisible
        set(value) {
            if (ExperimentalMobileDesign.enabled()) {
                if (value) {
                    ExpMotion.fadeInAndShow(binding.pbUpdateIsLoading)
                } else if (binding.pbUpdateIsLoading.isVisible) {
                    ExpMotion.fadeOutAndHide(binding.pbUpdateIsLoading)
                } else {
                    binding.pbUpdateIsLoading.visibility = View.GONE
                }
            } else {
                binding.pbUpdateIsLoading.visibility = when {
                    value -> View.VISIBLE
                    else -> View.GONE
                }
            }
        }

    init {
        setContentView(binding.root)

        binding.tvUpdateCurrentVersion.text = BuildConfig.VERSION_NAME

        binding.tvUpdateNewVersion.text = newReleases.first().tagName.substringAfter("v")

        binding.tvUpdateReleaseNotes.text = newReleases.map {
            it.body?.replace(
                Regex("^- ([a-z0-9]+: )?(.*?)(#\\d+ )?\$", RegexOption.MULTILINE),
                "- $2"
            )
        }.joinToString("\n")

        if (ExperimentalMobileDesign.enabled()) {
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            binding.root.setBackgroundResource(ExperimentalMobileDesign.dialogBackground())
            ExpMotion.enterScreen(binding.root)
            ExperimentalMobileDesign.applyReducedGlass(binding.root)
            binding.root.findViewById<View>(R.id.sv_release_notes)
                ?.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
            ExpMotion.revealHeader(
                binding.tvUpdateTitle,
                binding.root.findViewById(R.id.v_update_title_rule),
                binding.tvUpdateMessage,
                binding.root.findViewById(R.id.tv_update_current_version_label),
                binding.tvUpdateCurrentVersion,
                binding.root.findViewById(R.id.tv_update_new_version_label),
                binding.tvUpdateNewVersion,
                binding.root.findViewById(R.id.tv_update_release_notes_label),
            )
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_update_title_rule))
            binding.btnUpdate.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
            binding.btnUpdateCancel.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
            binding.btnUpdate.applyExpPress()
            binding.btnUpdateCancel.applyExpPress()
            listOf(
                binding.root.findViewById(R.id.iv_update_arrow),
                binding.root.findViewById(R.id.tv_update_release_notes_label),
                binding.root.findViewById(R.id.sv_release_notes),
                binding.btnUpdate,
                binding.btnUpdateCancel,
            ).forEachIndexed { index, view ->
                view ?: return@forEachIndexed
                view.postDelayed({ ExpMotion.popIn(view) }, 32L * index)
            }
        }

        binding.btnUpdateCancel.setOnClickListener {
            ExpMotion.hapticTap(it)
            hide()
        }


        window?.setLayout(
            context.resources.displayMetrics.widthPixels,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }


    fun setOnUpdateClickListener(listener: (view: View) -> Unit) {
        binding.btnUpdate.setOnClickListener {
            ExpMotion.hapticTap(it)
            listener(it)
        }
    }
}