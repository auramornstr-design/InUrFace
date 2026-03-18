/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * ElementAdapter.kt
 * RecyclerView adapter for the learned element list in EditorActivity.
 * Shows each mapped element with its capability chips and grid position.
 */

package com.inyourface.app.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.inyourface.app.R
import com.inyourface.app.model.CapabilityType
import com.inyourface.app.model.CapabilityValue
import com.inyourface.app.model.MappedElement

class ElementAdapter(
    private val onDelete: (MappedElement) -> Unit,
    private val onEditCapability: (MappedElement, CapabilityType) -> Unit
) : ListAdapter<MappedElement, ElementAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_element, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val colorDot: View = view.findViewById(R.id.elementColorDot)
        private val label: TextView = view.findViewById(R.id.elementLabel)
        private val gridPos: TextView = view.findViewById(R.id.elementGridPos)
        private val deleteBtn: ImageView = view.findViewById(R.id.btnDeleteElement)
        private val chipGroup: ChipGroup = view.findViewById(R.id.capabilityChips)

        fun bind(element: MappedElement) {
            val ctx = itemView.context

            // Label — use custom label capability if set, else content description, else fallback
            val customLabel = (element.capabilityStates[CapabilityType.LABEL.id]
                ?.currentValue as? CapabilityValue.Label)?.text
            label.text = customLabel
                ?: element.contentDescription?.take(24)
                ?: ctx.getString(R.string.element_label)

            // Grid position
            val primary = element.gridCells.firstOrNull()
            gridPos.text = if (primary != null) "[${primary.col},${primary.row}]" else ""

            // Color dot
            val colorHex = (element.capabilityStates[CapabilityType.RECOLOR.id]
                ?.currentValue as? CapabilityValue.Recolor)?.colorHex ?: "#2563A8"
            try {
                colorDot.background.setTint(Color.parseColor(colorHex))
            } catch (e: Exception) {
                colorDot.background.setTint(ContextCompat.getColor(ctx, R.color.brand_mid))
            }

            // Delete button
            deleteBtn.setOnClickListener { onDelete(element) }

            // Capability chips
            chipGroup.removeAllViews()
            element.availableCapabilities.forEach { capability ->
                val chip = Chip(ctx).apply {
                    text = capability.label
                    textSize = 11f
                    isCheckable = false
                    try {
                        chipBackgroundColor =
                            android.content.res.ColorStateList.valueOf(
                                Color.parseColor(capability.colorHex + "33")
                            )
                        setTextColor(Color.parseColor(capability.colorHex))
                        chipStrokeColor = android.content.res.ColorStateList.valueOf(
                            Color.parseColor(capability.colorHex + "66")
                        )
                        chipStrokeWidth = 1f
                    } catch (e: Exception) { /* use defaults */ }
                    setOnClickListener { onEditCapability(element, capability) }
                }
                chipGroup.addView(chip)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MappedElement>() {
            override fun areItemsTheSame(a: MappedElement, b: MappedElement) = a.id == b.id
            override fun areContentsTheSame(a: MappedElement, b: MappedElement) = a == b
        }
    }
}
