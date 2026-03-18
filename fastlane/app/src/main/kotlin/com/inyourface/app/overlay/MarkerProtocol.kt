/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * MarkerProtocol.kt — Phase 4 update
 * Adds markers to support the IF/Manakit/Governor upgrade:
 *
 * New Representative → Diplomat:
 *   AutoDeployActivate   — Governor has found a kept IF for this app; load it
 *   AuditRequest         — User or Governor requests an audit on a specific IF
 *
 * New Diplomat → Representative:
 *   AuditComplete        — Diplomat finished an audit; includes before/after drift
 *   MappingJobComplete   — Diplomat finished mapping a queued job; includes new faceId
 *
 * Phase 3 markers preserved in full — no regressions.
 * TeachModeActivate still carries real screen X/Y coordinates.
 * TeachModeComplete still includes captured bounds and label.
 * TeachModeFailed still signals clear failure reasons.
 */

package com.inyourface.app.overlay

import com.inyourface.app.model.AuditType
import com.inyourface.app.model.CapabilityType
import com.inyourface.app.model.CapabilityValue
import com.inyourface.app.model.GridCoordinate
import com.inyourface.app.model.GestureType
import com.inyourface.app.model.IFLayerType

sealed class OverlayMarker {
    abstract val id: String
    abstract val placedAtMs: Long

    // ─── Representative → Diplomat ────────────────────────────────────────────

    data class CustomizeModeActivate(
        override val id: String,
        override val placedAtMs: Long
    ) : OverlayMarker()

    data class CustomizeModeCommit(
        override val id: String,
        override val placedAtMs: Long
    ) : OverlayMarker()

    data class CustomizeModeCancel(
        override val id: String,
        override val placedAtMs: Long
    ) : OverlayMarker()

    /** Phase 3: real screen coordinates from user's actual tap on the foreign app element. */
    data class TeachModeActivate(
        override val id: String,
        override val placedAtMs: Long,
        val screenX: Float,
        val screenY: Float,
        val sourceLabel: String = ""
    ) : OverlayMarker()

    data class TeachModeCancel(
        override val id: String,
        override val placedAtMs: Long
    ) : OverlayMarker()

    data class CapabilityChangeRequest(
        override val id: String,
        override val placedAtMs: Long,
        val elementId: String,
        val capabilityType: CapabilityType,
        val newValue: CapabilityValue
    ) : OverlayMarker()

    data class ProxyButtonTap(
        override val id: String,
        override val placedAtMs: Long,
        val elementId: String,
        val gestureOverride: GestureType? = null
    ) : OverlayMarker()

    data class PreScoutRequest(
        override val id: String,
        override val placedAtMs: Long,
        val targetPackage: String
    ) : OverlayMarker()

    /**
     * Phase 4: Governor has confirmed a kept IF exists for this app space.
     * Diplomat loads the IF's element overrides and active persona layer config
     * into the live OverlayCanvas without waiting for user action.
     */
    data class AutoDeployActivate(
        override val id: String,
        override val placedAtMs: Long,
        val faceId: String,
        val packageName: String,
        val activePersonaId: String
    ) : OverlayMarker()

    /**
     * Phase 4: Request an audit on a specific IF.
     * Representative places this on behalf of the user or Governor.
     * Diplomat runs the appropriate audit level and emits AuditComplete.
     */
    data class AuditRequest(
        override val id: String,
        override val placedAtMs: Long,
        val faceId: String,
        val packageName: String,
        val auditType: AuditType
    ) : OverlayMarker()

    // ─── Diplomat → Representative ────────────────────────────────────────────

    data class GridReady(
        override val id: String,
        override val placedAtMs: Long,
        val requestId: String,
        val zoneSnapshot: Map<String, String>
    ) : OverlayMarker()

    /** Phase 3: includes captured bounds and label so UI can show confirmation. */
    data class TeachModeComplete(
        override val id: String,
        override val placedAtMs: Long,
        val requestId: String,
        val learnedElementId: String,
        val learnedCells: List<GridCoordinate>,
        val capturedLabel: String,
        val capturedBoundsLeft: Int,
        val capturedBoundsTop: Int,
        val capturedBoundsRight: Int,
        val capturedBoundsBottom: Int
    ) : OverlayMarker()

    data class TeachModeFailed(
        override val id: String,
        override val placedAtMs: Long,
        val requestId: String,
        val reason: TeachFailReason
    ) : OverlayMarker()

    data class CapabilityRejected(
        override val id: String,
        override val placedAtMs: Long,
        val requestId: String,
        val reason: RejectionReason
    ) : OverlayMarker()

