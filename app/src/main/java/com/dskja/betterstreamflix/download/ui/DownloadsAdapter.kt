package com.dskja.betterstreamflix.download.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.dskja.betterstreamflix.utils.ArtworkUrls
import com.dskja.betterstreamflix.utils.TvFocusChain
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.download.DownloadItemState
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign

class DownloadsAdapter(
    private val onPlay: (DownloadRowUiModel.Item) -> Unit,
    private val onPauseResume: (DownloadRowUiModel.Item) -> Unit,
    private val onRetry: (DownloadRowUiModel.Item) -> Unit,
    private val onDelete: (DownloadRowUiModel.Item) -> Unit,
    private val onItemMore: (DownloadRowUiModel.Item, View) -> Unit = { _, _ -> },
    private val onPackMore: (DownloadRowUiModel.SeasonPack, View) -> Unit = { _, _ -> },
) : ListAdapter<DownloadRowUiModel, RecyclerView.ViewHolder>(Diff) {

    object Payload {
        const val PROGRESS = "progress"
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is DownloadRowUiModel.Header -> TYPE_HEADER
        is DownloadRowUiModel.SeasonPack -> TYPE_PACK
        is DownloadRowUiModel.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(
                inflater.inflate(
                    R.layout.item_download_header,
                    parent,
                    false,
                )
            )
            TYPE_PACK -> PackVH(
                inflater.inflate(
                    R.layout.item_download_season,
                    parent,
                    false,
                )
            )
            else -> ItemVH(
                inflater.inflate(
                    R.layout.item_download,
                    parent,
                    false,
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is DownloadRowUiModel.Header -> (holder as HeaderVH).bind(item)
            is DownloadRowUiModel.SeasonPack -> (holder as PackVH).bind(item)
            is DownloadRowUiModel.Item -> (holder as ItemVH).bind(item)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.isNotEmpty() && holder is ItemVH && payloadsContainProgress(payloads)) {
            val item = getItem(position) as? DownloadRowUiModel.Item ?: return
            holder.bindProgress(item)
            holder.bindPrimaryAction(item)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.tv_download_header)
        fun bind(item: DownloadRowUiModel.Header) {
            title.text = item.title
            if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled()) {
                itemView.setBackgroundResource(
                    com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.glassCardBackground(),
                )
                if (itemView.getTag(R.id.exp_enter_animated_tag) != true) {
                    itemView.setTag(R.id.exp_enter_animated_tag, true)
                    com.dskja.betterstreamflix.utils.ExpMotion.revealHeader(
                        title,
                        itemView.findViewById(R.id.v_download_header_rule),
                    )
                    com.dskja.betterstreamflix.utils.ExpMotion.pulseAccentRule(
                        itemView.findViewById(R.id.v_download_header_rule),
                    )
                }
            }
        }
    }

    inner class PackVH(view: View) : RecyclerView.ViewHolder(view) {
        init {
            if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled()) {
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { view.applyExpPress() }
            }
        }
        private val title: TextView = view.findViewById(R.id.tv_download_season_title)
        private val subtitle: TextView = view.findViewById(R.id.tv_download_season_subtitle)
        private val progress: ProgressBar = view.findViewById(R.id.pb_download_season)
        fun bind(item: DownloadRowUiModel.SeasonPack) {
            val pack = item.pack
            itemView.nextFocusLeftId = R.id.nav_main
            title.text = pack.tvShowTitle
            subtitle.text = itemView.context.getString(
                R.string.downloads_season_progress,
                pack.completedEpisodes,
                pack.totalEpisodes,
            )
            progress.max = pack.totalEpisodes.coerceAtLeast(1)
            val target = pack.completedEpisodes
            if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled() &&
                itemView.getTag(R.id.exp_enter_animated_tag) != true
            ) {
                itemView.setTag(R.id.exp_enter_animated_tag, true)
                itemView.setBackgroundResource(
                    com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.glassCardBackground(),
                )
                ExpMotion.revealHeader(title, subtitle)
                ExpMotion.popIn(itemView)
                android.animation.ObjectAnimator.ofInt(progress, "progress", 0, target)
                    .setDuration(420L)
                    .start()
            } else {
                if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled()) {
                    itemView.setBackgroundResource(
                        com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.glassCardBackground(),
                    )
                }
                progress.progress = target
            }
            itemView.setOnLongClickListener {
                ExpMotion.hapticTap(it)
                onPackMore(item, it)
                true
            }
        }
    }

    inner class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        init {
            if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled()) {
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { view.applyExpPress() }
            }
        }
        private val poster: ImageView = view.findViewById(R.id.iv_download_poster)
        private val title: TextView = view.findViewById(R.id.tv_download_title)
        private val subtitle: TextView = view.findViewById(R.id.tv_download_subtitle)
        private val progressLine: TextView = view.findViewById(R.id.tv_download_progress)
        private val progress: ProgressBar = view.findViewById(R.id.pb_download)
        private val actionPrimary: TextView = view.findViewById(R.id.btn_download_primary)
        private val actionDelete: TextView = view.findViewById(R.id.btn_download_delete)

        fun bind(item: DownloadRowUiModel.Item) {
            title.text = item.entity.title
            subtitle.text = listOfNotNull(
                item.entity.subtitle.takeIf { it.isNotBlank() },
                item.entity.providerName,
                item.entity.serverName.takeIf { it.isNotBlank() },
                item.entity.qualityLabel.takeIf { it.isNotBlank() },
            ).joinToString(" · ") + watchedSuffix(item)
            val posterUrl = ArtworkUrls.preferW500(item.entity.posterUrl) ?: item.entity.posterUrl
            Glide.with(poster).load(posterUrl).centerCrop().into(poster)
            TvFocusChain.linkHorizontal(actionPrimary, actionDelete)
            actionPrimary.nextFocusLeftId = R.id.nav_main
            bindProgress(item)
            bindPrimaryAction(item)
            actionDelete.setOnClickListener {
                ExpMotion.hapticTap(it)
                onDelete(item)
            }
            actionPrimary.setOnClickListener {
                ExpMotion.hapticTap(it)
                when (item.state) {
                    DownloadItemState.COMPLETED -> onPlay(item)
                    DownloadItemState.FAILED -> onRetry(item)
                    DownloadItemState.PAUSED,
                    DownloadItemState.QUEUED,
                    DownloadItemState.PREPARING,
                    DownloadItemState.DOWNLOADING,
                    -> onPauseResume(item)
                    DownloadItemState.REMOVING -> Unit
                }
            }
            if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled()) {
                itemView.setBackgroundResource(
                    com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.glassCardBackground(),
                )
                subtitle.setBackgroundResource(
                    com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.metaPillBackground(),
                )
                val density = itemView.resources.displayMetrics.density
                subtitle.setPadding(
                    (8 * density).toInt(),
                    (3 * density).toInt(),
                    (8 * density).toInt(),
                    (3 * density).toInt(),
                )
                actionPrimary.setBackgroundResource(
                    com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.primaryButtonBackground(),
                )
                actionDelete.setBackgroundResource(
                    com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.chipBackground(),
                )
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                    actionPrimary.applyExpPress()
                    actionDelete.applyExpPress()
                }
                if (itemView.getTag(R.id.exp_enter_animated_tag) != true) {
                    itemView.setTag(R.id.exp_enter_animated_tag, true)
                    ExpMotion.popIn(itemView)
                }
            }
            itemView.setOnLongClickListener {
                ExpMotion.hapticTap(it)
                onItemMore(item, it)
                true
            }
        }

        private fun watchedSuffix(item: DownloadRowUiModel.Item): String =
            if (item.entity.watchedOffline) {
                " · " + itemView.context.getString(R.string.downloads_watched)
            } else {
                ""
            }

        fun bindPrimaryAction(item: DownloadRowUiModel.Item) {
            actionPrimary.text = when (item.state) {
                DownloadItemState.COMPLETED -> itemView.context.getString(R.string.downloads_action_play)
                DownloadItemState.FAILED -> itemView.context.getString(R.string.downloads_action_retry)
                DownloadItemState.PAUSED -> itemView.context.getString(R.string.downloads_action_resume)
                else -> itemView.context.getString(R.string.downloads_action_pause)
            }
        }

        fun bindProgress(item: DownloadRowUiModel.Item) {
            val pct = item.entity.progressPct.coerceIn(0, 100)
            val wasFailed = progressLine.getTag(R.id.exp_enter_animated_tag) == "failed"
            progress.isIndeterminate = item.state == DownloadItemState.PREPARING ||
                (item.state.isActive && pct <= 0 && item.entity.bytesDownloaded <= 0L)
            if (!progress.isIndeterminate) {
                if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled() &&
                    progress.visibility == View.VISIBLE
                ) {
                    progress.animate().cancel()
                    progress.progress = pct
                } else {
                    progress.progress = pct
                }
            }
            progressLine.text = if (item.state == DownloadItemState.FAILED) {
                item.failedSummary(itemView.context)
            } else {
                item.progressText
            }
            progressLine.contentDescription = itemView.context.getString(
                R.string.downloads_progress_a11y,
                pct,
            )
            progress.visibility = if (item.state == DownloadItemState.COMPLETED) {
                View.GONE
            } else {
                View.VISIBLE
            }
            if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled() &&
                item.state == DownloadItemState.FAILED &&
                !wasFailed
            ) {
                progressLine.setTag(R.id.exp_enter_animated_tag, "failed")
                progressLine.setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        progressLine, androidx.appcompat.R.attr.colorError,
                    )
                )
                com.dskja.betterstreamflix.utils.ExpMotion.popIn(progressLine)
                com.dskja.betterstreamflix.utils.ExpMotion.popIn(actionPrimary)
            } else if (item.state != DownloadItemState.FAILED) {
                progressLine.setTag(R.id.exp_enter_animated_tag, null)
                if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled()) {
                    val onVariant = com.google.android.material.color.MaterialColors.getColor(
                        progressLine,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    )
                    progressLine.setTextColor(onVariant)
                    val primary = com.google.android.material.color.MaterialColors.getColor(
                        progress,
                        androidx.appcompat.R.attr.colorPrimary,
                    )
                    progress.progressTintList =
                        android.content.res.ColorStateList.valueOf(primary)
                    progress.indeterminateTintList =
                        android.content.res.ColorStateList.valueOf(primary)
                }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<DownloadRowUiModel>() {
        override fun areItemsTheSame(oldItem: DownloadRowUiModel, newItem: DownloadRowUiModel): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: DownloadRowUiModel, newItem: DownloadRowUiModel): Boolean =
            oldItem == newItem

        override fun getChangePayload(oldItem: DownloadRowUiModel, newItem: DownloadRowUiModel): Any? {
            if (oldItem is DownloadRowUiModel.Item && newItem is DownloadRowUiModel.Item) {
                if (oldItem.entity.state != newItem.entity.state) return null
                if (oldItem.entity.progressPct != newItem.entity.progressPct ||
                    oldItem.entity.bytesDownloaded != newItem.entity.bytesDownloaded ||
                    oldItem.entity.contentLength != newItem.entity.contentLength ||
                    oldItem.entity.speedBytesPerSec != newItem.entity.speedBytesPerSec ||
                    oldItem.entity.etaSeconds != newItem.entity.etaSeconds
                ) {
                    return Payload.PROGRESS
                }
            }
            return null
        }
    }

    companion object {
        private const val TYPE_HEADER = 1
        private const val TYPE_ITEM = 2
        private const val TYPE_PACK = 3

        private fun payloadsContainProgress(payloads: List<Any>): Boolean {
            for (payload in payloads) {
                when (payload) {
                    Payload.PROGRESS -> return true
                    is Collection<*> -> {
                        if (payload.any { it == Payload.PROGRESS }) return true
                    }
                }
            }
            return false
        }
    }
}
