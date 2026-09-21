package com.dskja.betterstreamflix.ui

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.view.isVisible
import com.dskja.betterstreamflix.BuildConfig
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.DialogUpdateAppTvBinding
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.GitHub

class UpdateAppTvDialog(
    context: Context,
    newReleases: List<GitHub.Release>,
) : Dialog(context) {

    private val binding = DialogUpdateAppTvBinding.inflate(LayoutInflater.from(context))

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

        binding.btnUpdateCancel.setOnClickListener {
            ExpMotion.hapticTap(it)
            hide()
        }

        binding.btnUpdate.requestFocus()

        if (ExperimentalMobileDesign.enabled()) {
            binding.root.setBackgroundResource(ExperimentalMobileDesign.dialogBackground())
            ExperimentalMobileDesign.applyReducedGlass(binding.root)
            ExpMotion.enterScreen(binding.root)
            ExpMotion.revealHeader(
                binding.tvUpdateTitle,
                binding.root.findViewById(R.id.v_update_tv_title_rule),
                binding.tvUpdateMessage,
                binding.tvUpdateCurrentVersionLabel,
                binding.tvUpdateCurrentVersion,
                binding.tvUpdateNewVersionLabel,
                binding.tvUpdateNewVersion,
            )
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_update_tv_title_rule))
            binding.btnUpdate.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
            binding.btnUpdateCancel.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
            binding.btnUpdate.applyExpPress()
            binding.btnUpdateCancel.applyExpPress()
            listOf(
                binding.root.findViewById(R.id.iv_update_arrow),
                binding.root.findViewById(R.id.sv_release_notes),
                binding.btnUpdate,
                binding.btnUpdateCancel,
            ).forEachIndexed { index, view ->
                view?.postDelayed({ ExpMotion.popIn(view) }, 40L * index)
            }
        }

        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.55).toInt(),
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
