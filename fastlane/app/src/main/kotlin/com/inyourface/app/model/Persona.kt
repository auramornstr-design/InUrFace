/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * Persona.kt
 * A behavior mode applied to an Interactive Face.
 *
 * Personas do NOT change the IF mapping.
 * They change how much of the IF is expressed — which layers run, at what cost.
 *
 * System personas (LEAN, BALANCED, FULL) are managed automatically by TopGovernor.
 * Battery level degrades the active persona toward LEAN. Free users have no control
 * over persona types — the system picks the appropriate one.
 *
 * Plus and Pro users unlock CUSTOM personas — they can define their own layer
 * configurations, name them, and assign battery thresholds manually.
 *
 * Health belongs to the IF, not the persona.
 * Switching personas never changes health state.
 */

package com.inyourface.app.model

import java.util.UUID

// ─── Persona Type ─────────────────────────────────────────────────────────────

/**
 * The four types of persona.
 * LEAN / BALANCED / FULL are system-managed and always present on every IF.
 * CUSTOM is Plus/Pro only — user-defined.
 */
enum class PersonaType(val label: String) {
    LEAN    ("Lean"),     // Minimal — METRIC layer only, lowest battery cost
    BALANCED("Balanced"), // Standard — METRIC + COSMETIC + TRIGGER active
    FULL    ("Full"),     // Maximum — all four layers active, full monitoring
    CUSTOM  ("Custom")    // Plus/Pro only — user-defined layer and threshold config
}

// ─── Persona ──────────────────────────────────────────────────────────────────

/**
 * A complete behavior configuration for a specific IF.
 *
 * [id]               Stable identifier. System personas use fixed IDs (see companion).
 * [name]             Display name. System personas have fixed names. Custom can be anything.
 * [type]             The persona category.
 * [enabledLayers]    Which IFLayerTypes are active when this persona is running.
 * [isSystemManaged]  True for LEAN/BALANCED/FULL — TopGovernor controls activation.
 * [batteryThreshold] Auto-activate at or below this battery percentage.
 *                    LEAN activates at 20%, BALANCED at 40%, FULL has no threshold.
 *                    Custom personas can have any threshold or none.
 */
data class Persona(
    val id: String,
    val name: String,
    val type: PersonaType,
    val enabledLayers: Set<IFLayerType>,
    val isSystemManaged: Boolean,
    val batteryThreshold: Int? = null,
    val notes: String = ""
) {
    companion object {

        // Fixed IDs for the three system personas — stable across all IFs
        const val ID_LEAN     = "persona_system_lean"
        const val ID_BALANCED = "persona_system_balanced"
        const val ID_FULL     = "persona_system_full"

        /**
         * The LEAN system persona.
         * METRIC layer only. Activated at or below 20% battery.
         * Lowest battery and performance cost.
         */
        fun lean(): Persona = Persona(
            id               = ID_LEAN,
            name             = "Lean",
            type             = PersonaType.LEAN,
            enabledLayers    = setOf(IFLayerType.METRIC),
            isSystemManaged  = true,
            batteryThreshold = 20
        )

        /**
         * The BALANCED system persona.
         * METRIC + COSMETIC + TRIGGER active. DYNAMIC disabled.
         * Activated at or below 40% battery when FULL is current.
         */
        fun balanced(): Persona = Persona(
            id               = ID_BALANCED,
            name             = "Balanced",
            type             = PersonaType.BALANCED,
            enabledLayers    = setOf(
                IFLayerType.METRIC,
                IFLayerType.COSMETIC,
                IFLayerType.TRIGGER
            ),
            isSystemManaged  = true,
            batteryThreshold = 40
        )

        /**
         * The FULL system persona.
         * All four layers active. No battery threshold — this is the default state.
         * System degrades away from this as battery drops.
         */
        fun full(): Persona = Persona(
            id               = ID_FULL,
            name             = "Full",
            type             = PersonaType.FULL,
            enabledLayers    = IFLayerType.values().toSet(),
            isSystemManaged  = true,
            batteryThreshold = null
        )

        /**
         * The complete set of system-managed personas attached to every IF.
         * Returned in degradation order: full first, then balanced, then lean.
         */
        fun systemDefaults(): List<Persona> = listOf(full(), balanced(), lean())

        /**
         * Create a new custom persona — Plus/Pro only.
         * Caller supplies name, layers, and optional battery threshold.
         */
        fun createCustom(
            name: String,
            enabledLayers: Set<IFLayerType>,
            batteryThreshold: Int? = null,
            notes: String = ""
        ): Persona = Persona(
            id               = "persona_custom_${UUID.randomUUID()}",
            name             = name,
            type             = PersonaType.CUSTOM,
            enabledLayers    = enabledLayers,
            isSystemManaged  = false,
            batteryThreshold = batteryThreshold,
            notes            = notes
        )
    }
}
