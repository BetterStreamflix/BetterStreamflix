package com.dskja.betterstreamflix.home

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.ui.UserDataNotifier
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Drag (Mobile) + DPAD/buttons (TV) editor for Remix Home block order.
 */
object HomeRemixUi {

    fun show(fragment: Fragment, onChanged: (() -> Unit)? = null) {
        val context = fragment.requireContext()
        val themed = ExpDialogChrome.themedContext(context)
        val content = LayoutInflater.from(themed)
            .inflate(R.layout.dialog_home_remix, null, false)
        val recycler = content.findViewById<RecyclerView>(R.id.rv_home_remix)
        val order = HomeRemix.parseOrder(UserPreferences.homeShelfBlockOrder).toMutableList()
        lateinit var adapter: RemixAdapter
        adapter = RemixAdapter(
            order = order,
            onMoveRequested = { from, to ->
                if (from == to || from !in order.indices || to !in order.indices) return@RemixAdapter
                val item = order.removeAt(from)
                order.add(to, item)
                adapter.notifyItemMoved(from, to)
                ExpMotion.hapticTap(recycler)
            },
        )
        recycler.layoutManager = LinearLayoutManager(themed)
        recycler.adapter = adapter
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val item = order.removeAt(from)
                order.add(to, item)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (viewHolder == null) return
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    ExpMotion.hapticTap(viewHolder.itemView)
                    viewHolder.itemView.animate()
                        .scaleX(1.03f)
                        .scaleY(1.03f)
                        .translationZ(10f * viewHolder.itemView.resources.displayMetrics.density)
                        .setDuration(140L)
                        .start()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationZ(0f)
                    .setDuration(160L)
                    .start()
            }
        })
        touchHelper.attachToRecyclerView(recycler)
        adapter.dragStarter = { holder -> touchHelper.startDrag(holder) }

        val builder = AlertDialog.Builder(themed)
        builder
            .setTitle(R.string.home_remix_title)
            .setView(content)
            .setPositiveButton(R.string.home_remix_apply) { _, _ ->
                UserPreferences.homeShelfBlockOrder = HomeRemix.serializeOrder(order)
                UserDataNotifier.notifyChanged()
                onChanged?.invoke()
                ExpDialogChrome.notify(
                    context,
                    R.string.home_remix_saved,
                    R.string.home_remix_title,
                )
            }
            .setNeutralButton(R.string.home_remix_reset) { _, _ ->
                UserPreferences.homeShelfBlockOrder =
                    HomeRemix.serializeOrder(HomeRemix.DEFAULT_ORDER)
                UserDataNotifier.notifyChanged()
                onChanged?.invoke()
                ExpDialogChrome.notify(
                    context,
                    R.string.home_remix_reset_done,
                    R.string.home_remix_title,
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    ExpDialogChrome.polishButtons(dialog)
                    content.findViewById<View>(R.id.rv_home_remix)?.requestFocus()
                }
                dialog.show()
            }
    }

    private class RemixAdapter(
        private val order: MutableList<HomeRemix.Block>,
        private val onMoveRequested: (from: Int, to: Int) -> Unit,
    ) : RecyclerView.Adapter<RemixAdapter.Holder>() {

        var dragStarter: ((RecyclerView.ViewHolder) -> Unit)? = null

        override fun getItemCount(): Int = order.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_home_remix_block, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val block = order[position]
            val ctx = holder.itemView.context
            holder.title.setText(titleRes(block))
            holder.summary.setText(summaryRes(block))
            holder.pos.text = (position + 1).toString()
            holder.handle.setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    ExpMotion.hapticTap(v)
                    dragStarter?.invoke(holder)
                }
                false
            }
            holder.up.setOnClickListener {
                val from = holder.bindingAdapterPosition
                if (from > 0) onMoveRequested(from, from - 1)
            }
            holder.down.setOnClickListener {
                val from = holder.bindingAdapterPosition
                if (from in 0 until order.lastIndex) onMoveRequested(from, from + 1)
            }
            holder.up.isEnabled = position > 0
            holder.down.isEnabled = position < order.lastIndex
            holder.up.alpha = if (position > 0) 1f else 0.35f
            holder.down.alpha = if (position < order.lastIndex) 1f else 0.35f
            holder.itemView.contentDescription = ctx.getString(
                R.string.home_remix_block_cd,
                ctx.getString(titleRes(block)),
                position + 1,
                order.size,
            )
        }

        private fun titleRes(block: HomeRemix.Block): Int = when (block) {
            HomeRemix.Block.CONTINUE -> R.string.home_remix_block_continue
            HomeRemix.Block.LIVE -> R.string.home_remix_block_live
            HomeRemix.Block.COLLECTIONS -> R.string.home_remix_block_collections
        }

        private fun summaryRes(block: HomeRemix.Block): Int = when (block) {
            HomeRemix.Block.CONTINUE -> R.string.home_remix_block_continue_summary
            HomeRemix.Block.LIVE -> R.string.home_remix_block_live_summary
            HomeRemix.Block.COLLECTIONS -> R.string.home_remix_block_collections_summary
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val pos: TextView = view.findViewById(R.id.tv_home_remix_pos)
            val handle: ImageView = view.findViewById(R.id.iv_home_remix_handle)
            val title: TextView = view.findViewById(R.id.tv_home_remix_title)
            val summary: TextView = view.findViewById(R.id.tv_home_remix_summary)
            val up: ImageButton = view.findViewById(R.id.btn_home_remix_up)
            val down: ImageButton = view.findViewById(R.id.btn_home_remix_down)
        }
    }
}
