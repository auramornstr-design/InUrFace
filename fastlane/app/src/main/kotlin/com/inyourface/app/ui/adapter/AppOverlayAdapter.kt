/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * AppOverlayAdapter.kt
 * RecyclerView adapter for the dashboard list of configured app overlays.
 */

package com.inyourface.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.inyourface.app.R
import com.inyourface.app.model.AppInfo

data class AppOverlayItem(
    val appInfo: AppInfo,
    val elementCount: Int,
    val isActive: Boolean
)

class AppOverlayAdapter(
    private val onItemClick: (AppOverlayItem) -> Unit
) : ListAdapter<AppOverlayItem, AppOverlayAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_overlay, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.appIcon)
        private val name: TextView = view.findViewById(R.id.appName)
        private val count: TextView = view.findViewById(R.id.elementCount)
        private val badge: TextView = view.findViewById(R.id.badgeActive)

        fun bind(item: AppOverlayItem) {
            name.text = item.appInfo.label
            count.text = itemView.context.getString(R.string.elements_count, item.elementCount)
            badge.visibility = if (item.isActive) View.VISIBLE else View.GONE
            icon.setImageDrawable(item.appInfo.icon(itemView.context))
            itemView.setOnClickListener { onItemClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppOverlayItem>() {
            override fun areItemsTheSame(a: AppOverlayItem, b: AppOverlayItem) =
                a.appInfo.packageName == b.appInfo.packageName
            override fun areContentsTheSame(a: AppOverlayItem, b: AppOverlayItem) = a == b
        }
    }
}
