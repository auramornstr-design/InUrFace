/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * CapabilityEditorDialog.kt
 * Proper capability editors for Size and Border — these were stubs in Phase 2.
 * Also provides a unified dispatcher so EditorActivity stays clean.
 *
 * Each editor is a static factory function that builds and shows the right
 * dialog for the capability type, calls the RepresentativeRuntime to
 * request the change, and handles the Diplomat's accept/reject response.
 */

package com.inyourface.app.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.inyourface.app.model.*
import com.inyourface.app.overlay.RejectionReason
import com.inyourface.app.representative.RepresentativeRuntime

object CapabilityEditorDialog {

    /**
     * Dispatch to the right editor for any capability type.
     * Call this instead of a when() block in EditorActivity.
     */
    fun show(
        context: Context,
        element: MappedElement,
        capability: CapabilityType,
        runtime: RepresentativeRuntime,
        onComplete: (accepted: Boolean) -> Unit
    ) {
        when (capability) {
            CapabilityType.LABEL       -> showLabelEditor(context, element, capability, runtime, onComplete)
            CapabilityType.RECOLOR     -> showColorPicker(context, element, capability, runtime, onComplete)
            CapabilityType.OPACITY     -> showOpacityPicker(context, element, capability, runtime, onComplete)
            CapabilityType.ACTION_TYPE -> showActionTypePicker(context, element, capability, runtime, onComplete)
            CapabilityType.SIZE        -> showSizeEditor(context, element, capability, runtime, onComplete)
            CapabilityType.BORDER      -> showBorderEditor(context, element, capability, runtime, onComplete)
            CapabilityType.POSITION,
            CapabilityType.TARGET_BINDING -> showGridOnlyHint(context, capability)
        }
    }

    // ─── Label Editor ─────────────────────────────────────────────────────────

