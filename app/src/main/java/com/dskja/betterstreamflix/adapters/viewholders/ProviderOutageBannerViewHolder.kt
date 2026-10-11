package com.dskja.betterstreamflix.adapters.viewholders

import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.providers.ProviderOutageBannerItem
import com.dskja.betterstreamflix.providers.ProviderTrustLayer

class ProviderOutageBannerViewHolder(
    itemView: View,
) : RecyclerView.ViewHolder(itemView) {

    fun bind(
        item: ProviderOutageBannerItem,
        onOpen: ((ProviderOutageBannerItem) -> Unit)?,
        onDismiss: ((ProviderOutageBannerItem) -> Unit)?,
    ) {
        val title = itemView.findViewById<TextView>(R.id.tv_provider_outage_title)
        val subtitle = itemView.findViewById<TextView>(R.id.tv_provider_outage_subtitle)
        val cta = itemView.findViewById<TextView>(R.id.btn_provider_outage_cta)
        val dismiss = itemView.findViewById<ImageButton>(R.id.btn_provider_outage_dismiss)
        val icon = itemView.findViewById<ImageView>(R.id.iv_provider_outage_icon)

        val names = item.providerNames.take(3).joinToString(", ")
        title.text = when (item.level) {
            ProviderTrustLayer.OutageLevel.SEVERE ->
                itemView.context.getString(R.string.provider_outage_banner_title_severe)
            else ->
                itemView.context.getString(R.string.provider_outage_banner_title_watch)
        }
        subtitle.text = itemView.context.getString(
            R.string.provider_outage_banner_subtitle,
            names.ifBlank { itemView.context.getString(R.string.provider_trust_server_unknown) },
            item.deadCount,
        )
        cta.text = itemView.context.getString(R.string.provider_outage_banner_cta)
        icon?.setImageResource(R.drawable.ic_providers_language)

        val open = View.OnClickListener { onOpen?.invoke(item) }
        itemView.setOnClickListener(open)
        cta.setOnClickListener(open)
        dismiss.setOnClickListener { onDismiss?.invoke(item) }
    }
}
