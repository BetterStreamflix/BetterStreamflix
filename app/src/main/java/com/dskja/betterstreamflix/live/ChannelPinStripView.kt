package com.dskja.betterstreamflix.live

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.ChannelCoverResolver
import com.dskja.betterstreamflix.utils.LiveHubCatalog

/**
 * Instagram Stories-style horizontal rail of pinned live channels.
 */
class ChannelPinStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val titleView: TextView
    private val recycler: RecyclerView
    private val adapter = PinAdapter()

    var onChannelClick: ((TvShow) -> Unit)? = null
    var onChannelLongClick: ((TvShow) -> Unit)? = null
    var onAddClick: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        val padV = (6 * resources.displayMetrics.density).toInt()
        setPadding(0, padV, 0, padV / 2)
        LayoutInflater.from(context).inflate(R.layout.view_channel_pin_strip, this, true)
        titleView = findViewById(R.id.tv_channel_pin_strip_title)
        recycler = findViewById(R.id.rv_channel_pins)
        recycler.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        recycler.adapter = adapter
        recycler.itemAnimator = null
        isVisible = false
    }

    fun bind(channels: List<TvShow>, showAdd: Boolean = true) {
        adapter.submit(channels, showAdd)
        isVisible = channels.isNotEmpty() || showAdd
    }

    fun setTitleVisible(visible: Boolean) {
        titleView.isVisible = visible
    }

    private inner class PinAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var channels: List<TvShow> = emptyList()
        private var showAdd: Boolean = true

        fun submit(items: List<TvShow>, add: Boolean) {
            channels = items
            showAdd = add
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = channels.size + if (showAdd) 1 else 0

        override fun getItemViewType(position: Int): Int =
            if (showAdd && position == channels.size) TYPE_ADD else TYPE_CHANNEL

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view = inflater.inflate(R.layout.item_channel_pin, parent, false)
            return if (viewType == TYPE_ADD) AddHolder(view) else ChannelHolder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is ChannelHolder -> holder.bind(channels[position])
                is AddHolder -> holder.bind()
            }
        }
    }

    private inner class ChannelHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewById(R.id.iv_channel_pin)
        private val name: TextView = itemView.findViewById(R.id.tv_channel_pin_name)
        private val live: TextView = itemView.findViewById(R.id.tv_channel_pin_live)
        private val ring = image.parent as? FrameLayout

        fun bind(show: TvShow) {
            applyPinDensity(itemView, ring, image)
            ring?.setBackgroundResource(R.drawable.bg_channel_pin_ring)
            name.text = show.title
            live.isVisible = !LiveHubCatalog.programNowOf(show).isNullOrBlank()
            clearPlus(ring)
            val cover = runCatching {
                ChannelCoverResolver.resolveSync(show.title, show.poster, show.poster)
            }.getOrNull()
            val pin = com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.channelPinSizeDp()
            val pinPx = com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.dpToPx(image, pin)
            Glide.with(image)
                .load(cover ?: show.poster)
                .placeholder(R.drawable.bg_people_circle)
                .error(R.drawable.bg_people_circle)
                .override(pinPx, pinPx)
                .into(image)
            // SL-007: long-click unpins; nextFocusDown into main content.
            itemView.contentDescription = context.getString(
                R.string.meine_sender_leiste_pin_cd,
                show.title,
            )
            itemView.nextFocusDownId = R.id.nav_main_fragment
            itemView.setOnClickListener { onChannelClick?.invoke(show) }
            itemView.setOnLongClickListener {
                onChannelLongClick?.invoke(show)
                true
            }
        }
    }

    private inner class AddHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewById(R.id.iv_channel_pin)
        private val name: TextView = itemView.findViewById(R.id.tv_channel_pin_name)
        private val live: TextView = itemView.findViewById(R.id.tv_channel_pin_live)
        private val ring = image.parent as? FrameLayout

        fun bind() {
            applyPinDensity(itemView, ring, image)
            live.isVisible = false
            name.setText(R.string.meine_sender_leiste_add)
            ring?.setBackgroundResource(R.drawable.bg_channel_pin_add)
            image.setImageDrawable(null)
            image.setBackgroundResource(android.R.color.transparent)
            ensurePlusLabel(ring)
            itemView.contentDescription = context.getString(R.string.meine_sender_leiste_add)
            itemView.nextFocusDownId = R.id.nav_main_fragment
            itemView.setOnClickListener { onAddClick?.invoke() }
            itemView.setOnLongClickListener(null)
        }
    }

    private fun applyPinDensity(itemView: View, ring: FrameLayout?, image: ImageView) {
        val mode = com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.mode()
        val itemW = com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.dpToPx(
            itemView,
            com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.channelPinItemWidthDp(mode),
        )
        val pin = com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.dpToPx(
            itemView,
            com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.channelPinSizeDp(mode),
        )
        itemView.layoutParams = itemView.layoutParams?.also { it.width = itemW }
            ?: ViewGroup.LayoutParams(itemW, ViewGroup.LayoutParams.WRAP_CONTENT)
        ring?.layoutParams = ring?.layoutParams?.also {
            it.width = pin
            it.height = pin
        }
        image.layoutParams = image.layoutParams?.also {
            it.width = ViewGroup.LayoutParams.MATCH_PARENT
            it.height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    private fun ensurePlusLabel(ring: FrameLayout?) {
        if (ring == null) return
        val tag = "channel_pin_plus"
        var plus = ring.findViewWithTag<TextView>(tag)
        if (plus == null) {
            plus = TextView(context).apply {
                this.tag = tag
                text = "+"
                textSize = 22f
                setTextColor(context.getColor(R.color.cinema_text))
                gravity = android.view.Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
            ring.addView(plus)
        }
        plus.isVisible = true
    }

    private fun clearPlus(ring: FrameLayout?) {
        ring?.findViewWithTag<View>("channel_pin_plus")?.isVisible = false
    }

    companion object {
        private const val TYPE_CHANNEL = 0
        private const val TYPE_ADD = 1
    }
}
