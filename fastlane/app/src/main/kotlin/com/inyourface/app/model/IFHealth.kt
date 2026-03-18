/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * IFHealth.kt
 * Health model for Interactive Faces, their layers, and individual mapped objects.
 * Health belongs to the IF — not to a Persona. The Persona changes behavior.
 * The IF health reflects whether the mapping still reflects reality.
 *
 * Color codes signal severity at a glance.
 * Expression states signal character — a personality for each health condition.
 * Five expressions range from HAPPY (everything is great) to DISTRESSED (remap now).
 *
 * Drift accumulates as the underlying app updates or zones shift.
 * The TopGovernor owns drift calculation. IFHealth is the result.
 */

package com.inyourface.app.model

// ─── Health Color ─────────────────────────────────────────────────────────────

/**
 * Visual severity indicator. Used by the Manakit gallery and any health HUD.
 * Rendered as background color on IF cards and object indicators.
 */
enum class HealthColor(val hex: String, val label: String) {
    GREEN  ("#2E7D32", "Healthy"),        // All systems nominal — mapping is current
    YELLOW ("#F9A825", "Minor Drift"),    // Slight version mismatch or one stale zone
    ORANGE ("#E65100", "Needs Attention"),// Several elements degraded — audit recommended
    RED    ("#B71C1C", "Remap Needed"),   // IF is significantly out of sync — critical
    GRAY   ("#616161", "Inactive"),       // IF exists but is dormant / not being kept
    BLUE   ("#0277BD", "Just Audited")    // Freshly cleaned and verified — all clear
}

// ─── Health Expression ────────────────────────────────────────────────────────

/**
 * The "face" of an IF's health state.
 * Five states from happy and relaxed to nervous and distressed.
 * Rendered as icon expressions on IF cards in the Manakit gallery.
 * Deliberately chosen to be readable at a glance without reading any text.
 */
enum class HealthExpression(val label: String, val description: String) {
    HAPPY     ("Happy",     "Everything is great. Fully mapped and recently audited."),
    RELAXED   ("Relaxed",   "Doing well. Minor things could improve but nothing urgent."),
    UNEASY    ("Uneasy",    "Noticing some drift. Worth checking on soon."),
    NERVOUS   ("Nervous",   "Multiple issues active. Audit is recommended now."),
    DISTRESSED("Distressed","Critical. Remap or full audit required immediately.")
}

// ─── IF Health ────────────────────────────────────────────────────────────────

/**
 * The complete health snapshot for an Interactive Face.
 * Computed by TopGovernor. Stored inside InteractiveFace.
 * Updated whenever drift is detected or an audit is completed.
 *
 * [driftScore]         0.0 = perfect mapping, 1.0 = fully degraded
 * [flaggedObjectIds]   MappedElement IDs that have known issues
 * [flaggedLayerTypes]  Layers that need attention
 * [auditHistory]       Record of past audits for this IF
 */
data class IFHealth(
    val color: HealthColor,
    val expression: HealthExpression,
    val driftScore: Float,
    val lastAuditedAtMs: Long,
    val flaggedObjectIds: List<String> = emptyList(),
    val flaggedLayerTypes: List<IFLayerType> = emptyList(),
    val auditHistory: List<AuditRecord> = emptyList()
) {
    companion object {

        /**
         * Default health state for a freshly created or freshly audited IF.
         * Blue color — just built. Happy expression — no issues found yet.
         */
        fun fresh(): IFHealth = IFHealth(
            color             = HealthColor.BLUE,
            expression        = HealthExpression.HAPPY,
            driftScore        = 0f,
            lastAuditedAtMs   = System.currentTimeMillis()
        )

        /**
         * Inactive state for an IF that exists but is not kept in the Manakit.
         */
        fun inactive(): IFHealth = IFHealth(
            color             = HealthColor.GRAY,
            expression        = HealthExpression.RELAXED,
            driftScore        = 0f,
            lastAuditedAtMs   = 0L
        )

        /**
         * Derive a full health snapshot from a drift score.
         * Color and expression are computed together — they always stay in sync.
         */
        fun fromDriftScore(
            score: Float,
            lastAuditedAtMs: Long,
            flaggedObjects: List<String> = emptyList(),
            flaggedLayers: List<IFLayerType> = emptyList(),
            existingHistory: List<AuditRecord> = emptyList()
        ): IFHealth {
            val clampedScore = score.coerceIn(0f, 1f)
            val color = when {
                clampedScore <= 0f   -> HealthColor.BLUE
                clampedScore < 0.20f -> HealthColor.GREEN
                clampedScore < 0.40f -> HealthColor.YELLOW
                clampedScore < 0.70f -> HealthColor.ORANGE
                else                 -> HealthColor.RED
            }
            val expression = when {
                clampedScore <= 0f   -> HealthExpression.HAPPY
                clampedScore < 0.20f -> HealthExpression.RELAXED
                clampedScore < 0.40f -> HealthExpression.UNEASY
                clampedScore < 0.70f -> HealthExpression.NERVOUS
                else                 -> HealthExpression.DISTRESSED
            }
            return IFHealth(
                color           = color,
                expression      = expression,
                driftScore      = clampedScore,
                lastAuditedAtMs = lastAuditedAtMs,
                flaggedObjectIds  = flaggedObjects,
                flaggedLayerTypes = flaggedLayers,
                auditHistory    = existingHistory
            )
        }

        /**
         * Build a post-audit health snapshot.
         * Drift resets to zero, color goes BLUE, expression goes HAPPY.
         * The audit record is prepended to history.
         */
        fun postAudit(
            auditRecord: AuditRecord,
            existingHistory: List<AuditRecord> = emptyList()
        ): IFHealth = IFHealth(
            color             = HealthColor.BLUE,
            expression        = HealthExpression.HAPPY,
            driftScore        = 0f,
            lastAuditedAtMs   = auditRecord.performedAtMs,
            auditHistory      = listOf(auditRecord) + existingHistory
        )
    }
}

// ─── Audit System ─────────────────────────────────────────────────────────────

/**
 * The four levels of audit that the Diplomat can run on an IF.
 * TopGovernor recommends the appropriate level based on health color.
 * RED → FULL_REMAP, ORANGE → LAYER_RECALIBRATE, YELLOW → FUNCTION, GREEN → QUICK.
 */
enum class AuditType(val label: String, val description: String) {
    QUICK             ("Quick Check",       "Version hash comparison only. Fast."),
    FUNCTION          ("Function Audit",    "Verify element actions still respond."),
    LAYER_RECALIBRATE ("Layer Recalibrate", "Recheck layer placements and zone map."),
    FULL_REMAP        ("Full Remap",        "Diplomat rebuilds the IF from scratch.")
}

/**
 * A single completed audit record stored in IFHealth history.
 */
data class AuditRecord(
    val auditType: AuditType,
    val performedAtMs: Long,
    val driftBefore: Float,
    val driftAfter: Float,
    val resolvedObjectIds: List<String> = emptyList(),
    val resolvedLayerTypes: List<IFLayerType> = emptyList()
)
