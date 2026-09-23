package com.dskja.betterstreamflix.adapters.viewholders

import android.content.Intent
import android.graphics.drawable.PictureDrawable
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.databinding.ItemProviderMobileBinding
import com.dskja.betterstreamflix.databinding.ItemProviderTvBinding
import com.dskja.betterstreamflix.models.Provider
import com.dskja.betterstreamflix.providers.ProviderHealth
import com.dskja.betterstreamflix.providers.ProviderSmoke
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.toActivity
import java.util.Locale

class ProviderViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    init {
        if (ExperimentalMobileDesign.enabled()) {
            itemView.applyExpPress()
        }
    }

    private lateinit var provider: Provider

    fun bind(provider: Provider) {
        this.provider = provider

        when (_binding) {
            is ItemProviderMobileBinding -> displayMobileItem(_binding)
            is ItemProviderTvBinding -> displayTvItem(_binding)
        }
    }

    private fun displayMobileItem(binding: ItemProviderMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                selectProviderWithConfirm()
            }
            setOnLongClickListener {
                ExpMotion.hapticTap(it)
                toggleFavorite(provider)
                val fav = binding.ivProviderFavorite
                val wasVisible = fav.visibility == View.VISIBLE
                fav.visibility = if (provider.isFavorite) View.VISIBLE else View.GONE
                if (ExperimentalMobileDesign.enabled() && provider.isFavorite && !wasVisible) {
                    ExpMotion.popIn(fav)
                }
                true
            }
        }
        
        val favWasVisible = binding.ivProviderFavorite.visibility == View.VISIBLE
        binding.ivProviderFavorite.visibility =
            if (provider.isFavorite) View.VISIBLE else View.GONE
        if (ExperimentalMobileDesign.enabled()) {
            if (provider.isFavorite) {
                binding.ivProviderFavorite.setBackgroundResource(
                    ExperimentalMobileDesign.iconChipBackground(),
                )
                if (!favWasVisible) ExpMotion.popIn(binding.ivProviderFavorite)
            } else {
                binding.ivProviderFavorite.background = null
            }
        }

        binding.root.findViewById<ImageView>(R.id.iv_provider_selected)?.let { selected ->
            val isCurrent = UserPreferences.currentProvider?.name == provider.name
            val wasVisible = selected.visibility == View.VISIBLE
            selected.visibility = if (isCurrent) View.VISIBLE else View.GONE
            if (ExperimentalMobileDesign.enabled() && isCurrent) {
                selected.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
                if (!wasVisible) ExpMotion.popIn(selected)
            } else if (!isCurrent) {
                selected.background = null
            }
        }

        loadProviderLogo(binding.ivProviderLogo)

        binding.tvProviderName.text = provider.name

        val lang = Locale.forLanguageTag(provider.language)
            .let { it.getDisplayLanguage(it) }
            .replaceFirstChar { it.titlecase() }
        if (ExperimentalMobileDesign.enabled()) {
            binding.root.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
            binding.tvProviderLanguage.setBackgroundResource(
                ExperimentalMobileDesign.metaPillBackground(),
            )
            binding.tvProviderLanguage.text = lang
            bindProviderHealthPill(binding.root, lang)
            if (binding.root.getTag(R.id.exp_enter_animated_tag) != true) {
                binding.root.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.popIn(binding.root)
                ExpMotion.revealHeader(binding.tvProviderName, binding.tvProviderLanguage)
            }
        } else {
            binding.tvProviderLanguage.text = providerHealthLabel(lang, provider.name)
        }
    }

    private fun bindProviderHealthPill(root: View, language: String) {
        val pill = root.findViewById<android.widget.TextView>(R.id.tv_provider_health_pill) ?: return
        val health = when {
            ProviderHealth.isQuarantinedName(provider.name) ->
                context.getString(R.string.provider_health_quarantined)
            ProviderSmoke.isHomeCircuitOpen(provider.name) ->
                context.getString(R.string.provider_health_unstable)
            ProviderSmoke.failureCount(provider.name) >= 2 ->
                context.getString(R.string.provider_health_flaky)
            else -> null
        }
        if (health == null) {
            pill.visibility = View.GONE
            return
        }
        val wasVisible = pill.visibility == View.VISIBLE
        pill.text = health
        val colorAttr = when {
            ProviderHealth.isQuarantinedName(provider.name) ->
                androidx.appcompat.R.attr.colorError
            ProviderSmoke.isHomeCircuitOpen(provider.name) ->
                com.google.android.material.R.attr.colorTertiary
            else ->
                com.google.android.material.R.attr.colorOnSecondaryContainer
        }
        pill.setTextColor(
            com.google.android.material.color.MaterialColors.getColor(pill, colorAttr),
        )
        if (ExperimentalMobileDesign.enabled()) {
            pill.setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
        }
        pill.visibility = View.VISIBLE
        if (!wasVisible) ExpMotion.popIn(pill)
    }

    private fun displayTvItem(binding: ItemProviderTvBinding) {
        binding.root.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                selectProviderWithConfirm()
            }
            setOnLongClickListener {
                ExpMotion.hapticTap(it)
                toggleFavorite(provider)
                val fav = binding.ivProviderFavorite
                val wasVisible = fav.visibility == View.VISIBLE
                fav.visibility = if (provider.isFavorite) View.VISIBLE else View.GONE
                true
            }
        }

        val favWasVisible = binding.ivProviderFavorite.visibility == View.VISIBLE
        binding.ivProviderFavorite.visibility =
            if (provider.isFavorite) View.VISIBLE else View.GONE

        loadProviderLogo(binding.ivProviderLogo)

        binding.tvProviderName.text = provider.name

        val lang = Locale.forLanguageTag(provider.language)
            .let { it.getDisplayLanguage(it) }
            .replaceFirstChar { it.titlecase() }
        binding.tvProviderLanguage.text = providerHealthLabel(lang, provider.name)
    }

    private fun providerHealthLabel(language: String, providerName: String): String {
        val health = when {
            ProviderHealth.isQuarantinedName(providerName) ->
                context.getString(R.string.provider_health_quarantined)
            ProviderSmoke.isHomeCircuitOpen(providerName) ->
                context.getString(R.string.provider_health_unstable)
            ProviderSmoke.failureCount(providerName) >= 2 ->
                context.getString(R.string.provider_health_flaky)
            else -> null
        }
        return if (health != null) "$language · $health" else language
    }

    private fun selectProviderWithConfirm() {
        val name = provider.name
        val needsConfirm = ProviderHealth.isQuarantinedName(name) ||
            ProviderSmoke.isHomeCircuitOpen(name)
        if (!needsConfirm) {
            switchToProvider()
            return
        }
        val message = when {
            ProviderHealth.isQuarantinedName(name) ->
                context.getString(R.string.provider_confirm_quarantined, name)
            else ->
                context.getString(R.string.provider_confirm_unstable, name)
        }
        val builder = if (ExperimentalMobileDesign.enabled()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        } else {
            AlertDialog.Builder(context)
        }
        val glass = if (ExperimentalMobileDesign.enabled()) {
            com.dskja.betterstreamflix.utils.ExpDialogChrome.buildGlassMessage(context, message)
        } else {
            null
        }
        builder.setTitle(R.string.provider_confirm_title)
        if (glass != null) builder.setView(glass.root)
        else builder.setMessage(message)
        builder
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ExpMotion.hapticTap(itemView)
                switchToProvider()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    if (glass != null) {
                        com.dskja.betterstreamflix.utils.ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                    } else {
                        com.dskja.betterstreamflix.utils.ExpDialogChrome.polishButtons(dialog)
                    }
                }
                dialog.show()
            }
    }

    private fun switchToProvider() {
        // Setting currentProvider resets Room + notifies ViewModels to cancel
        // in-flight Home/catalog work before we tear down the activity task.
        UserPreferences.currentProvider = provider.provider
        context.toActivity()?.apply {
            startActivity(
                Intent(this, this::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
            finish()
        }
    }

    private fun loadProviderLogo(imageView: ImageView) {
        val logo = provider.logo.takeIf { it.isNotEmpty() }
        val isSvg = logo?.substringBefore("?")?.endsWith(".svg", ignoreCase = true) == true

        if (isSvg) {
            imageView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            Glide.with(context)
                .`as`(PictureDrawable::class.java)
                .load(logo)
                .error(R.drawable.ic_provider_default_logo)
                .into(imageView)
        } else {
            imageView.setLayerType(View.LAYER_TYPE_NONE, null)
            Glide.with(context)
                .load(logo ?: R.drawable.ic_provider_default_logo)
                .error(R.drawable.ic_provider_default_logo)
                .fitCenter()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(imageView)
        }
    }
    
    private fun toggleFavorite(provider: Provider) {
        provider.isFavorite = !provider.isFavorite
        val favorites = UserPreferences.favoriteProviders.toMutableSet()
        if (provider.isFavorite) {
            favorites.add(provider.name)
        } else {
            favorites.remove(provider.name)
        }
        UserPreferences.favoriteProviders = favorites
    }
}
