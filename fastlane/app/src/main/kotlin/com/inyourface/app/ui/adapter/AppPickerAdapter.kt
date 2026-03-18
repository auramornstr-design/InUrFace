/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * AppPickerAdapter.kt
 * Searchable RecyclerView adapter for the app picker screen.
 * Handles filtering and configured-badge display.
 */

package com.inyourface.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.inyourface.app.R
import com.inyourface.app.model.AppInfo

class AppPickerAdapter(
    private val onItemClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

    private var fullList = listOf<AppInfo>()
    private var filteredList = listOf<AppInfo>()
    private var configuredPackages = setOf<String>()
    private var query = ""

    fun submitList(list: List<AppInfo>, configured: Set<String> = emptySet()) {
        fullList = list
        configuredPackages = configured
        applyFilter()
    }

    fun filter(q: String) {
        query = q.trim().lowercase()
        applyFilter()
    }

    private fun applyFilter() {
        filteredList = if (query.isEmpty()) fullList
        else fullList.filter {
            it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_picker, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(filteredList[position])
    }

    override fun getItemCount() = filteredList.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.appIcon)
        private val name: TextView = view.findViewById(R.id.appName)
        private val pkg: TextView = view.findViewById(R.id.appPackage)
        private val badge: TextView = view.findViewById(R.id.badgeConfigured)

        fun bind(app: AppInfo) {
            name.text = app.label
            pkg.text = app.packageName
            icon.setImageDrawable(app.icon(itemView.context))
            badge.visibility = if (app.packageName in configuredPackages) View.VISIBLE else View.GONE
            itemView.setOnClickListener { onItemClick(app) }
        }
    }
}
