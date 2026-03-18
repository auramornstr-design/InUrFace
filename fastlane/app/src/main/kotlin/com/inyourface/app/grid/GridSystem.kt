/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * GridSystem.kt
 * Converts between grid coordinates and physical screen coordinates.
 * Calculates adaptive resolution based on foreign app constraint density.
 * Manages zone classification for the constraint map.
 *
 * The grid is:
 *   - Visual feedback for the user (shown in Customize Mode)
 *   - Computational contract between runtimes (addressing system)
 *   - Anchor for dynamic mirroring (stable across minor UI shifts)
 *   - Constraint map (shows what's allowed)
 *
 * All of this in one structure. No separate subsystems.
 */

package com.inyourface.app.grid

import android.graphics.Rect
import android.graphics.RectF
import android.util.DisplayMetrics
import com.inyourface.app.model.*

// ─── Grid Cell ────────────────────────────────────────────────────────────────

data class GridCell(
    val col: Int,
    val row: Int,
    val bounds: RectF,              // Pixel bounds of this cell on screen
    val zone: ZoneClass = ZoneClass.SAFE
) {
    fun key() = "$col,$row"
    fun contains(x: Float, y: Float) = bounds.contains(x, y)
    fun toCoordinate() = GridCoordinate(col, row)
}

// ─── Grid System ──────────────────────────────────────────────────────────────

class GridSystem(
    private val screenWidth: Int,
    private val screenHeight: Int,
    val resolution: GridResolution
) {

    val columns: Int get() = resolution.columns
    val rows: Int get() = resolution.rows

    private val cellWidth: Float = screenWidth.toFloat() / columns
    private val cellHeight: Float = screenHeight.toFloat() / rows

    // The full grid — initially all SAFE, Diplomat updates zones after scanning
    private val cells: Array<Array<GridCell>> = Array(columns) { col ->
        Array(rows) { row ->
            GridCell(
                col = col,
                row = row,
                bounds = cellBoundsFor(col, row),
                zone = ZoneClass.SAFE
            )
        }
    }

    // ─── Coordinate Conversion ────────────────────────────────────────────────

    /** Convert grid coordinate to screen pixel bounds. */
    fun toScreenBounds(col: Int, row: Int): RectF = cellBoundsFor(col, row)

    fun toScreenBounds(coord: GridCoordinate): RectF = cellBoundsFor(coord.col, coord.row)

    /** Convert a screen pixel point to its grid cell, or null if out of bounds. */
    fun toGridCoordinate(screenX: Float, screenY: Float): GridCoordinate? {
        val col = (screenX / cellWidth).toInt()
        val row = (screenY / cellHeight).toInt()
        return if (col in 0 until columns && row in 0 until rows)
            GridCoordinate(col, row)
        else null
    }

    /** Convert a screen Rect to the set of grid cells it overlaps. */
    fun toGridCells(screenRect: Rect): List<GridCoordinate> {
        val result = mutableListOf<GridCoordinate>()
        val startCol = (screenRect.left / cellWidth).toInt().coerceAtLeast(0)
        val endCol = (screenRect.right / cellWidth).toInt().coerceAtMost(columns - 1)
        val startRow = (screenRect.top / cellHeight).toInt().coerceAtLeast(0)
        val endRow = (screenRect.bottom / cellHeight).toInt().coerceAtMost(rows - 1)

        for (col in startCol..endCol)
            for (row in startRow..endRow)
                result.add(GridCoordinate(col, row))
        return result
    }

    /** Center pixel of a grid cell — used for gesture dispatch targeting. */
    fun cellCenter(col: Int, row: Int): Pair<Float, Float> {
        val bounds = cellBoundsFor(col, row)
        return Pair(bounds.centerX(), bounds.centerY())
    }

    fun cellCenter(coord: GridCoordinate) = cellCenter(coord.col, coord.row)

    // ─── Zone Management ──────────────────────────────────────────────────────

    /** Diplomat calls this to mark a zone after scanning the foreign app. */
    fun setZone(col: Int, row: Int, zone: ZoneClass) {
        if (col in 0 until columns && row in 0 until rows) {
            cells[col][row] = cells[col][row].copy(zone = zone)
        }
    }

    fun setZone(coord: GridCoordinate, zone: ZoneClass) = setZone(coord.col, coord.row, zone)

    /** Mark all cells overlapping a forbidden screen rect as FORBIDDEN. */
    fun markForbiddenRect(screenRect: Rect) {
        toGridCells(screenRect).forEach { setZone(it, ZoneClass.FORBIDDEN) }
    }

    /** Mark all cells overlapping a caution screen rect as CAUTION. */
    fun markCautionRect(screenRect: Rect) {
        toGridCells(screenRect).forEach { coord ->
            // Don't downgrade FORBIDDEN to CAUTION
            if (cells[coord.col][coord.row].zone != ZoneClass.FORBIDDEN)
                setZone(coord, ZoneClass.CAUTION)
        }
    }

    fun getZone(col: Int, row: Int): ZoneClass =
        if (col in 0 until columns && row in 0 until rows) cells[col][row].zone
        else ZoneClass.FORBIDDEN  // Out-of-bounds is always forbidden

    fun getZone(coord: GridCoordinate) = getZone(coord.col, coord.row)

    fun isSafe(col: Int, row: Int): Boolean = getZone(col, row) == ZoneClass.SAFE
    fun isSafe(coord: GridCoordinate): Boolean = isSafe(coord.col, coord.row)

    /** Full snapshot of the zone map for TranslationKey storage. */
    fun zoneMapSnapshot(): Map<String, ZoneClass> {
        val map = mutableMapOf<String, ZoneClass>()
        for (col in 0 until columns)
            for (row in 0 until rows)
                if (cells[col][row].zone != ZoneClass.SAFE)  // Only store non-default zones
                    map["$col,$row"] = cells[col][row].zone
        return map
    }

    /** Restore zone map from a persisted snapshot. */
    fun restoreZoneMap(snapshot: Map<String, ZoneClass>) {
        snapshot.forEach { (key, zone) ->
            val parts = key.split(",")
            if (parts.size == 2) {
                val col = parts[0].toIntOrNull() ?: return@forEach
                val row = parts[1].toIntOrNull() ?: return@forEach
                setZone(col, row, zone)
            }
        }
    }

    // ─── Safe Zone Queries ────────────────────────────────────────────────────

    /** All safe cells — used by Representative to show available placement spots. */
    fun safeCells(): List<GridCell> =
        cells.flatten().filter { it.zone == ZoneClass.SAFE }

    /** Find the nearest safe cell to a given screen point. */
    fun nearestSafeCell(screenX: Float, screenY: Float): GridCoordinate? {
        var nearest: GridCoordinate? = null
        var nearestDist = Float.MAX_VALUE

        for (col in 0 until columns) {
            for (row in 0 until rows) {
                if (cells[col][row].zone == ZoneClass.SAFE) {
                    val center = cellCenter(col, row)
                    val dx = center.first - screenX
                    val dy = center.second - screenY
                    val dist = dx * dx + dy * dy
                    if (dist < nearestDist) {
                        nearestDist = dist
                        nearest = GridCoordinate(col, row)
                    }
                }
            }
        }
        return nearest
    }

    // ─── Standard System Zone Registration ───────────────────────────────────

    /**
     * Mark standard Android system zones as FORBIDDEN.
     * Called by Diplomat during initial scan before app-specific zones are added.
     * These are known forbidden regardless of which app is active.
     */
    fun applyStandardSystemZones(metrics: DisplayMetrics) {
        val statusBarHeight = getStatusBarHeight()
        val navBarHeight = getNavBarHeight(metrics)

        // Status bar — top of screen
        if (statusBarHeight > 0)
            markForbiddenRect(Rect(0, 0, screenWidth, statusBarHeight))

        // Navigation bar — bottom of screen
        if (navBarHeight > 0)
            markForbiddenRect(Rect(0, screenHeight - navBarHeight, screenWidth, screenHeight))
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private fun cellBoundsFor(col: Int, row: Int): RectF {
        val left = col * cellWidth
        val top = row * cellHeight
        return RectF(left, top, left + cellWidth, top + cellHeight)
    }

    private fun getStatusBarHeight(): Int {
        // Standard Android status bar is approximately 24dp
        // Actual value retrieved at runtime via window insets in DiplomatRuntime
        return 72  // 24dp @ 3x density — placeholder, overridden at runtime
    }

    private fun getNavBarHeight(metrics: DisplayMetrics): Int {
        // Gesture nav = 0, 3-button nav ≈ 48dp
        // Overridden at runtime from WindowInsets
        return 0
    }

    companion object {

        /**
         * Select the appropriate grid resolution for a given constraint density.
         * Diplomat calls this after scanning the foreign app.
         */
        fun resolutionFor(density: ConstraintDensity): GridResolution = when (density) {
            ConstraintDensity.LOW    -> GridResolution.FINE
            ConstraintDensity.MEDIUM -> GridResolution.MEDIUM
            ConstraintDensity.HIGH   -> GridResolution.COARSE
        }

        /**
         * Estimate constraint density from accessibility tree scan results.
         * More restricted elements and gesture zones = higher density.
         */
        fun estimateConstraintDensity(
            totalElements: Int,
            restrictedElements: Int,
            gestureZoneCount: Int
        ): ConstraintDensity {
            if (totalElements == 0) return ConstraintDensity.MEDIUM

            val restrictionRatio = restrictedElements.toFloat() / totalElements
            val densityScore = restrictionRatio + (gestureZoneCount * 0.1f)

            return when {
                densityScore < 0.2f -> ConstraintDensity.LOW
                densityScore < 0.5f -> ConstraintDensity.MEDIUM
                else                -> ConstraintDensity.HIGH
            }
        }
    }
}
