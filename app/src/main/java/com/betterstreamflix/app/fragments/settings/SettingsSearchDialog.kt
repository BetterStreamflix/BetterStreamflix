package com.betterstreamflix.app.fragments.settings

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.XmlRes
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AlertDialog
import com.betterstreamflix.app.R
import com.betterstreamflix.app.utils.ExpDialogChrome

/**
 * Search dialog over the settings index. Results deep-link into the nested
 * preference screen and scroll straight to the matched preference.
 */
object SettingsSearchDialog {

    fun show(
        context: Context,
        @XmlRes settingsXmlRes: Int,
        onNavigate: (SettingsSearchIndex.Entry) -> Unit,
    ) {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_settings_search, null)
        val input = view.findViewById<EditText>(R.id.et_settings_search)
        val empty = view.findViewById<TextView>(R.id.tv_settings_search_empty)
        val list = view.findViewById<RecyclerView>(R.id.rv_settings_search)

        val entries = SettingsSearchIndex.index(context, settingsXmlRes)
        lateinit var dialogRef: AlertDialog
        val adapter = ResultAdapter { entry ->
            dialogRef.dismiss()
            onNavigate(entry)
        }
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter

        fun filter(query: String) {
            val q = query.trim()
            val results = if (q.isBlank()) {
                entries.filter { it.title.isNotBlank() }.take(30)
            } else {
                entries.filter { it.matches(q) }
            }
            adapter.submit(results)
            empty.visibility = if (results.isEmpty() && q.isNotBlank()) {
                View.VISIBLE
            } else {
                View.GONE
            }
            list.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
        }
        filter("")

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        val dialog = AlertDialog.Builder(ExpDialogChrome.themedContext(context))
            .setTitle(R.string.settings_search_title)
            .setView(view)
            .setNegativeButton(R.string.option_cancel, null)
            .create()
        dialogRef = dialog
        dialog.show()
        input.post { input.requestFocus() }
    }

    private class ResultAdapter(
        private val onClick: (SettingsSearchIndex.Entry) -> Unit,
    ) : RecyclerView.Adapter<ResultAdapter.Holder>() {

        private var items: List<SettingsSearchIndex.Entry> = emptyList()

        fun submit(results: List<SettingsSearchIndex.Entry>) {
            items = results
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_settings_search_result, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.title.text = entry.title
            val crumb = entry.path.ifBlank { holder.itemView.context.getString(R.string.settings_search_root) }
            holder.path.text = if (entry.summary.isBlank()) {
                crumb
            } else {
                "$crumb — ${entry.summary.take(80)}"
            }
            holder.itemView.setOnClickListener { onClick(entry) }
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tv_result_title)
            val path: TextView = v.findViewById(R.id.tv_result_path)
        }
    }
}