    private fun showLabelEditor(
        ctx: Context, element: MappedElement, cap: CapabilityType,
        runtime: RepresentativeRuntime, onComplete: (Boolean) -> Unit
    ) {
        val current = (element.capabilityStates[cap.id]
            ?.currentValue as? CapabilityValue.Label)?.text ?: ""
        val input = EditText(ctx).apply {
            setText(current)
            hint = "Enter label"
            setSingleLine()
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Rename Element")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    runtime.requestCapabilityChange(element.id, cap, CapabilityValue.Label(text)) { ok, _ ->
                        onComplete(ok)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Color Picker ─────────────────────────────────────────────────────────

    private val colorOptions = listOf(
        "#E94560" to "Accent Red",
        "#2563A8" to "Deep Blue",
        "#1A7A1A" to "Forest Green",
        "#7A1A7A" to "Purple",
        "#F5A623" to "Amber",
        "#1A7A7A" to "Teal",
        "#0F3460" to "Navy",
        "#555555" to "Slate",
        "#C0392B" to "Crimson",
        "#2980B9" to "Sky Blue",
        "#27AE60" to "Emerald",
        "#8E44AD" to "Violet",
        "#FFFFFF" to "White",
        "#000000" to "Black"
    )

    private fun showColorPicker(
        ctx: Context, element: MappedElement, cap: CapabilityType,
        runtime: RepresentativeRuntime, onComplete: (Boolean) -> Unit
    ) {
        val current = (element.capabilityStates[cap.id]
            ?.currentValue as? CapabilityValue.Recolor)?.colorHex

        val grid = GridLayout(ctx).apply {
            columnCount = 4
            setPadding(24, 24, 24, 24)
        }

        colorOptions.forEachIndexed { _, (hex, name) ->
            val swatch = View(ctx).apply {
                val size = (ctx.resources.displayMetrics.density * 52).toInt()
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size; height = size
                    setMargins(8, 8, 8, 8)
                }
                try { setBackgroundColor(Color.parseColor(hex)) } catch (e: Exception) {}
                contentDescription = name
                if (hex == current) {
                    scaleX = 0.85f; scaleY = 0.85f
                    alpha = 0.6f
                }
            }
            grid.addView(swatch)
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Choose Color")
            .setView(grid)
            .setNegativeButton("Cancel", null)
            .create()

        grid.children().forEachIndexed { index, view ->
            view.setOnClickListener {
                val hex = colorOptions[index].first
                runtime.requestCapabilityChange(
                    element.id, cap, CapabilityValue.Recolor(hex)
                ) { ok, _ -> onComplete(ok) }
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    // ─── Opacity Picker ───────────────────────────────────────────────────────

    private fun showOpacityPicker(
        ctx: Context, element: MappedElement, cap: CapabilityType,
        runtime: RepresentativeRuntime, onComplete: (Boolean) -> Unit
    ) {
        val current = (element.capabilityStates[cap.id]
            ?.currentValue as? CapabilityValue.Opacity)?.alpha ?: 0.85f

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val preview = View(ctx).apply {
            val h = (ctx.resources.displayMetrics.density * 48).toInt()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
            setBackgroundColor(Color.parseColor("#E94560"))
            alpha = current
        }

        val valueLabel = TextView(ctx).apply {
            text = "${(current * 100).toInt()}%"
            gravity = Gravity.CENTER
            textSize = 20f
            setPadding(0, 16, 0, 0)
        }

        val slider = SeekBar(ctx).apply {
            max = 100
            progress = (current * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val alpha = progress / 100f
                    preview.alpha = alpha
                    valueLabel.text = "$progress%"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        container.addView(preview)
        container.addView(slider)
        container.addView(valueLabel)

        AlertDialog.Builder(ctx)
            .setTitle("Opacity")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                val alpha = slider.progress / 100f
                runtime.requestCapabilityChange(
                    element.id, cap, CapabilityValue.Opacity(alpha)
                ) { ok, _ -> onComplete(ok) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Action Type Picker ───────────────────────────────────────────────────

    private fun showActionTypePicker(
        ctx: Context, element: MappedElement, cap: CapabilityType,
        runtime: RepresentativeRuntime, onComplete: (Boolean) -> Unit
    ) {
        val gestures = GestureType.values()
        val labels = gestures.map { it.name.replace('_', ' ').lowercase()
            .replaceFirstChar { c -> c.uppercase() } }.toTypedArray()
        val current = (element.capabilityStates[cap.id]
            ?.currentValue as? CapabilityValue.ActionType)?.gesture
        val currentIndex = gestures.indexOf(current)

        AlertDialog.Builder(ctx)
            .setTitle("Action Type")
            .setSingleChoiceItems(labels, currentIndex) { dialog, index ->
                runtime.requestCapabilityChange(
                    element.id, cap, CapabilityValue.ActionType(gestures[index])
                ) { ok, _ -> onComplete(ok) }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Size Editor (Phase 3 — was stub) ────────────────────────────────────

    private fun showSizeEditor(
        ctx: Context, element: MappedElement, cap: CapabilityType,
        runtime: RepresentativeRuntime, onComplete: (Boolean) -> Unit
    ) {
        val currentW = (element.capabilityStates[cap.id]
            ?.currentValue as? CapabilityValue.Size)?.widthCells ?: 1
        val currentH = (element.capabilityStates[cap.id]
            ?.currentValue as? CapabilityValue.Size)?.heightCells ?: 1

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        fun row(label: String, current: Int, max: Int): SeekBar {
            val lbl = TextView(ctx).apply {
                text = "$label: $current cell(s)"
                textSize = 14f
                setPadding(0, 16, 0, 4)
            }
            val bar = SeekBar(ctx).apply {
                this.max = max - 1
                progress = current - 1
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        lbl.text = "$label: ${p + 1} cell(s)"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            container.addView(lbl)
            container.addView(bar)
            return bar
        }

        val widthBar = row("Width", currentW, 6)
        val heightBar = row("Height", currentH, 6)

        AlertDialog.Builder(ctx)
            .setTitle("Button Size")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                runtime.requestCapabilityChange(
                    element.id, cap,
                    CapabilityValue.Size(widthBar.progress + 1, heightBar.progress + 1)
                ) { ok, _ -> onComplete(ok) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Border Editor (Phase 3 — was stub) ───────────────────────────────────

    private fun showBorderEditor(
        ctx: Context, element: MappedElement, cap: CapabilityType,
        runtime: RepresentativeRuntime, onComplete: (Boolean) -> Unit
    ) {
        val current = element.capabilityStates[cap.id]?.currentValue as? CapabilityValue.Border

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        // Style options
        val styleLabel = TextView(ctx).apply { text = "Border Style"; textSize = 14f; setPadding(0, 8, 0, 4) }
        container.addView(styleLabel)

        val styleGroup = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        val styleMap = mapOf(
            BorderStyle.NONE    to "None",
            BorderStyle.SOLID   to "Solid",
            BorderStyle.DASHED  to "Dashed",
            BorderStyle.ROUNDED to "Rounded"
        )
        var selectedStyle = current?.style ?: BorderStyle.SOLID
        styleMap.forEach { (style, label) ->
            RadioButton(ctx).apply {
                text = label
                id = style.ordinal
                isChecked = style == selectedStyle
                setOnClickListener { selectedStyle = style }
            }.also { styleGroup.addView(it) }
        }
        container.addView(styleGroup)

        // Thickness
        val thickLabel = TextView(ctx).apply {
            text = "Thickness: ${current?.strokeDp?.toInt() ?: 2}dp"
            textSize = 14f; setPadding(0, 16, 0, 4)
        }
        container.addView(thickLabel)
        val thickBar = SeekBar(ctx).apply {
            max = 8; progress = (current?.strokeDp?.toInt() ?: 2) - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    thickLabel.text = "Thickness: ${p + 1}dp"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        container.addView(thickBar)

        // Color
        val colorLabel = TextView(ctx).apply { text = "Border Color"; textSize = 14f; setPadding(0, 16, 0, 4) }
        container.addView(colorLabel)

        var selectedColorHex = current?.colorHex ?: "#4A90D9"
        val colorRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val previewColors = listOf("#4A90D9","#E94560","#1A7A1A","#FFFFFF","#F5A623","#7A1A7A","#1A7A7A","#555555")
        previewColors.forEach { hex ->
            View(ctx).apply {
                val sz = (ctx.resources.displayMetrics.density * 36).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { setMargins(6, 0, 6, 0) }
                try { setBackgroundColor(Color.parseColor(hex)) } catch (e: Exception) {}
                if (hex == selectedColorHex) alpha = 0.5f
                setOnClickListener { selectedColorHex = hex }
            }.also { colorRow.addView(it) }
        }
        container.addView(colorRow)

        AlertDialog.Builder(ctx)
            .setTitle("Border Style")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                if (selectedStyle == BorderStyle.NONE) {
                    runtime.requestCapabilityChange(
                        element.id, cap,
                        CapabilityValue.Border("#000000", 0f, BorderStyle.NONE)
                    ) { ok, _ -> onComplete(ok) }
                } else {
                    runtime.requestCapabilityChange(
                        element.id, cap,
                        CapabilityValue.Border(selectedColorHex, (thickBar.progress + 1).toFloat(), selectedStyle)
                    ) { ok, _ -> onComplete(ok) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Grid-Only Hint ───────────────────────────────────────────────────────

    private fun showGridOnlyHint(ctx: Context, cap: CapabilityType) {
        AlertDialog.Builder(ctx)
            .setTitle(cap.label)
            .setMessage("Use Customize Mode to adjust ${cap.label.lowercase()} — drag the button on the grid.")
            .setPositiveButton("Got it", null)
            .show()
    }

    // ─── Utility ──────────────────────────────────────────────────────────────

    private fun ViewGroup.children(): List<View> =
        (0 until childCount).map { getChildAt(it) }
}
