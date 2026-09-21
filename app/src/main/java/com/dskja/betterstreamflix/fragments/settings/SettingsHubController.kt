package com.dskja.betterstreamflix.fragments.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceFragmentCompat
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.FragmentSettingsHubMobileBinding
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign

/**
 * Support-style cinematic hub overlay for Settings root and Integrations
 * (screen_platform) when experimental design is enabled.
 */
internal class SettingsHubController(
    private val fragment: PreferenceFragmentCompat,
    private val currentRootKey: () -> String?,
    private val onOpenPreferenceScreen: (key: String, title: String) -> Unit,
    private val onOpenSupport: () -> Unit,
    private val onOpenAbout: () -> Unit,
) {
    private var hubBinding: FragmentSettingsHubMobileBinding? = null
    private var boundMode: HubMode? = null
    private var enterAnimated = false

    private enum class HubMode { ROOT, PLATFORM }

    fun attach(root: View) {
        if (!ExperimentalMobileDesign.enabled()) {
            detach()
            return
        }
        val parent = root as? ViewGroup ?: return
        if (hubBinding == null) {
            hubBinding = FragmentSettingsHubMobileBinding.inflate(
                LayoutInflater.from(fragment.requireContext()),
                parent,
                true,
            )
            enterAnimated = false
        }
        updateVisibility()
    }

    fun updateVisibility() {
        val binding = hubBinding
        if (!ExperimentalMobileDesign.enabled()) {
            detach()
            return
        }
        if (binding == null) return

        val key = currentRootKey()
        val mode = when (key) {
            null -> HubMode.ROOT
            "screen_platform" -> HubMode.PLATFORM
            else -> null
        }
        val visible = mode != null
        binding.root.visibility = if (visible) View.VISIBLE else View.GONE
        fragment.listView?.apply {
            visibility = if (visible) View.GONE else View.VISIBLE
            setBackgroundColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorSurface, 0xFF0B0B0F.toInt(),
                ),
            )
            if (!visible) post { requestFocus() }
        }
        if (mode != null) {
            val needsBind = boundMode != mode || mode == HubMode.PLATFORM
            if (needsBind) {
                bindMode(binding, mode)
                if (boundMode != mode) enterAnimated = false
                boundMode = mode
            }
            if (!enterAnimated) {
                binding.root.setBackgroundResource(R.drawable.bg_exp_canvas_atmosphere)
                ExperimentalMobileDesign.applyReducedGlass(binding.root)
                ExpMotion.enterScreen(binding.root)
                ExpMotion.startAnimation(binding.svSettingsHub, R.anim.support_fade_slide_up)
                ExpMotion.revealHeader(
                    binding.tvSettingsHubEyebrow,
                    binding.tvSettingsHubTitle,
                    binding.vSettingsHubAccentRule,
                    binding.tvSettingsHubSubtitle,
                )
                ExpMotion.pulseAccentRule(binding.vSettingsHubAccentRule)
                if (binding.cardSettingsFeatured.visibility == View.VISIBLE) {
                    ExpMotion.popIn(binding.cardSettingsFeatured)
                }
                enterAnimated = true
            }
            binding.root.post {
                (binding.cardSettingsFeatured.takeIf { it.visibility == View.VISIBLE }
                    ?: binding.llSettingsHubApp.getChildAt(0))
                    ?.requestFocus()
            }
        } else {
            enterAnimated = false
            boundMode = null
        }
    }

    fun detach() {
        hubBinding?.root?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        hubBinding = null
        boundMode = null
        enterAnimated = false
    }

    private fun bindMode(binding: FragmentSettingsHubMobileBinding, mode: HubMode) {
        when (mode) {
            HubMode.ROOT -> {
                binding.tvSettingsHubEyebrow.setText(R.string.settings_hub_eyebrow)
                binding.tvSettingsHubTitle.setText(R.string.settings_hub_title)
                binding.tvSettingsHubSubtitle.setText(R.string.settings_hub_subtitle)
                binding.cardSettingsFeatured.visibility = View.VISIBLE
                binding.cardSettingsFeatured.setOnClickListener {
                    ExpMotion.hapticTap(it)
                    onOpenPreferenceScreen(
                        SettingsHubCategories.featuredScreenKey,
                        fragment.getString(R.string.platform_settings_title),
                    )
                }
                if (ExperimentalMobileDesign.enabled()) {
                    binding.cardSettingsFeatured.setBackgroundResource(
                        ExperimentalMobileDesign.glassCardBackground(),
                    )
                    ExperimentalMobileDesign.applyReducedGlass(binding.root)
                    with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                        binding.cardSettingsFeatured.applyExpPress()
                    }
                }
                setSectionLabel(binding, R.id.ll_settings_hub_app, R.string.settings_hub_section_app, true)
                setSectionLabel(binding, R.id.ll_settings_hub_account, R.string.settings_hub_section_account, true)
                setSectionLabel(binding, R.id.ll_settings_hub_project, R.string.settings_hub_section_project, true)
                inflateSettingsCards(binding.llSettingsHubApp, SettingsHubCategories.appCards())
                inflateSettingsCards(binding.llSettingsHubAccount, SettingsHubCategories.accountCards())
                inflateSettingsCards(binding.llSettingsHubProject, SettingsHubCategories.projectCards())
            }
            HubMode.PLATFORM -> {
                binding.tvSettingsHubEyebrow.setText(R.string.settings_hub_featured_badge)
                binding.tvSettingsHubTitle.setText(R.string.platform_settings_title)
                binding.tvSettingsHubSubtitle.text = PlatformHubCategories.hubSubtitle(fragment.requireContext())
                binding.cardSettingsFeatured.visibility = View.GONE
                setSectionLabel(binding, R.id.ll_settings_hub_app, R.string.platform_hub_section_services, true)
                setSectionLabel(binding, R.id.ll_settings_hub_account, 0, false)
                setSectionLabel(binding, R.id.ll_settings_hub_project, 0, false)
                inflatePlatformCards(binding.llSettingsHubApp)
                binding.llSettingsHubAccount.removeAllViews()
                binding.llSettingsHubProject.removeAllViews()
            }
        }
    }

    private fun setSectionLabel(
        binding: FragmentSettingsHubMobileBinding,
        sectionListId: Int,
        titleRes: Int,
        visible: Boolean,
    ) {
        val list = binding.root.findViewById<View>(sectionListId) ?: return
        val label = findPreviousTextSibling(list)
        label?.visibility = if (visible) View.VISIBLE else View.GONE
        list.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible && titleRes != 0) {
            label?.setText(titleRes)
            if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled() && label != null) {
                val key = "$sectionListId|$titleRes"
                if (label.getTag(com.dskja.betterstreamflix.R.id.exp_enter_animated_tag) != key) {
                    label.setTag(com.dskja.betterstreamflix.R.id.exp_enter_animated_tag, key)
                    com.dskja.betterstreamflix.utils.ExpMotion.revealHeader(label)
                    com.dskja.betterstreamflix.utils.ExpMotion.pulseAccentRule(label)
                }
            }
        }
    }

    private fun findPreviousTextSibling(view: View): TextView? {
        val parent = view.parent as? ViewGroup ?: return null
        val index = parent.indexOfChild(view)
        if (index <= 0) return null
        return parent.getChildAt(index - 1) as? TextView
    }

    private fun inflatePlatformCards(container: LinearLayout) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(fragment.requireContext())
        val context = fragment.requireContext()
        PlatformHubCategories.cards().forEachIndexed { index, card ->
            if (fragment.findPreference<androidx.preference.Preference>(card.screenKey) == null) {
                return@forEachIndexed
            }
            addCard(
                inflater = inflater,
                container = container,
                index = index,
                titleRes = card.titleRes,
                summaryText = PlatformHubCategories.liveSummary(context, card.screenKey),
                iconRes = card.iconRes,
            ) {
                onOpenPreferenceScreen(card.screenKey, fragment.getString(card.titleRes))
            }
        }
    }

    private fun inflateSettingsCards(container: LinearLayout, cards: List<SettingsHubCard>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(fragment.requireContext())
        cards.forEachIndexed { index, card ->
            val show = when (val target = card.target) {
                is SettingsHubTarget.PreferenceScreen ->
                    fragment.findPreference<androidx.preference.Preference>(target.key) != null
                else -> true
            }
            if (!show) return@forEachIndexed
            addCard(
                inflater = inflater,
                container = container,
                index = index,
                titleRes = card.titleRes,
                summaryText = when (card.id) {
                    "profiles" -> {
                        val active = com.dskja.betterstreamflix.profiles.ProfileManager.activeProfile()
                        val count = com.dskja.betterstreamflix.profiles.ProfileManager.profiles().size
                        val name = active?.displayName
                            ?: fragment.getString(R.string.profile_default)
                        fragment.getString(R.string.profile_hub_summary_fmt, name, count)
                    }
                    else -> fragment.getString(card.summaryRes)
                },
                iconRes = card.iconRes,
            ) {
                when (val target = card.target) {
                    is SettingsHubTarget.PreferenceScreen ->
                        onOpenPreferenceScreen(target.key, fragment.getString(card.titleRes))
                    SettingsHubTarget.Support -> onOpenSupport()
                    SettingsHubTarget.About -> onOpenAbout()
                }
            }
        }
    }

    private fun addCard(
        inflater: LayoutInflater,
        container: LinearLayout,
        index: Int,
        titleRes: Int,
        summaryText: CharSequence,
        iconRes: Int,
        onClick: () -> Unit,
    ) {
        val row = inflater.inflate(R.layout.item_settings_hub_card, container, false)
        row.findViewById<TextView>(R.id.tv_settings_hub_card_title).setText(titleRes)
        row.findViewById<TextView>(R.id.tv_settings_hub_card_summary).text = summaryText
        row.findViewById<ImageView>(R.id.iv_settings_hub_card_icon).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(
                    row, androidx.appcompat.R.attr.colorPrimary,
                ),
            )
        }
        row.setOnClickListener {
            ExpMotion.hapticTap(it)
            onClick()
        }
        if (ExperimentalMobileDesign.enabled()) {
            with(com.dskja.betterstreamflix.utils.ExpPressEffects) { row.applyExpPress() }
            row.setBackgroundResource(ExperimentalMobileDesign.optionItemBackground())
            (row.findViewById<ImageView>(R.id.iv_settings_hub_card_icon).parent as? View)
                ?.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
            (row as? ViewGroup)?.let { group ->
                val chevron = group.getChildAt(group.childCount - 1) as? ImageView
                chevron?.imageTintList = android.content.res.ColorStateList.valueOf(
                    com.google.android.material.color.MaterialColors.getColor(
                        row, androidx.appcompat.R.attr.colorPrimary,
                    ),
                )
            }
        }
        container.addView(row)
        if (ExperimentalMobileDesign.enabled()) {
            row.alpha = 0f
            row.postDelayed({ ExpMotion.popIn(row) }, 32L * index)
        }
    }
}
