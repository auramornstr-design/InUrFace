/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * IFLayer.kt
 * The four grid layers that compose an Interactive Face.
 * Each layer handles a different class of customization over the foreign app.
 *
 * METRIC   — position, size — where things live on screen
 * COSMETIC — color, shape, texture, label — how things look
 * DYNAMIC  — animation, motion envelopes, cadence — how things move
 * TRIGGER  — event relationships — what causes what
 *
 * Layers belong to the IF. Personas enable or disable them.
 * Health exists at the layer level — a degraded cosmetic layer doesn't mean
 * the metric layer is broken. Each can be audited independently.
 *
 * The DYNAMIC layer is the most expensive. LEAN persona disables it first.
 * The TRIGGER layer carries inter-element event logic. FULL persona runs all four.
 */

package com.inyourface.app.model

// ─── Layer Type ───────────────────────────────────────────────────────────────

enum class IFLayerType(val label: String, val description: String) {
    METRIC  ("Metric",   "Position and size — where mapped objects live on screen."),
    COSMETIC("Cosmetic", "Color, shape, texture, label — how mapped objects appear."),
    DYNAMIC ("Dynamic",  "Animation, motion envelopes, cadence — how objects move."),
    TRIGGER ("Trigger",  "Event relationships — what actions cause what responses.")
}

// ─── Layer State ──────────────────────────────────────────────────────────────

/**
 * A single layer's runtime state within an Interactive Face.
 *
 * [isEnabled]  Controlled by the active Persona. Layer exists but may be paused.
 * [health]     Layer-level health — a degraded DYNAMIC layer doesn't affect METRIC.
 * [metadata]   Extensible key/value bag for layer-specific properties.
 *              Example: METRIC metadata might hold preferred grid resolution override.
 *              Example: DYNAMIC metadata might hold max frame rate cap.
 */
data class IFLayer(
    val type: IFLayerType,
    val isEnabled: Boolean = true,
    val health: IFHealth = IFHealth.fresh(),
    val metadata: Map<String, String> = emptyMap()
) {
    companion object {

        /**
         * Returns a complete default layer set — all four layers enabled, all healthy.
         * Used when a new InteractiveFace is created.
         */
        fun defaultSet(): Map<IFLayerType, IFLayer> =
            IFLayerType.values().associateWith { IFLayer(type = it) }

        /**
         * Returns the layer set for the LEAN persona:
         * Only METRIC is active — minimal footprint, lowest battery cost.
         */
        fun leanSet(): Map<IFLayerType, IFLayer> =
            IFLayerType.values().associateWith { type ->
                IFLayer(type = type, isEnabled = type == IFLayerType.METRIC)
            }

        /**
         * Returns the layer set for the BALANCED persona:
         * METRIC, COSMETIC, TRIGGER active — DYNAMIC disabled.
         */
        fun balancedSet(): Map<IFLayerType, IFLayer> =
            IFLayerType.values().associateWith { type ->
                IFLayer(type = type, isEnabled = type != IFLayerType.DYNAMIC)
            }

        /**
         * Returns the layer set for the FULL persona:
         * All four layers active.
         */
        fun fullSet(): Map<IFLayerType, IFLayer> =
            IFLayerType.values().associateWith { type -> IFLayer(type = type, isEnabled = true) }
    }
}
