package com.dskja.betterstreamflix.onboarding

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.AppLanguageManager
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shared 3-step wizard chrome for Mobile + TV dialogs (same view ids).
 */
class OnboardingWizardBinder(
    private val root: View,
    private val isTv: Boolean,
    private val onFinish: (draft: OnboardingDraft, skipped: Boolean) -> Unit,
) {
    private val draft = OnboardingDraft()
    private var step = 0

    private val tvStep: TextView = root.findViewById(R.id.tv_onboarding_step)
    private val tvTitle: TextView = root.findViewById(R.id.tv_onboarding_title)
    private val tvBody: TextView = root.findViewById(R.id.tv_onboarding_body)
    private val panelLanguage: View = root.findViewById(R.id.panel_onboarding_language)
    private val panelProviders: View = root.findViewById(R.id.panel_onboarding_providers)
    private val panelKids: View = root.findViewById(R.id.panel_onboarding_kids)
    private val languageList: LinearLayout = root.findViewById(R.id.ll_onboarding_languages)
    private val providerList: LinearLayout = root.findViewById(R.id.ll_onboarding_providers)
    private val btnKidsYes: TextView = root.findViewById(R.id.btn_onboarding_kids_yes)
    private val btnKidsNo: TextView = root.findViewById(R.id.btn_onboarding_kids_no)
    private val btnSkip: TextView = root.findViewById(R.id.btn_onboarding_skip)
    private val btnPrimary: TextView = root.findViewById(R.id.btn_onboarding_primary)
    private val tvProviderCount: TextView = root.findViewById(R.id.tv_onboarding_provider_count)

    fun bind() {
        draft.languageTag = AppLanguageManager.getSelectedLanguage(root.context)
        btnSkip.setOnClickListener {
            ExpMotion.hapticTap(it)
            confirmSkip()
        }
        btnPrimary.setOnClickListener {
            ExpMotion.hapticTap(it)
            onPrimary()
        }
        btnKidsYes.setOnClickListener {
            ExpMotion.hapticTap(it)
            draft.kidsEnabled = true
            refreshKidsSelection()
            updatePrimaryEnabled()
        }
        btnKidsNo.setOnClickListener {
            ExpMotion.hapticTap(it)
            draft.kidsEnabled = false
            refreshKidsSelection()
            updatePrimaryEnabled()
        }
        populateLanguages()
        showStep(0)
        if (isTv) {
            btnPrimary.post { btnPrimary.requestFocus() }
        }
    }

    fun handleBack(): Boolean {
        if (step > 0) {
            showStep(step - 1)
            return true
        }
        confirmSkip()
        return true
    }

    private fun onPrimary() {
        when (step) {
            0 -> showStep(1)
            1 -> {
                if (draft.favoriteNames.size != OnboardingPolicy.REQUIRED_FAVORITES) return
                showStep(2)
            }
            else -> onFinish(draft, false)
        }
    }

    private fun confirmSkip() {
        val ctx = ExpDialogChrome.themedContext(root.context)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.onboarding_skip_confirm_title)
            .setMessage(R.string.onboarding_skip_confirm_body)
            .setNegativeButton(R.string.option_cancel, null)
            .setPositiveButton(R.string.onboarding_skip) { _, _ ->
                onFinish(draft, true)
            }
            .show()
    }

    private fun showStep(index: Int) {
        step = index.coerceIn(0, 2)
        panelLanguage.visibility = if (step == 0) View.VISIBLE else View.GONE
        panelProviders.visibility = if (step == 1) View.VISIBLE else View.GONE
        panelKids.visibility = if (step == 2) View.VISIBLE else View.GONE

        root.findViewById<View>(R.id.v_onboarding_dot_1)?.isSelected = step >= 0
        root.findViewById<View>(R.id.v_onboarding_dot_2)?.isSelected = step >= 1
        root.findViewById<View>(R.id.v_onboarding_dot_3)?.isSelected = step >= 2

        when (step) {
            0 -> {
                tvStep.text = root.context.getString(R.string.onboarding_step_of, 1, 3)
                tvTitle.setText(R.string.onboarding_language_title)
                tvBody.setText(R.string.onboarding_language_body)
                btnPrimary.setText(R.string.bypass_action_continue)
            }
            1 -> {
                tvStep.text = root.context.getString(R.string.onboarding_step_of, 2, 3)
                tvTitle.setText(R.string.onboarding_providers_title)
                tvBody.setText(R.string.onboarding_providers_body)
                btnPrimary.setText(R.string.bypass_action_continue)
                populateProviders()
            }
            else -> {
                tvStep.text = root.context.getString(R.string.onboarding_step_of, 3, 3)
                tvTitle.setText(R.string.onboarding_kids_title)
                tvBody.setText(R.string.onboarding_kids_body)
                btnPrimary.setText(R.string.onboarding_finish)
                refreshKidsSelection()
            }
        }
        updatePrimaryEnabled()
        updateProviderCount()
        if (isTv) {
            val focusTarget = when (step) {
                0 -> languageList.getChildAt(0)
                1 -> providerList.getChildAt(0)
                else -> btnKidsNo
            }
            focusTarget?.post { focusTarget.requestFocus() }
        }
    }

    private fun updatePrimaryEnabled() {
        val enabled = when (step) {
            0 -> true
            1 -> draft.favoriteNames.size == OnboardingPolicy.REQUIRED_FAVORITES
            else -> true
        }
        btnPrimary.isEnabled = enabled
        btnPrimary.alpha = if (enabled) 1f else 0.45f
    }

    private fun updateProviderCount() {
        tvProviderCount.text = root.context.getString(
            R.string.onboarding_providers_count,
            draft.favoriteNames.size,
            OnboardingPolicy.REQUIRED_FAVORITES,
        )
    }

    private fun populateLanguages() {
        languageList.removeAllViews()
        val ctx = root.context
        val entries = AppLanguageManager.buildLanguageEntries(ctx)
        val values = AppLanguageManager.buildLanguageValues(ctx)
        entries.zip(values).forEach { (label, value) ->
            val tag = value.toString()
            val row = inflateChoiceRow(languageList)
            row.text = label
            row.isSelected = draft.languageTag == tag
            applyChoiceStyle(row, selected = draft.languageTag == tag)
            row.setOnClickListener {
                ExpMotion.hapticTap(it)
                draft.languageTag = tag
                // Keep favorites that still exist after language change.
                val stillValid = OnboardingCatalog.providersForLanguage(tag)
                    .map { p -> p.name }
                    .toSet()
                draft.favoriteNames.removeAll { it !in stillValid }
                for (i in 0 until languageList.childCount) {
                    val child = languageList.getChildAt(i) as TextView
                    val selected = child.getTag(R.id.onboarding_choice_tag) == tag
                    child.isSelected = selected
                    applyChoiceStyle(child, selected)
                }
                updatePrimaryEnabled()
            }
            row.setTag(R.id.onboarding_choice_tag, tag)
            languageList.addView(row)
        }
    }

    private fun populateProviders() {
        providerList.removeAllViews()
        val providers = OnboardingCatalog.providersForLanguage(draft.languageTag)
        providers.forEach { provider ->
            val row = inflateProviderRow(providerList)
            val nameView = row.findViewById<TextView>(R.id.tv_choice_name)
            val logoView = row.findViewById<android.widget.ImageView>(R.id.iv_choice_logo)
            val checkView = row.findViewById<android.widget.ImageView>(R.id.iv_choice_check)
            nameView.text = provider.name
            row.contentDescription = provider.name
            loadProviderLogo(logoView, provider.logo)
            val selected = draft.favoriteNames.contains(provider.name)
            row.isSelected = selected
            applyProviderRowStyle(row, nameView, checkView, selected)
            row.setOnClickListener {
                ExpMotion.hapticTap(it)
                if (draft.favoriteNames.contains(provider.name)) {
                    draft.favoriteNames.remove(provider.name)
                } else if (draft.favoriteNames.size < OnboardingPolicy.REQUIRED_FAVORITES) {
                    draft.favoriteNames.add(provider.name)
                }
                for (i in 0 until providerList.childCount) {
                    val child = providerList.getChildAt(i)
                    val name = child.getTag(R.id.onboarding_choice_tag) as? String
                    val isSel = name != null && draft.favoriteNames.contains(name)
                    child.isSelected = isSel
                    applyProviderRowStyle(
                        child,
                        child.findViewById(R.id.tv_choice_name),
                        child.findViewById(R.id.iv_choice_check),
                        isSel,
                    )
                }
                updateProviderCount()
                updatePrimaryEnabled()
            }
            row.setTag(R.id.onboarding_choice_tag, provider.name)
            providerList.addView(row)
        }
        updateProviderCount()
    }

    private fun refreshKidsSelection() {
        applyChoiceStyle(btnKidsYes, draft.kidsEnabled)
        applyChoiceStyle(btnKidsNo, !draft.kidsEnabled)
        btnKidsYes.isSelected = draft.kidsEnabled
        btnKidsNo.isSelected = !draft.kidsEnabled
    }

    private fun inflateProviderRow(parent: ViewGroup): View {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_onboarding_provider, parent, false)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = parent.resources.getDimensionPixelSize(R.dimen.onboarding_choice_gap)
        view.layoutParams = lp
        view.isFocusable = true
        view.isClickable = true
        return view
    }

    /** Same SVG-aware logo path as ProviderViewHolder (remote URL or default). */
    private fun loadProviderLogo(imageView: android.widget.ImageView, logo: String?) {
        val url = logo?.takeIf { it.isNotEmpty() }
        val isSvg = url?.substringBefore("?")?.endsWith(".svg", ignoreCase = true) == true
        val context = imageView.context
        if (isSvg) {
            imageView.setLayerType(
                if (isTv) View.LAYER_TYPE_NONE else View.LAYER_TYPE_SOFTWARE,
                null,
            )
            com.bumptech.glide.Glide.with(context)
                .`as`(android.graphics.drawable.PictureDrawable::class.java)
                .load(url)
                .error(R.drawable.ic_provider_default_logo)
                .into(imageView)
        } else {
            imageView.setLayerType(View.LAYER_TYPE_NONE, null)
            com.bumptech.glide.Glide.with(context)
                .load(url ?: R.drawable.ic_provider_default_logo)
                .error(R.drawable.ic_provider_default_logo)
                .fitCenter()
                .transition(
                    com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
                        .withCrossFade(),
                )
                .into(imageView)
        }
    }

    private fun applyProviderRowStyle(
        row: View,
        nameView: TextView,
        checkView: android.widget.ImageView,
        selected: Boolean,
    ) {
        row.setBackgroundResource(
            if (selected) R.drawable.bg_onboarding_choice_selected
            else R.drawable.bg_onboarding_choice,
        )
        val content = ContextCompat.getColor(
            row.context,
            if (selected) R.color.cinema_cta_ink else R.color.cinema_text,
        )
        nameView.setTextColor(content)
        checkView.visibility = if (selected) View.VISIBLE else View.GONE
        checkView.imageTintList = android.content.res.ColorStateList.valueOf(content)
    }

    private fun inflateChoiceRow(parent: ViewGroup): TextView {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_onboarding_choice, parent, false) as TextView
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = parent.resources.getDimensionPixelSize(R.dimen.onboarding_choice_gap)
        view.layoutParams = lp
        view.isFocusable = true
        view.isClickable = true
        return view
    }

    private fun applyChoiceStyle(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_onboarding_choice_selected
            else R.drawable.bg_onboarding_choice,
        )
        val content = ContextCompat.getColor(
            view.context,
            if (selected) R.color.cinema_cta_ink else R.color.cinema_text,
        )
        view.setTextColor(content)
        // Check mark at the row's trailing edge when selected.
        val check = if (selected) {
            ContextCompat.getDrawable(view.context, R.drawable.ic_featured_check)?.apply {
                setTint(content)
            }
        } else {
            null
        }
        view.setCompoundDrawablesWithIntrinsicBounds(null, null, check, null)
        view.compoundDrawablePadding =
            (12 * view.resources.displayMetrics.density).toInt()
    }
}
