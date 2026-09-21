package com.dskja.betterstreamflix.telegram

import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.BuildConfig
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.DialogTelegramJoinGateMobileBinding
import com.dskja.betterstreamflix.support.SupportLinkOpener
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * Optional community invite UI — brand hero, live Telegram + Discord rows, dismissible.
 */
class TelegramJoinGateUiBinder(
    private val binding: DialogTelegramJoinGateMobileBinding,
    private val host: Host,
) {
    interface Host {
        fun context(): Context
        fun lifecycleOwner(): LifecycleOwner?
        fun isHostAlive(): Boolean
        fun onDismissed()
    }

    private var metaJob: Job? = null
    private var finishing = false

    fun bind() {
        applyContentInsets()
        binding.tvTgGateTelegramHandle.text = host.context().getString(
            R.string.tg_gate_channel_chip,
            TelegramJoinGatePolicy.CHANNEL_HANDLE,
        )
        binding.tvTgGateDiscordHandle.text =
            host.context().getString(R.string.tg_gate_discord_handle)

        binding.btnTgGateClose.setOnClickListener { haptic(it); dismiss() }
        binding.btnTgGateContinue.setOnClickListener { haptic(it); dismiss() }

        if (BuildConfig.DEBUG) {
            binding.btnTgGateDebugSkip.isVisible = true
            binding.btnTgGateDebugSkip.setOnClickListener {
                haptic(it)
                dismiss()
            }
        }

        binding.rowTgGateTelegram.setOnClickListener { haptic(it); openTelegram() }
        binding.btnTgGateOpen.setOnClickListener { haptic(it); openTelegram() }
        binding.rowTgGateDiscord.setOnClickListener { haptic(it); openDiscord() }
        binding.btnTgGateDiscordOpen.setOnClickListener { haptic(it); openDiscord() }

        listOf(
            binding.rowTgGateTelegram,
            binding.rowTgGateDiscord,
            binding.btnTgGateOpen,
            binding.btnTgGateDiscordOpen,
            binding.btnTgGateContinue,
            binding.btnTgGateClose,
        ).forEach { it.applyExpPress() }

        playEnterMotion()
        loadLiveMetadata()
    }

    fun onResume() {
        if (!host.isHostAlive()) return
        if (TelegramJoinGatePolicy.isUnlocked()) {
            dismiss(animate = false)
        }
    }

    fun destroy() {
        metaJob?.cancel()
        metaJob = null
        binding.root.animate().cancel()
        runCatching {
            Glide.with(host.context().applicationContext)
                .clear(binding.ivTgGateTelegramIcon)
        }
        runCatching {
            Glide.with(host.context().applicationContext)
                .clear(binding.ivTgGateDiscordIcon)
        }
    }

    fun dismiss(animate: Boolean = true) {
        if (finishing) return
        finishing = true
        TelegramJoinGatePolicy.markDismissed()
        metaJob?.cancel()
        if (!animate || reduceMotion()) {
            host.onDismissed()
            return
        }
        binding.root.animate()
            .alpha(0f)
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { host.onDismissed() }
            .start()
    }

    private fun applyContentInsets() {
        val ctx = host.context()
        ViewCompat.setOnApplyWindowInsetsListener(binding.tgGateContent) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val padH = ctx.resources.getDimensionPixelSize(R.dimen.tg_gate_pad_h)
            val padV = ctx.resources.getDimensionPixelSize(R.dimen.tg_gate_pad_v)
            view.updatePadding(
                left = bars.left + padH,
                top = bars.top + padV,
                right = bars.right + padH,
                bottom = bars.bottom + padV,
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.tgGateContent)
    }

    private fun loadLiveMetadata() {
        val owner = host.lifecycleOwner() ?: return
        binding.pbTgGateTelegram.isVisible = true
        binding.pbTgGateDiscord.isVisible = true
        metaJob?.cancel()
        metaJob = owner.lifecycleScope.launch {
            val snapshot = runCatching { CommunityInviteRepository.load() }.getOrNull()
            if (!host.isHostAlive()) return@launch
            binding.pbTgGateTelegram.isVisible = false
            binding.pbTgGateDiscord.isVisible = false
            snapshot?.telegram?.let { applyTelegram(it) }
                ?: run {
                    binding.tvTgGateTelegramMembers.setText(R.string.tg_gate_members_unavailable)
                }
            snapshot?.discord?.let { applyDiscord(it) }
                ?: run {
                    binding.tvTgGateDiscordMembers.setText(R.string.tg_gate_members_unavailable)
                }
        }
    }

    private fun applyTelegram(info: CommunityInviteRepository.ChannelInfo) {
        binding.tvTgGateTelegramTitle.text = info.title
        binding.tvTgGateTelegramHandle.text = info.handleOrTag
        binding.tvTgGateTelegramMembers.text = formatMembers(
            members = info.memberCount,
            online = null,
            subscribersLabel = true,
        )
        loadAvatar(binding.ivTgGateTelegramIcon, info.iconUrl, R.drawable.ic_telegram)
    }

    private fun applyDiscord(info: CommunityInviteRepository.ChannelInfo) {
        binding.tvTgGateDiscordTitle.text = info.title
        binding.tvTgGateDiscordHandle.text = info.handleOrTag
        binding.tvTgGateDiscordMembers.text = formatMembers(
            members = info.memberCount,
            online = info.onlineCount,
            subscribersLabel = false,
        )
        loadAvatar(binding.ivTgGateDiscordIcon, info.iconUrl, R.drawable.ic_discord)
    }

    private fun formatMembers(
        members: Int?,
        online: Int?,
        subscribersLabel: Boolean,
    ): String {
        val ctx = host.context()
        if (members == null) {
            return ctx.getString(R.string.tg_gate_members_unavailable)
        }
        val formatted = NumberFormat.getIntegerInstance(Locale.getDefault()).format(members)
        return if (subscribersLabel) {
            ctx.getString(R.string.tg_gate_subscribers_count, formatted)
        } else if (online != null) {
            val onlineFmt = NumberFormat.getIntegerInstance(Locale.getDefault()).format(online)
            ctx.getString(R.string.tg_gate_discord_members_online, formatted, onlineFmt)
        } else {
            ctx.getString(R.string.tg_gate_members_count, formatted)
        }
    }

    private fun loadAvatar(
        target: android.widget.ImageView,
        url: String?,
        placeholder: Int,
    ) {
        if (url.isNullOrBlank()) {
            target.setImageResource(placeholder)
            target.setPadding(
                dp(12),
                dp(12),
                dp(12),
                dp(12),
            )
            return
        }
        target.setPadding(0, 0, 0, 0)
        runCatching {
            Glide.with(target)
                .load(Uri.parse(url))
                .circleCrop()
                .placeholder(placeholder)
                .error(placeholder)
                .transition(DrawableTransitionOptions.withCrossFade(220))
                .into(target)
        }.onFailure {
            target.setImageResource(placeholder)
        }
    }

    private fun openTelegram() {
        val ctx = host.context()
        TelegramJoinGatePolicy.markOpened()
        val opened = SupportLinkOpener.openTelegram(ctx) ||
            SupportLinkOpener.open(ctx, TelegramJoinGatePolicy.WEB_URL, markAppreciation = false)
        if (!opened) {
            Toast.makeText(ctx, R.string.tg_gate_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDiscord() {
        val ctx = host.context()
        val opened = SupportLinkOpener.open(ctx, SupportUrlsDiscord(), markAppreciation = false)
        if (!opened) {
            Toast.makeText(ctx, R.string.tg_gate_discord_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun SupportUrlsDiscord(): String = TelegramJoinGatePolicy.DISCORD_URL

    private fun reduceMotion(): Boolean =
        Settings.Global.getFloat(
            host.context().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f

    private fun playEnterMotion() {
        if (reduceMotion()) return
        val views = listOf(
            binding.tvTgGateBrand,
            binding.vTgGateBrandRule,
            binding.tvTgGateHeadline,
            binding.tvTgGateBody,
            binding.rowTgGateTelegram,
            binding.rowTgGateDiscord,
            binding.tvTgGateHint,
            binding.btnTgGateContinue,
        )
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 18f + index
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(36L * index)
                .setDuration(420L)
                .setInterpolator(DecelerateInterpolator(1.35f))
                .start()
        }
        binding.tvTgGateBrand.scaleX = 0.96f
        binding.tvTgGateBrand.scaleY = 0.96f
        binding.tvTgGateBrand.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(520L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun haptic(view: View) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun dp(value: Int): Int =
        (value * host.context().resources.displayMetrics.density).toInt()
}
