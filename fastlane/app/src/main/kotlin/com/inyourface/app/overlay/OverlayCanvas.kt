/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * OverlayCanvas.kt
 * The overlay surface that sits on top of every foreign app.
 * Owned by the DiplomatRuntime. Rendered via TYPE_ACCESSIBILITY_OVERLAY.
 *
 * Phase 2 additions:
 *   - Full drag-to-reposition in Customize Mode
 *   - Drag ghost — translucent copy of button follows the finger
 *   - Snap-to-grid on finger release
 *   - Live zone validation during drag (forbidden zones highlight red as ghost enters)
 *   - Grid coordinate label overlay (toggled by user preference)
 *   - Haptic feedback on snap
 */

package com.inyourface.app.overlay

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import com.inyourface.app.grid.GridSystem
import com.inyourface.app.model.*

class OverlayCanvas(
    context: Context,
    private val markerSurface: MarkerSurface
) : View(context) {

    // ─── State ────────────────────────────────────────────────────────────────

    private var isCustomizeMode = false
    private var activeTranslationKey: TranslationKey? = null
    private var activeGridSystem: GridSystem? = null
    private var proxyButtons = listOf<ProxyButton>()
    private var showGridCoords = false
    private var hapticEnabled = true

    // Drag state
    private var dragButton: ProxyButton? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var dragCurrentX = 0f
    private var dragCurrentY = 0f
    private var dragTargetCell: GridCoordinate? = null
    private var isDragging = false

    // ─── Vibrator ─────────────────────────────────────────────────────────────

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    // ─── Paints ───────────────────────────────────────────────────────────────

    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val safeZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 50, 200, 80); style = Paint.Style.FILL
    }
    private val forbiddenZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 220, 50, 50); style = Paint.Style.FILL
    }
    private val cautionZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(35, 160, 160, 160); style = Paint.Style.FILL
    }
    private val forbiddenBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 220, 50, 50); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val snapTargetSafePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 50, 220, 100); style = Paint.Style.FILL
    }
    private val snapTargetForbiddenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 220, 50, 50); style = Paint.Style.FILL
    }
    private val snapTargetBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val proxyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val proxyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val proxyLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 28f
    }
    private val ghostFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; alpha = 140
    }
    private val ghostBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.WHITE; alpha = 160
    }
    private val capabilityBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val capabilityLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val gridCoordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255); textSize = 18f; textAlign = Paint.Align.LEFT
    }
    private val customizeTintPaint = Paint().apply {
        color = Color.argb(60, 10, 20, 50); style = Paint.Style.FILL
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    fun setPreferences(showCoords: Boolean, haptic: Boolean) {
        showGridCoords = showCoords; hapticEnabled = haptic; invalidate()
    }

    fun activateCustomizeMode(key: TranslationKey, grid: GridSystem) {
        isCustomizeMode = true; activeTranslationKey = key; activeGridSystem = grid
        proxyButtons = buildProxyButtons(key, grid); invalidate()
    }

    fun deactivateCustomizeMode() {
        isCustomizeMode = false; isDragging = false; dragButton = null
        val key = activeTranslationKey; val grid = activeGridSystem
        if (key != null && grid != null) proxyButtons = buildProxyButtons(key, grid)
        invalidate()
    }

    fun loadTranslationKey(key: TranslationKey, grid: GridSystem) {
        activeTranslationKey = key; activeGridSystem = grid
        proxyButtons = buildProxyButtons(key, grid); invalidate()
    }

    // ─── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isCustomizeMode) drawCustomizeMode(canvas) else drawRuntimeMode(canvas)
    }

    private fun drawRuntimeMode(canvas: Canvas) {
        proxyButtons.forEach { drawProxyButton(canvas, it) }
    }

    private fun drawCustomizeMode(canvas: Canvas) {
        val grid = activeGridSystem ?: return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), customizeTintPaint)
        drawZoneFills(canvas, grid)
        drawGridLines(canvas, grid)
        if (showGridCoords) drawGridCoords(canvas, grid)
        drawSnapTarget(canvas, grid)
        proxyButtons.forEach { btn ->
            if (btn.elementId != dragButton?.elementId) drawProxyButton(canvas, btn)
        }
        drawCapabilityBoxes(canvas, grid)
        if (isDragging) drawDragGhost(canvas)
    }

    private fun drawZoneFills(canvas: Canvas, grid: GridSystem) {
        for (col in 0 until grid.columns) {
            for (row in 0 until grid.rows) {
                val bounds = grid.toScreenBounds(col, row)
                when (grid.getZone(col, row)) {
                    ZoneClass.SAFE -> canvas.drawRect(bounds, safeZonePaint)
                    ZoneClass.FORBIDDEN -> {
                        canvas.drawRect(bounds, forbiddenZonePaint)
                        canvas.drawRect(bounds, forbiddenBorderPaint)
                    }
                    ZoneClass.CAUTION -> canvas.drawRect(bounds, cautionZonePaint)
                    ZoneClass.OCCUPIED -> {}
                }
            }
        }
    }

    private fun drawGridLines(canvas: Canvas, grid: GridSystem) {
        val cw = width.toFloat() / grid.columns; val ch = height.toFloat() / grid.rows
        for (col in 0..grid.columns) canvas.drawLine(col * cw, 0f, col * cw, height.toFloat(), gridLinePaint)
        for (row in 0..grid.rows) canvas.drawLine(0f, row * ch, width.toFloat(), row * ch, gridLinePaint)
    }

    private fun drawGridCoords(canvas: Canvas, grid: GridSystem) {
        for (col in 0 until grid.columns step 4)
            for (row in 0 until grid.rows step 4) {
                val b = grid.toScreenBounds(col, row)
                canvas.drawText("$col,$row", b.left + 4f, b.top + 20f, gridCoordPaint)
            }
    }

    private fun drawSnapTarget(canvas: Canvas, grid: GridSystem) {
        val cell = dragTargetCell ?: return
        val bounds = grid.toScreenBounds(cell.col, cell.row)
        canvas.drawRect(bounds, if (grid.isSafe(cell)) snapTargetSafePaint else snapTargetForbiddenPaint)
        canvas.drawRect(bounds, snapTargetBorderPaint)
    }

    private fun drawProxyButton(canvas: Canvas, button: ProxyButton, ghost: Boolean = false) {
        val bounds = if (ghost) RectF(
            dragCurrentX - button.bounds.width() / 2f,
            dragCurrentY - button.bounds.height() / 2f,
            dragCurrentX + button.bounds.width() / 2f,
            dragCurrentY + button.bounds.height() / 2f
        ) else button.bounds
        val corner = 12f
        val alpha = (((if (ghost) button.opacity * 0.55f else button.opacity)) * 255).toInt()
        val fill = if (ghost) ghostFillPaint else proxyFillPaint
        fill.color = button.fillColor; fill.alpha = alpha
        canvas.drawRoundRect(bounds, corner, corner, fill)
        val border = if (ghost) ghostBorderPaint else proxyBorderPaint
        if (!ghost) { border.color = button.borderColor; border.alpha = alpha }
        canvas.drawRoundRect(bounds, corner, corner, border)
        if (button.label.isNotEmpty()) {
            proxyLabelPaint.alpha = alpha
            canvas.drawText(button.label, bounds.centerX(), bounds.centerY() + proxyLabelPaint.textSize / 3f, proxyLabelPaint)
        }
    }

    private fun drawDragGhost(canvas: Canvas) {
        dragButton?.let { drawProxyButton(canvas, it, ghost = true) }
    }

    private fun drawCapabilityBoxes(canvas: Canvas, grid: GridSystem) {
        activeTranslationKey?.mappedElements?.values?.forEach { element ->
            val primary = element.gridCells.firstOrNull() ?: return@forEach
            val cb = grid.toScreenBounds(primary)
            val caps = element.availableCapabilities.toList()
            val boxSize = 36f; val boxGap = 4f
            val totalW = caps.size * (boxSize + boxGap) - boxGap
            var x = cb.left + (cb.width() - totalW) / 2f; val y = cb.top + 6f
            caps.forEach { cap ->
                val r = RectF(x, y, x + boxSize, y + boxSize)
                capabilityBoxPaint.color = Color.parseColor(cap.colorHex)
                canvas.drawRoundRect(r, 4f, 4f, capabilityBoxPaint)
                canvas.drawText(cap.id.toString(), r.centerX(), r.centerY() + capabilityLabelPaint.textSize / 3f, capabilityLabelPaint)
                x += boxSize + boxGap
            }
        }
    }

    // ─── Touch ────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (isCustomizeMode) handleCustomizeTouch(event) else handleRuntimeTouch(event)

    private fun handleRuntimeTouch(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return false
        val tapped = proxyButtons.find { it.bounds.contains(event.x, event.y) } ?: return false
        markerSurface.place(OverlayMarker.ProxyButtonTap(
            id = generateMarkerId(), placedAtMs = System.currentTimeMillis(), elementId = tapped.elementId
        ))
        return true
    }

    private fun handleCustomizeTouch(event: MotionEvent): Boolean {
        val grid = activeGridSystem ?: return true
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val hit = proxyButtons.find { it.bounds.contains(event.x, event.y) }
                if (hit != null) {
                    dragButton = hit
                    dragOffsetX = event.x - hit.bounds.centerX()
                    dragOffsetY = event.y - hit.bounds.centerY()
                    dragCurrentX = event.x; dragCurrentY = event.y
                    isDragging = true
                    dragTargetCell = grid.toGridCoordinate(event.x, event.y)
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return true
                dragCurrentX = event.x; dragCurrentY = event.y
                dragTargetCell = grid.toGridCoordinate(event.x - dragOffsetX, event.y - dragOffsetY)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    val cell = dragTargetCell; val btn = dragButton
                    if (cell != null && btn != null && grid.isSafe(cell)) {
                        snapButtonToCell(btn, cell); hapticSnap()
                    }
                }
                isDragging = false; dragButton = null; dragTargetCell = null; invalidate()
            }
        }
        return true
    }

    private fun snapButtonToCell(button: ProxyButton, cell: GridCoordinate) {
        markerSurface.place(OverlayMarker.CapabilityChangeRequest(
            id = generateMarkerId(),
            placedAtMs = System.currentTimeMillis(),
            elementId = button.elementId,
            capabilityType = CapabilityType.POSITION,
            newValue = CapabilityValue.Position(cell.col, cell.row)
        ))
        val grid = activeGridSystem ?: return
        val nb = grid.toScreenBounds(cell)
        val updated = button.copy(bounds = RectF(nb.left + 4f, nb.top + 4f, nb.right - 4f, nb.bottom - 4f))
        proxyButtons = proxyButtons.map { if (it.elementId == button.elementId) updated else it }
        invalidate()
    }

    private fun hapticSnap() {
        if (!hapticEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator?.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vibrator?.vibrate(18)
        } catch (e: Exception) {}
    }

    // ─── Proxy Button Construction ────────────────────────────────────────────

    private fun buildProxyButtons(key: TranslationKey, grid: GridSystem): List<ProxyButton> {
        return key.mappedElements.values.mapNotNull { element ->
            val primary = element.gridCells.firstOrNull() ?: return@mapNotNull null
            val posState = element.capabilityStates[CapabilityType.POSITION.id]
            val activeCell = if (posState?.currentValue is CapabilityValue.Position) {
                val p = posState.currentValue as CapabilityValue.Position; GridCoordinate(p.gridCol, p.gridRow)
            } else primary
            val cb = grid.toScreenBounds(activeCell)
            val wC = (element.capabilityStates[CapabilityType.SIZE.id]?.currentValue as? CapabilityValue.Size)?.widthCells ?: 1
            val hC = (element.capabilityStates[CapabilityType.SIZE.id]?.currentValue as? CapabilityValue.Size)?.heightCells ?: 1
            val bounds = RectF(cb.left + 4f, cb.top + 4f, cb.left + cb.width() * wC - 4f, cb.top + cb.height() * hC - 4f)
            val colorHex = (element.capabilityStates[CapabilityType.RECOLOR.id]?.currentValue as? CapabilityValue.Recolor)?.colorHex ?: "#1A3A6A"
            val fillColor = try { Color.parseColor(colorHex) } catch (e: Exception) { Color.parseColor("#1A3A6A") }
            val label = (element.capabilityStates[CapabilityType.LABEL.id]?.currentValue as? CapabilityValue.Label)?.text ?: element.contentDescription?.take(10) ?: ""
            val opacity = (element.capabilityStates[CapabilityType.OPACITY.id]?.currentValue as? CapabilityValue.Opacity)?.alpha ?: 0.85f
            val borderHex = (element.capabilityStates[CapabilityType.BORDER.id]?.currentValue as? CapabilityValue.Border)?.colorHex ?: "#4A90D9"
            val borderColor = try { Color.parseColor(borderHex) } catch (e: Exception) { Color.parseColor("#4A90D9") }
            ProxyButton(element.id, bounds, label, fillColor, borderColor, opacity)
        }
    }

    data class ProxyButton(
        val elementId: String, val bounds: RectF, val label: String,
        val fillColor: Int, val borderColor: Int, val opacity: Float
    )
}