    data class CapabilityAccepted(
        override val id: String,
        override val placedAtMs: Long,
        val requestId: String,
        val elementId: String,
        val capabilityType: CapabilityType,
        val acceptedValue: CapabilityValue
    ) : OverlayMarker()

    data class TranslationKeyStale(
        override val id: String,
        override val placedAtMs: Long,
        val affectedPackage: String
    ) : OverlayMarker()

    data class PreScoutComplete(
        override val id: String,
        override val placedAtMs: Long,
        val requestId: String,
        val targetPackage: String
    ) : OverlayMarker()

    /**
     * Phase 4: Diplomat finished an audit on a specific IF.
     * Carries before/after drift and lists of resolved objects and layers.
     * TopGovernor consumes this to update IFHealth and record AuditRecord.
     */
    data class AuditComplete(
        override val id: String,
        override val placedAtMs: Long,
        val requestId: String,
        val faceId: String,
        val packageName: String,
        val auditType: AuditType,
        val driftBefore: Float,
        val resolvedObjectIds: List<String> = emptyList(),
        val resolvedLayerTypes: List<IFLayerType> = emptyList()
    ) : OverlayMarker()

    /**
     * Phase 4: Diplomat finished mapping a job from the MappingQueue.
     * Governor receives this, marks the job COMPLETE, and signals the UI.
     */
    data class MappingJobComplete(
        override val id: String,
        override val placedAtMs: Long,
        val jobId: String,
        val packageName: String,
        val createdFaceId: String
    ) : OverlayMarker()
}

// ─── Fail and Rejection Enums ─────────────────────────────────────────────────

enum class TeachFailReason {
    NO_NODE_AT_COORDINATES,
    NODE_SECURITY_LOCKED,
    APP_NOT_ACTIVE,
    CAPTURE_TIMEOUT
}

enum class RejectionReason {
    ZONE_FORBIDDEN,
    SECURITY_RESTRICTED,
    GESTURE_BLOCKED,
    CAPABILITY_NOT_AVAILABLE,
    APP_STATE_INCOMPATIBLE,
    TRANSLATION_KEY_STALE
}

// ─── Marker Surface ───────────────────────────────────────────────────────────

class MarkerSurface {
    private val _markers = java.util.concurrent.ConcurrentHashMap<String, OverlayMarker>()

    fun place(marker: OverlayMarker)   { _markers[marker.id] = marker }
    fun respond(marker: OverlayMarker) { _markers[marker.id] = marker }
    fun clear(markerId: String)        { _markers.remove(markerId) }

    fun pendingRequests(): List<OverlayMarker> =
        _markers.values.filter { it.isRepresentativeRequest() }.sortedBy { it.placedAtMs }

    fun pendingResponses(): List<OverlayMarker> =
        _markers.values.filter { it.isDiplomatResponse() }.sortedBy { it.placedAtMs }

    fun purgeStale(thresholdMs: Long = 8000L) {
        val cutoff = System.currentTimeMillis() - thresholdMs
        _markers.entries.removeIf { it.value.placedAtMs < cutoff }
    }

    fun snapshot(): List<OverlayMarker> = _markers.values.toList()
}

// ─── Classification Extensions ────────────────────────────────────────────────

fun OverlayMarker.isRepresentativeRequest(): Boolean = when (this) {
    is OverlayMarker.CustomizeModeActivate,
    is OverlayMarker.CustomizeModeCommit,
    is OverlayMarker.CustomizeModeCancel,
    is OverlayMarker.TeachModeActivate,
    is OverlayMarker.TeachModeCancel,
    is OverlayMarker.CapabilityChangeRequest,
    is OverlayMarker.ProxyButtonTap,
    is OverlayMarker.PreScoutRequest,
    is OverlayMarker.AutoDeployActivate,
    is OverlayMarker.AuditRequest         -> true
    else                                  -> false
}

fun OverlayMarker.isDiplomatResponse(): Boolean = when (this) {
    is OverlayMarker.GridReady,
    is OverlayMarker.TeachModeComplete,
    is OverlayMarker.TeachModeFailed,
    is OverlayMarker.CapabilityRejected,
    is OverlayMarker.CapabilityAccepted,
    is OverlayMarker.TranslationKeyStale,
    is OverlayMarker.PreScoutComplete,
    is OverlayMarker.AuditComplete,
    is OverlayMarker.MappingJobComplete   -> true
    else                                  -> false
}

fun generateMarkerId(): String = java.util.UUID.randomUUID().toString()
