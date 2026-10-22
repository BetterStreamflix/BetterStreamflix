package com.betterstreamflix.app.ui

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.view.isVisible
import com.betterstreamflix.app.BuildConfig
import com.betterstreamflix.app.R
import com.betterstreamflix.app.databinding.DialogUpdateAppMobileBinding
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.utils.ExpPressEffects.applyExpPress
import com.betterstreamflix.app.utils.GitHub

class UpdateAppMobileDialog(
    context: Context,
    newReleases: List<GitHub.Release>,
) : Dialog(context) {

    private val binding = DialogUpdateAppMobileBinding.bind(
        LayoutInflater.from(context).inflate(
            R.layout.dialog_update_app_mobile,
            null,
        )
    )

    var isLoading: Boolean
        get() = binding.pbUpdateIsLoading.isVisible
        set(value) {
            if (value) {
                ExpMotion.fadeInAndShow(binding.pbUpdateIsLoading)
            } else if (binding.pbUpdateIsLoading.isVisible) {
                ExpMotion.fadeOutAndHide(binding.pbUpdateIsLoading)
            } else {
                binding.pbUpdateIsLoading.visibility = View.GONE
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

        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        binding.root.setBackgroundResource(R.drawable.bg_profile_dialog)
        ExpMotion.enterScreen(binding.root)
        binding.root.findViewById<View>(R.id.sv_release_notes)
            ?.setBackgroundResource(R.drawable.bg_cinema_glass_card)
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
        binding.btnUpdate.setBackgroundResource(R.drawable.bg_button_mobile)
        binding.btnUpdateCancel.setBackgroundResource(R.drawable.bg_outlined_chip)
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
