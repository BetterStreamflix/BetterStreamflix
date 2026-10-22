package com.dskja.betterstreamflix.adapters.viewholders

import android.content.Intent
import android.graphics.drawable.PictureDrawable
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.fragments.settings.SettingsDeepLink
import com.dskja.betterstreamflix.providers.AniWorldProvider
import com.dskja.betterstreamflix.providers.GuardaFlixProvider
import com.dskja.betterstreamflix.providers.ProviderPassport
import com.dskja.betterstreamflix.providers.SerienStreamProvider
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.databinding.ItemProviderMobileBinding
import com.dskja.betterstreamflix.databinding.ItemProviderTvBinding
import com.dskja.betterstreamflix.models.Provider
import com.dskja.betterstreamflix.providers.ProviderHealth
import com.dskja.betterstreamflix.providers.ProviderSmoke
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.toActivity
import kotlinx.coroutines.launch
import java.util.Locale

class ProviderViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    init {
        itemView.applyExpPress()
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
                if (provider.isFavorite && !wasVisible) {
                    ExpMotion.popIn(fav)
                }
                true
            }
        }
        
        val favWasVisible = binding.ivProviderFavorite.visibility == View.VISIBLE
        binding.ivProviderFavorite.visibility =
            if (provider.isFavorite) View.VISIBLE else View.GONE
        if (provider.isFavorite) {
            binding.ivProviderFavorite.setBackgroundResource(
                R.drawable.bg_detail_icon_chip,
            )
            if (!favWasVisible) ExpMotion.popIn(binding.ivProviderFavorite)
        } else {
            binding.ivProviderFavorite.background = null
        }

        binding.root.findViewById<ImageView>(R.id.iv_provider_selected)?.let { selected ->
            val isCurrent = UserPreferences.currentProvider?.name == provider.name
            val wasVisible = selected.visibility == View.VISIBLE
            selected.visibility = if (isCurrent) View.VISIBLE else View.GONE
            if (isCurrent) {
                selected.setBackgroundResource(R.drawable.bg_detail_icon_chip)
                if (!wasVisible) ExpMotion.popIn(selected)
            } else if (!isCurrent) {
                selected.background = null
            }
        }

        bindLoginGear(binding.root.findViewById(R.id.iv_provider_login_gear), tvNonFocusable = false)

        loadProviderLogo(binding.ivProviderLogo)

        binding.tvProviderName.text = provider.name

        val lang = Locale.forLanguageTag(provider.language)
            .let { it.getDisplayLanguage(it) }
            .replaceFirstChar { it.titlecase() }
        // OEM LayerDrawable/GradientDrawable draw crashes (BETTERSTREAMFLIX-3J).
        setBackgroundResourceSafe(
            binding.root,
            R.drawable.bg_cinema_glass_card,
        )
        setBackgroundResourceSafe(
            binding.tvProviderLanguage,
            R.drawable.bg_meta_pill,
        )
        binding.tvProviderLanguage.text = lang
        bindProviderHealthPill(binding.root, lang)
        bindProviderPassportPill(binding.root)
        if (binding.root.getTag(R.id.exp_enter_animated_tag) != true) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.popIn(binding.root)
            ExpMotion.revealHeader(binding.tvProviderName, binding.tvProviderLanguage)
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
            com.google.android.material.color.MaterialColors.getColor(
                pill,
                colorAttr,
                0xFFE8E8E8.toInt(),
            ),
        )
        pill.setBackgroundResource(R.drawable.bg_meta_pill)
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
                showTvProviderActions(binding)
                true
            }
        }

        binding.ivProviderFavorite.visibility =
            if (provider.isFavorite) View.VISIBLE else View.GONE

        binding.root.findViewById<ImageView>(R.id.iv_provider_selected)?.let { selected ->
            val isCurrent = UserPreferences.currentProvider?.name == provider.name
            selected.visibility = if (isCurrent) View.VISIBLE else View.GONE
        }

        bindLoginGear(binding.root.findViewById(R.id.iv_provider_login_gear), tvNonFocusable = true)

        loadProviderLogo(binding.ivProviderLogo)

        binding.tvProviderName.text = provider.name

        val lang = Locale.forLanguageTag(provider.language)
            .let { it.getDisplayLanguage(it) }
            .replaceFirstChar { it.titlecase() }
        binding.tvProviderLanguage.text = providerMetaLabel(lang, provider.name)
        // TMDb providers get the Cinema TMDb accent (TV-INK-09).
        if (provider.name.contains("TMDb", ignoreCase = true)) {
            binding.tvProviderLanguage.setTextColor(
                ContextCompat.getColor(context, R.color.cinema_tmdb),
            )
        } else {
            binding.tvProviderLanguage.setTextColor(
                ContextCompat.getColor(context, R.color.cinema_text_muted),
            )
        }
    }

    /** Long-press menu: favorite, domain update (independent of quarantine), login. */
    private fun showTvProviderActions(binding: ItemProviderTvBinding) {
        val supportsLogin = supportsProviderLogin(provider.provider)
        val offerDomain = ProviderHealth.supportsDomainUpdate(provider.provider)
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        labels += if (provider.isFavorite) {
            context.getString(R.string.option_show_unfavorite)
        } else {
            context.getString(R.string.option_show_favorite)
        }
        actions += {
            toggleFavorite(provider)
            binding.ivProviderFavorite.visibility =
                if (provider.isFavorite) View.VISIBLE else View.GONE
        }
        if (offerDomain) {
            labels += context.getString(R.string.provider_update_domain)
            actions += { updateProviderDomain() }
        }
        if (supportsLogin) {
            labels += context.getString(R.string.provider_login_gear_cd)
            actions += { openProviderLoginSettings() }
        }
        if (actions.size == 1) {
            actions.first().invoke()
            return
        }
        AlertDialog.Builder(com.dskja.betterstreamflix.utils.ExpDialogChrome.themedContext(context))
            .setTitle(provider.name)
            .setItems(labels.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    com.dskja.betterstreamflix.utils.ExpDialogChrome.polishShown(dialog)
                    dialog.listView?.apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()
                    }
                }
                dialog.show()
            }
    }

    private fun providerMetaLabel(language: String, providerName: String): String {
        val health = when {
            ProviderHealth.isQuarantinedName(providerName) ->
                context.getString(R.string.provider_health_quarantined)
            ProviderSmoke.isHomeCircuitOpen(providerName) ->
                context.getString(R.string.provider_health_unstable)
            ProviderSmoke.failureCount(providerName) >= 2 ->
                context.getString(R.string.provider_health_flaky)
            else -> null
        }
        val passport = passportStatusLabel(provider.provider)
        return listOfNotNull(language, health, passport).joinToString(" · ")
    }

    private fun passportStatusLabel(backend: com.dskja.betterstreamflix.providers.Provider): String? {
        if (!ProviderPassport.supportsPassport(backend)) return null
        val status = ProviderPassport.statusForProvider(backend) ?: return null
        return when (status) {
            ProviderPassport.Status.SESSION_FRESH ->
                context.getString(R.string.provider_passport_status_session_fresh)
            ProviderPassport.Status.CAPTCHA_DUE ->
                context.getString(R.string.provider_passport_status_captcha_due)
            ProviderPassport.Status.LOGIN_DUE ->
                context.getString(R.string.provider_passport_status_login_due)
        }
    }

    private fun bindProviderPassportPill(root: View) {
        val pill = root.findViewById<android.widget.TextView>(R.id.tv_provider_passport_pill)
            ?: return
        val label = passportStatusLabel(provider.provider)
        if (label == null) {
            pill.visibility = View.GONE
            return
        }
        val status = ProviderPassport.statusForProvider(provider.provider)
        val wasVisible = pill.visibility == View.VISIBLE
        pill.text = label
        val colorAttr = when (status) {
            ProviderPassport.Status.CAPTCHA_DUE ->
                androidx.appcompat.R.attr.colorError
            ProviderPassport.Status.LOGIN_DUE ->
                com.google.android.material.R.attr.colorTertiary
            ProviderPassport.Status.SESSION_FRESH,
            null,
            -> com.google.android.material.R.attr.colorOnSecondaryContainer
        }
        pill.setTextColor(
            com.google.android.material.color.MaterialColors.getColor(
                pill,
                colorAttr,
                0xFFE8E8E8.toInt(),
            ),
        )
        pill.setBackgroundResource(R.drawable.bg_meta_pill)
        pill.visibility = View.VISIBLE
        if (!wasVisible) ExpMotion.popIn(pill)
    }

    private fun supportsProviderLogin(
        backend: com.dskja.betterstreamflix.providers.Provider,
    ): Boolean = backend is SerienStreamProvider ||
        backend is AniWorldProvider ||
        backend is GuardaFlixProvider

    private fun selectProviderWithConfirm() {
        val name = provider.name
        val needsConfirm = ProviderHealth.isQuarantinedName(name) ||
            ProviderSmoke.isHomeCircuitOpen(name)
        val offerDomainUpdate = ProviderHealth.supportsDomainUpdate(provider.provider)
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
        val builder = androidx.appcompat.app.AlertDialog.Builder(context)
        val glass = com.dskja.betterstreamflix.utils.ExpDialogChrome.buildGlassMessage(context, message)
        builder.setTitle(R.string.provider_confirm_title)
        builder.setView(glass.root)
        builder
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ExpMotion.hapticTap(itemView)
                switchToProvider()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (offerDomainUpdate) {
            builder.setNeutralButton(R.string.provider_update_domain) { _, _ ->
                ExpMotion.hapticTap(itemView)
                updateProviderDomain()
            }
        }
        builder
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

    private fun updateProviderDomain() {
        val activity = context.toActivity() ?: return
        activity.lifecycleScope.launch {
            val outcome = com.dskja.betterstreamflix.providers.ProviderDomainUpdater
                .update(provider.provider)
            val msg = when (outcome) {
                is com.dskja.betterstreamflix.providers.ProviderDomainUpdater.Outcome.Updated ->
                    context.getString(
                        R.string.provider_update_domain_updated,
                        outcome.newUrl,
                    )
                com.dskja.betterstreamflix.providers.ProviderDomainUpdater.Outcome.Unchanged ->
                    context.getString(R.string.provider_update_domain_unchanged)
                is com.dskja.betterstreamflix.providers.ProviderDomainUpdater.Outcome.Failed ->
                    context.getString(
                        R.string.provider_update_domain_failed,
                        outcome.message,
                    )
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun switchToProvider() {
        // Persist the name only — do not reset Room or notify ViewModels while
        // tearing down the task (that race crashed cold starts until clear-data).
        // The next Main*Activity onCreate consumes the pending switch and resets.
        UserPreferences.prepareProviderSwitch(provider.provider)
        context.toActivity()?.apply {
            startActivity(
                Intent(this, this::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
            finish()
        }
    }

    private fun bindLoginGear(gear: ImageView?, tvNonFocusable: Boolean) {
        if (gear == null) return
        val supportsLogin = supportsProviderLogin(provider.provider)
        if (!supportsLogin) {
            gear.visibility = View.GONE
            gear.setOnClickListener(null)
            return
        }
        val wasVisible = gear.visibility == View.VISIBLE
        gear.visibility = View.VISIBLE
        if (tvNonFocusable) {
            // Nested focusable gear traps DPAD on Leanback — long-press opens login;
            // keep click for touch remotes / mouse.
            gear.isFocusable = false
            gear.isFocusableInTouchMode = false
            gear.isClickable = true
            gear.setOnClickListener { view ->
                ExpMotion.hapticTap(view)
                openProviderLoginSettings()
            }
            return
        }
        gear.isFocusable = true
        gear.isClickable = true
        gear.setBackgroundResource(R.drawable.bg_detail_icon_chip)
        if (!wasVisible) ExpMotion.popIn(gear)
        with(com.dskja.betterstreamflix.utils.ExpPressEffects) { gear.applyExpPress() }
        gear.setOnClickListener { view ->
            ExpMotion.hapticTap(view)
            openProviderLoginSettings()
        }
    }

    private fun openProviderLoginSettings() {
        when (provider.provider) {
            is SerienStreamProvider -> SettingsDeepLink.openSerienStreamAuth()
            is AniWorldProvider -> SettingsDeepLink.openAniWorldAuth()
            is GuardaFlixProvider -> SettingsDeepLink.openGuardaFlixAuth()
            else -> return
        }
        runCatching {
            itemView.findNavController().navigate(R.id.settings)
        }.onFailure {
            if (provider.provider is GuardaFlixProvider) {
                SettingsDeepLink.openGuardaFlixAuth()
                android.widget.Toast.makeText(
                    context,
                    R.string.settings_category_guardaflix,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                return@onFailure
            }
            // Providers screen may sit outside a nav host with settings; open
            // session login WebView directly as a fallback.
            val source = when (provider.provider) {
                is SerienStreamProvider ->
                    com.dskja.betterstreamflix.activities.tools.ProviderWatchlistScrapeActivity.SOURCE_SERIENSTREAM
                is AniWorldProvider ->
                    com.dskja.betterstreamflix.activities.tools.ProviderWatchlistScrapeActivity.SOURCE_ANIWORLD
                else -> return
            }
            val intent = Intent(
                context,
                com.dskja.betterstreamflix.activities.tools.ProviderWatchlistScrapeActivity::class.java,
            )
                .putExtra(
                    com.dskja.betterstreamflix.activities.tools.ProviderWatchlistScrapeActivity.EXTRA_SOURCE,
                    source,
                )
                .putExtra(
                    com.dskja.betterstreamflix.activities.tools.ProviderWatchlistScrapeActivity.EXTRA_SAVE_SESSION_ONLY,
                    true,
                )
            // Soft-fail on TV / Fire builds where the activity may be unavailable
            // (BETTERSTREAMFLIX-2B ActivityNotFoundException).
            if (intent.resolveActivity(context.packageManager) == null) {
                android.widget.Toast.makeText(
                    context,
                    R.string.loading_error_retry,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                return@onFailure
            }
            runCatching { context.startActivity(intent) }
        }
    }

    private fun loadProviderLogo(imageView: ImageView) {
        val logo = provider.logo.takeIf { it.isNotEmpty() }
        val isSvg = logo?.substringBefore("?")?.endsWith(".svg", ignoreCase = true) == true

        if (isSvg) {
            // SOFTWARE layers on every TV provider tile force CPU raster during
            // grid measure/draw and contribute to Providers ANRs. Keep hardware
            // on Leanback; mobile may still need software for some SVG paths.
            val leanback = com.dskja.betterstreamflix.utils.DeviceCapabilities
                .isLeanbackDevice(context)
            imageView.setLayerType(
                if (leanback) View.LAYER_TYPE_NONE else View.LAYER_TYPE_SOFTWARE,
                null,
            )
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

    /**
     * Some OEM Android 10 forks throw from GradientDrawable.draw when a
     * LayerDrawable/gradient ConstantState is odd (BETTERSTREAMFLIX-3J). Prefer
     * the themed drawable, but never let a bad resource crash the Providers grid.
     */
    private fun setBackgroundResourceSafe(view: View, resId: Int) {
        runCatching { view.setBackgroundResource(resId) }
            .onFailure {
                view.setBackgroundColor(
                    ContextCompat.getColor(context, R.color.exp_nav_glass),
                )
            }
    }
}
