/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * IFAdapter.kt — Phase 4 replacement for ProfileAdapter.kt
 * RecyclerView adapter for Interactive Faces in EditorActivity.
 *
 * Each card displays:
 *   - IF name
 *   - Active persona name
 *   - Health color as a left-edge colored bar
 *   - Health expression as a readable label
 *   - Last modified date
 *   - "Keep" button — adds the IF to the Manakit for auto-deploy
 *   - Long press → delete confirmation
 *
 * ProfileAdapter.kt should be removed from the project once this file is in place.
 * This adapter uses the same item_profile.xml layout — no layout changes needed.
 */

package com.inyourface.app.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.inyourface.app.R
import com.inyourface.app.model.IFSummary
import java.text.SimpleDateFormat
import java.util.*

class IFAdapter(
    private val onKeep:   (IFSummary) -> Unit,
    private val onDelete: (IFSummary) -> Unit
) : ListAdapter<IFSummary, IFAdapter.ViewHolder>(DIFF) {

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_profile, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // item_profile.xml uses these same view ids — reused without layout change
        private val activeBar:   View     = view.findViewById(R.id.activeBar)
        private val name:        TextView = view.findViewById(R.id.profileName)
        private val meta:        TextView = view.findViewById(R.id.profileMeta)
        private val keepBtn:     TextView = view.findViewById(R.id.btnActivateProfile)

        fun bind(summary: IFSummary) {
            val ctx = itemView.context

            name.text = summary.name
            meta.text = buildString {
                append(summary.activePersonaName)
                append("  ·  ")
                append(summary.healthExpression.label)
                append("  ·  ")
                append(dateFormat.format(Date(summary.lastModifiedAtMs)))
            }

            // Health color drives the left-edge active bar color
            try {
                activeBar.visibility = View.VISIBLE
                activeBar.background.setTint(Color.parseColor(summary.healthColor.hex))
            } catch (e: Exception) {
                activeBar.visibility = View.INVISIBLE
            }

            // Name color reflects health — RED/ORANGE IFs are visually flagged
            name.setTextColor(
                when (summary.healthColor) {
                    com.inyourface.app.model.HealthColor.RED,
                    com.inyourface.app.model.HealthColor.ORANGE ->
                        Color.parseColor(summary.healthColor.hex)
                    com.inyourface.app.model.HealthColor.BLUE,
                    com.inyourface.app.model.HealthColor.GREEN ->
                        try {
                            androidx.core.content.ContextCompat.getColor(ctx, R.color.brand_accent)
                        } catch (e: Exception) { Color.WHITE }
                    else ->
                        try {
                            androidx.core.content.ContextCompat.getColor(ctx, R.color.brand_on_surface)
                        } catch (e: Exception) { Color.LTGRAY }
                }
            )

            keepBtn.text = ctx.getString(R.string.keep_if)
            keepBtn.setOnClickListener { onKeep(summary) }
            itemView.setOnLongClickListener { onDelete(summary); true }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<IFSummary>() {
            override fun areItemsTheSame(a: IFSummary, b: IFSummary) = a.id == b.id
            override fun areContentsTheSame(a: IFSummary, b: IFSummary) = a == b
        }
    }
}
