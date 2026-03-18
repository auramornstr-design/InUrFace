/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * TranslationKey.kt
 * The shared contract between RepresentativeRuntime and DiplomatRuntime.
 * Built once per foreign app version. Refreshed only on app update detection.
 * This is the only data structure both runtimes fully understand.
 *
 * The Representative reads it to know what capabilities exist and where elements live.
 * The Diplomat reads it to know what screen operations to execute and what is forbidden.
 * Neither runtime owns this exclusively — it is the bridge.
 */

package com.inyourface.app.model

import android.graphics.Rect
import com.google.gson.Gson
import com.google.gson.GsonBuilder

// ─── Grid Zone Classification ─────────────────────────────────────────────────

/**
 * What a grid cell is allowed to do.
 * The Diplomat writes this. The Representative renders it as color.
 */
enum class ZoneClass {
    SAFE,        // Green — usable for overlay button placement
    FORBIDDEN,   // Red   — system gesture zone, nav bar, or security-restricted
    CAUTION,     // Gray  — foreign app critical UI, occlusion not recommended
    OCCUPIED     // Blue  — an overlay element already lives here
}

// ─── Motion Signature ─────────────────────────────────────────────────────────

/**
 * A lightweight recorded delta sequence from Teach Mode capture.
 * Not raw video — just bounding box changes over time and state transitions.
 * The Diplomat records this; the Representative can display its metadata.
 */
data class MotionSignature(
    val elementId: String,
    val capturedAtMs: Long,
    val boundingDeltas: List<BoundingDelta>,   // Spatial movement over time
    val stateTransitions: List<StateTransition>, // Visual state changes over time
    val dominantGestureType: GestureType
)

data class BoundingDelta(
    val timestampMs: Long,
    val dLeft: Int,
    val dTop: Int,
    val dRight: Int,
    val dBottom: Int
)

data class StateTransition(
    val timestampMs: Long,
    val fromState: ElementState,
    val toState: ElementState
)

enum class ElementState {
    DEFAULT, PRESSED, FOCUSED, DISABLED, LOADING, CHECKED, UNCHECKED
}

// ─── Mapped Element ───────────────────────────────────────────────────────────

/**
 * A single UI element from the foreign app that has been mapped and understood.
 * The Diplomat creates this. The Representative displays it.
 */
data class MappedElement(
    val id: String,                                 // Stable identifier for this element
    val foreignAppId: String,                       // Which app this came from
    val contentDescription: String?,                // Accessibility description if available
    val screenBounds: Rect,                         // Actual screen bounds at capture time
    val gridCells: List<GridCoordinate>,            // Grid cells this element occupies
    val availableCapabilities: Set<CapabilityType>, // What the Diplomat allows for this element
    val capabilityStates: Map<Int, CapabilityState>,// Current capability values (keyed by capability id)
    val motionSignature: MotionSignature?,          // Recorded from Teach Mode, null if not yet taught
    val constraintFlags: Int = 0                    // Bitfield for special constraint conditions
) {
    companion object {
        // Constraint flag bits
        const val FLAG_TOUCH_FILTERED   = 0x01 // filterTouchesWhenObscured is set on this element
        const val FLAG_SECURITY_LOCKED  = 0x02 // Element is in a security-sensitive region
        const val FLAG_ANIMATION_ACTIVE = 0x04 // Element is currently animated
        const val FLAG_STATE_VOLATILE   = 0x08 // Element state changes rapidly
    }
}

data class GridCoordinate(val col: Int, val row: Int)

// ─── Translation Key ──────────────────────────────────────────────────────────

/**
 * The complete contract for a single foreign app.
 * Persisted to disk. Invalidated and rebuilt when [appVersionHash] changes.
 */
data class TranslationKey(
    val foreignPackageName: String,             // e.g. "com.openai.chatgpt"
    val appVersionHash: String,                 // Hash of versionCode + versionName
    val createdAtMs: Long,
    val lastRefreshedAtMs: Long,
    val gridResolution: GridResolution,         // How fine the grid is for this app
    val constraintDensity: ConstraintDensity,   // The app's overall restriction level
    val gridZoneMap: Map<String, ZoneClass>,    // "col,row" → ZoneClass
    val mappedElements: Map<String, MappedElement>, // elementId → MappedElement
    val forbiddenRegions: List<Rect>,           // Screen rects the Diplomat will never enter
    val metadata: AppMetadata
) {
    /** Stable key for a grid cell in the zone map. */
    fun zoneKey(col: Int, row: Int) = "$col,$row"

    fun zoneAt(col: Int, row: Int): ZoneClass =
        gridZoneMap[zoneKey(col, row)] ?: ZoneClass.SAFE

    fun isKeyStale(currentVersionHash: String): Boolean =
        appVersionHash != currentVersionHash

    fun serialize(): String = gson.toJson(this)

    companion object {
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        fun deserialize(json: String): TranslationKey =
            gson.fromJson(json, TranslationKey::class.java)

        fun empty(packageName: String): TranslationKey = TranslationKey(
            foreignPackageName = packageName,
            appVersionHash = "",
            createdAtMs = System.currentTimeMillis(),
            lastRefreshedAtMs = System.currentTimeMillis(),
            gridResolution = GridResolution.MEDIUM,
            constraintDensity = ConstraintDensity.MEDIUM,
            gridZoneMap = emptyMap(),
            mappedElements = emptyMap(),
            forbiddenRegions = emptyList(),
            metadata = AppMetadata(packageName, "", 0)
        )
    }
}

data class AppMetadata(
    val packageName: String,
    val appLabel: String,
    val versionCode: Long
)

enum class GridResolution(val columns: Int, val rows: Int) {
    FINE(24, 36),    // Flexible apps — lots of placement granularity
    MEDIUM(16, 24),  // Mid-range constraint density
    COARSE(10, 15)   // Heavily restricted apps — bigger cells, clearer constraint map
}

enum class ConstraintDensity {
    LOW,    // Few restrictions — fine grid
    MEDIUM, // Moderate — medium grid
    HIGH    // Many restrictions — coarse grid
}
