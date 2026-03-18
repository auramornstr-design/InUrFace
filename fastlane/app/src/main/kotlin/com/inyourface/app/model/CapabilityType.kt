/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * CapabilityType.kt
 * Defines the eight core capability types that can be applied to any mapped UI element.
 * Identified internally by integer. Rendered by RepresentativeRuntime as color-coded boxes.
 * Executed by DiplomatRuntime as concrete system operations.
 */

package com.inyourface.app.model

import androidx.annotation.ColorInt

/**
 * Each capability type carries:
 *  - [id]          The stable integer identifier stored in TranslationKey
 *  - [label]       Human-readable name shown in Customize Mode
 *  - [colorHex]    UI color used by Representative for the capability box
 *  - [description] Tooltip / onboarding text
 */
enum class CapabilityType(
    val id: Int,
    val label: String,
    val colorHex: String,
    val description: String
) {

    /** Move the proxy button to any valid grid cell in the safe zone. */
    POSITION(
        id = 1,
        label = "Position",
        colorHex = "#2563A8",
        description = "Move this control to a different location on screen."
    ),

    /** Resize the proxy button within available grid space. */
    SIZE(
        id = 2,
        label = "Size",
        colorHex = "#1A7A1A",
        description = "Make this control larger or smaller."
    ),

    /** Apply a color tint or fill. Icon shape is preserved. */
    RECOLOR(
        id = 3,
        label = "Recolor",
        colorHex = "#E94560",
        description = "Change the color of this control."
    ),

    /** Change the text label displayed on or beneath the proxy button. */
    LABEL(
        id = 4,
        label = "Label",
        colorHex = "#7A5C1E",
        description = "Rename this control to something that makes sense to you."
    ),

    /** Adjust transparency of the proxy button. */
    OPACITY(
        id = 5,
        label = "Opacity",
        colorHex = "#555555",
        description = "Make this control more or less transparent."
    ),

    /** Change outline style, thickness, or color. */
    BORDER(
        id = 6,
        label = "Border",
        colorHex = "#1A7A7A",
        description = "Change the outline of this control."
    ),

    /** Switch the input event type: tap, long press, double tap, swipe. */
    ACTION_TYPE(
        id = 7,
        label = "Action",
        colorHex = "#7A1A7A",
        description = "Change how this control is activated."
    ),

    /** Reassign which foreign app element this proxy button triggers. */
    TARGET_BINDING(
        id = 8,
        label = "Target",
        colorHex = "#0F3460",
        description = "Point this control at a different element in the app."
    );

    companion object {
        fun fromId(id: Int): CapabilityType? = entries.find { it.id == id }

        /** All capability types available for a standard mappable element. */
        val ALL: Set<CapabilityType> = entries.toSet()

        /** Minimal set for read-only / restricted elements. */
        val COSMETIC_ONLY: Set<CapabilityType> = setOf(RECOLOR, LABEL, OPACITY, BORDER)

        /** Minimal set when only position can change. */
        val POSITION_ONLY: Set<CapabilityType> = setOf(POSITION)
    }
}

/**
 * The resolved state of a single capability on a specific mapped element.
 * Stored inside MappedElement. Diplomat reads this; Representative writes it.
 */
data class CapabilityState(
    val type: CapabilityType,
    val isAvailable: Boolean,
    val currentValue: CapabilityValue
)

/**
 * Union type for the different values a capability can hold.
 * Sealed so the Diplomat can pattern-match cleanly without casting soup.
 */
sealed class CapabilityValue {
    data class Position(val gridCol: Int, val gridRow: Int) : CapabilityValue()
    data class Size(val widthCells: Int, val heightCells: Int) : CapabilityValue()
    data class Recolor(val colorHex: String, val tintAlpha: Float = 1.0f) : CapabilityValue()
    data class Label(val text: String) : CapabilityValue()
    data class Opacity(val alpha: Float) : CapabilityValue()   // 0.0 – 1.0
    data class Border(val colorHex: String, val strokeDp: Float, val style: BorderStyle) : CapabilityValue()
    data class ActionType(val gesture: GestureType) : CapabilityValue()
    data class TargetBinding(val elementId: String) : CapabilityValue()
    object Unset : CapabilityValue()
}

enum class BorderStyle { NONE, SOLID, DASHED, ROUNDED }

enum class GestureType {
    SINGLE_TAP,
    LONG_PRESS,
    DOUBLE_TAP,
    SWIPE_UP,
    SWIPE_DOWN,
    SWIPE_LEFT,
    SWIPE_RIGHT
}
