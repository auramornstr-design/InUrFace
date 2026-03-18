/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * RepresentativeRuntime.kt — Phase 4 update
 * The manager-side foreground service. Brokers between the UI and DiplomatRuntime.
 *
 * Phase 3 preserved:
 *   - activateTeachMode() triggers the flow with screenX=0, screenY=0
 *   - Diplomat mounts TeachModeOverlay and waits for real user tap
 *   - TeachModeFailed is handled and relayed to UI via broadcast
 *
 * Phase 4 additions:
 *   - Receives ACTION_AUTO_DEPLOY_READY from TopGovernor
 *     → places AutoDeployActivate on the MarkerSurface for Diplomat to execute
 *   - Receives ACTION_AUDIT_SUGGESTED from TopGovernor
 *     → relays to UI via broadcast; UI decides whether to confirm
 *   - Receives ACTION_PERSONA_CHANGED from TopGovernor
 *     → relays to UI via broadcast so active persona indicator updates
 *   - Receives ACTION_MAPPING_JOB_READY from TopGovernor
 *     → no action needed here; Diplomat handles via MarkerSurface broadcast path
 *   - handleAuditComplete: receives AuditComplete from Diplomat, broadcasts to UI
 *   - handleMappingJobComplete: marks job complete in MappingQueue, broadcasts to UI
 *   - requestAudit(): public API for UI to trigger an audit on a specific IF
 *   - requestManakitKeep(): keep or release an IF in the Manakit
 *   - requestMappingJob(): enqueue an app for interface mapping
 */

package com.inyourface.app.representative

import android.app.*
import android.content.*
import android.os.Build
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.inyourface.app.InYourFaceApp
import com.inyourface.app.governor.TopGovernor
import com.inyourface.app.model.*
import com.inyourface.app.overlay.*
import kotlinx.coroutines.*

class RepresentativeRuntime : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val markerSurface = InYourFaceApp.markerSurface
    private var pendingCallbacks = mutableMapOf<String, (OverlayMarker) -> Unit>()

    private var isCustomizeModeActive = false
    private var isTeachModeActive = false

    // ─── Governor Receivers ───────────────────────────────────────────────────

    private val autoDeployReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val faceId          = intent.getStringExtra(TopGovernor.EXTRA_FACE_ID) ?: return
            val packageName     = intent.getStringExtra(TopGovernor.EXTRA_PACKAGE) ?: return
            val activePersonaId = intent.getStringExtra(TopGovernor.EXTRA_ACTIVE_PERSONA_ID) ?: ""
            scope.launch { triggerAutoDeploy(faceId, packageName, activePersonaId) }
        }
    }

    private val auditSuggestedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val faceId      = intent.getStringExtra(TopGovernor.EXTRA_FACE_ID) ?: return
            val packageName = intent.getStringExtra(TopGovernor.EXTRA_PACKAGE) ?: return
            val auditType   = intent.getStringExtra(TopGovernor.EXTRA_AUDIT_TYPE) ?: return
            // Relay suggestion to UI — UI decides whether to confirm and execute
            sendBroadcast(Intent(ACTION_AUDIT_SUGGESTED)
                .putExtra(EXTRA_FACE_ID, faceId)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_AUDIT_TYPE, auditType))
        }
    }

    private val personaChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val faceId      = intent.getStringExtra(TopGovernor.EXTRA_FACE_ID) ?: return
            val personaType = intent.getStringExtra(TopGovernor.EXTRA_PERSONA_TYPE) ?: return
            val personaId   = intent.getStringExtra(TopGovernor.EXTRA_PERSONA_ID) ?: return
            sendBroadcast(Intent(ACTION_PERSONA_CHANGED)
                .putExtra(EXTRA_FACE_ID, faceId)
                .putExtra(EXTRA_PERSONA_ID, personaId)
                .putExtra(EXTRA_PERSONA_TYPE, personaType))
        }
    }

    private val healthUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val faceId      = intent.getStringExtra(TopGovernor.EXTRA_FACE_ID) ?: return
            val packageName = intent.getStringExtra(TopGovernor.EXTRA_PACKAGE) ?: return
            val color       = intent.getStringExtra(TopGovernor.EXTRA_HEALTH_COLOR) ?: return
            val expression  = intent.getStringExtra(TopGovernor.EXTRA_HEALTH_EXPRESSION) ?: return
            sendBroadcast(Intent(ACTION_HEALTH_UPDATED)
                .putExtra(EXTRA_FACE_ID, faceId)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_HEALTH_COLOR, color)
                .putExtra(EXTRA_HEALTH_EXPRESSION, expression))
        }
    }

    // ─── Service Lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        startResponseObserver()
        registerReceiver(autoDeployReceiver,     IntentFilter(TopGovernor.ACTION_AUTO_DEPLOY_READY))
        registerReceiver(auditSuggestedReceiver, IntentFilter(TopGovernor.ACTION_AUDIT_SUGGESTED))
        registerReceiver(personaChangedReceiver, IntentFilter(TopGovernor.ACTION_PERSONA_CHANGED))
        registerReceiver(healthUpdatedReceiver,  IntentFilter(TopGovernor.ACTION_HEALTH_UPDATED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder = RepresentativeBinder()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { unregisterReceiver(autoDeployReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(auditSuggestedReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(personaChangedReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(healthUpdatedReceiver) } catch (e: Exception) {}
    }

    // ─── Response Observer ────────────────────────────────────────────────────

    private fun startResponseObserver() {
        scope.launch {
            while (isActive) {
                markerSurface.pendingResponses().forEach { handleResponse(it) }
                delay(32L)
            }
        }
    }

    private fun handleResponse(marker: OverlayMarker) {
        val requestId = when (marker) {
            is OverlayMarker.GridReady           -> marker.requestId
            is OverlayMarker.TeachModeComplete   -> marker.requestId
            is OverlayMarker.TeachModeFailed     -> marker.requestId
            is OverlayMarker.CapabilityRejected  -> marker.requestId
            is OverlayMarker.CapabilityAccepted  -> marker.requestId
            is OverlayMarker.PreScoutComplete    -> marker.requestId
            is OverlayMarker.AuditComplete       -> marker.requestId   // Phase 4
            is OverlayMarker.MappingJobComplete  -> marker.jobId        // Phase 4 (uses jobId not requestId)
            else -> null
        }
        requestId?.let { pendingCallbacks.remove(it) }?.invoke(marker)

        when (marker) {
            is OverlayMarker.TranslationKeyStale ->
                sendBroadcast(Intent(ACTION_KEY_STALE)
                    .putExtra(EXTRA_PACKAGE, marker.affectedPackage))

            is OverlayMarker.TeachModeComplete -> {
                isTeachModeActive = false
                sendBroadcast(Intent(ACTION_TEACH_COMPLETE)
                    .putExtra(EXTRA_ELEMENT_ID, marker.learnedElementId)
                    .putExtra(EXTRA_CAPTURED_LABEL, marker.capturedLabel))
            }

            is OverlayMarker.TeachModeFailed -> {
                isTeachModeActive = false
                sendBroadcast(Intent(ACTION_TEACH_FAILED)
                    .putExtra(EXTRA_TEACH_FAIL_REASON, marker.reason.name))
            }

            is OverlayMarker.GridReady ->
                sendBroadcast(Intent(ACTION_GRID_READY))

            is OverlayMarker.CapabilityRejected ->
                sendBroadcast(Intent(ACTION_CAPABILITY_REJECTED)
                    .putExtra(EXTRA_REJECTION_REASON, marker.reason.name))

            // Phase 4 — Audit complete: relay result to UI
            is OverlayMarker.AuditComplete ->
                sendBroadcast(Intent(ACTION_AUDIT_COMPLETE)
                    .putExtra(EXTRA_FACE_ID, marker.faceId)
                    .putExtra(EXTRA_PACKAGE, marker.packageName)
                    .putExtra(EXTRA_AUDIT_TYPE, marker.auditType.name))

            // Phase 4 — Mapping job complete: update queue, signal UI
            is OverlayMarker.MappingJobComplete -> {
                scope.launch(Dispatchers.IO) {
                    MappingQueue.markComplete(
                        this@RepresentativeRuntime,
                        marker.jobId,
                        marker.createdFaceId
                    )
                }
                sendBroadcast(Intent(ACTION_MAPPING_JOB_COMPLETE)
                    .putExtra(EXTRA_JOB_ID, marker.jobId)
                    .putExtra(EXTRA_FACE_ID, marker.createdFaceId)
                    .putExtra(EXTRA_PACKAGE, marker.packageName))
            }

            else -> {}
        }
        markerSurface.clear(marker.id)
    }

    // ─── Auto-Deploy (Phase 4) ────────────────────────────────────────────────

    /**
     * Governor found a kept IF for the current app space.
     * Place an AutoDeployActivate marker so the Diplomat loads the IF's config
     * into the live OverlayCanvas.
     */
    private fun triggerAutoDeploy(faceId: String, packageName: String, activePersonaId: String) {
        markerSurface.place(OverlayMarker.AutoDeployActivate(
            id              = generateMarkerId(),
            placedAtMs      = System.currentTimeMillis(),
            faceId          = faceId,
            packageName     = packageName,
            activePersonaId = activePersonaId
        ))
        sendBroadcast(Intent(ACTION_AUTO_DEPLOY_ACTIVATED)
            .putExtra(EXTRA_FACE_ID, faceId)
            .putExtra(EXTRA_PACKAGE, packageName))
    }

    // ─── Customize Mode ───────────────────────────────────────────────────────

    fun activateCustomizeMode(onGridReady: (OverlayMarker.GridReady) -> Unit) {
        if (isCustomizeModeActive) return
        isCustomizeModeActive = true
        val id = generateMarkerId()
        pendingCallbacks[id] = { if (it is OverlayMarker.GridReady) onGridReady(it) }
        markerSurface.place(OverlayMarker.CustomizeModeActivate(id, System.currentTimeMillis()))
    }

    fun commitCustomizeMode() {
        if (!isCustomizeModeActive) return
        isCustomizeModeActive = false
        markerSurface.place(OverlayMarker.CustomizeModeCommit(generateMarkerId(), System.currentTimeMillis()))
    }

    fun cancelCustomizeMode() {
        if (!isCustomizeModeActive) return
        isCustomizeModeActive = false
        markerSurface.place(OverlayMarker.CustomizeModeCancel(generateMarkerId(), System.currentTimeMillis()))
    }

    // ─── Teach Mode (Phase 3) ─────────────────────────────────────────────────

    fun activateTeachMode(
        sourceLabel: String = "",
        onComplete: (OverlayMarker.TeachModeComplete) -> Unit,
        onFailed: (TeachFailReason) -> Unit = {}
    ) {
        if (isTeachModeActive) return
        isTeachModeActive = true
        val id = generateMarkerId()
        pendingCallbacks[id] = { marker ->
            when (marker) {
                is OverlayMarker.TeachModeComplete -> onComplete(marker)
                is OverlayMarker.TeachModeFailed   -> onFailed(marker.reason)
                else -> {}
            }
        }
        markerSurface.place(OverlayMarker.TeachModeActivate(
            id           = id,
            placedAtMs   = System.currentTimeMillis(),
            screenX      = 0f,
            screenY      = 0f,
            sourceLabel  = sourceLabel
        ))
    }

    fun cancelTeachMode() {
        if (!isTeachModeActive) return
        isTeachModeActive = false
        markerSurface.place(OverlayMarker.TeachModeCancel(generateMarkerId(), System.currentTimeMillis()))
    }

    // ─── Audit (Phase 4) ──────────────────────────────────────────────────────

    /**
     * UI-initiated audit request. Places AuditRequest on the surface for Diplomat.
     * onComplete is called when AuditComplete is received.
     */
    fun requestAudit(
        faceId: String,
        packageName: String,
        auditType: AuditType,
        onComplete: (OverlayMarker.AuditComplete) -> Unit = {}
    ) {
        val id = generateMarkerId()
        pendingCallbacks[id] = { if (it is OverlayMarker.AuditComplete) onComplete(it) }
        markerSurface.place(OverlayMarker.AuditRequest(
            id          = id,
            placedAtMs  = System.currentTimeMillis(),
            faceId      = faceId,
            packageName = packageName,
            auditType   = auditType
        ))
    }

    // ─── Manakit Operations (Phase 4) ─────────────────────────────────────────

    /**
     * Keep an IF in the Manakit.
     * Returns false if the kit is full (Free tier limit reached).
     */
    fun keepFaceInManakit(faceId: String): Boolean =
        ManakitStore.keepFace(this, faceId)

    /** Release an IF from the Manakit. Does not delete the IF itself. */
    fun releaseFaceFromManakit(faceId: String) =
        ManakitStore.releaseFace(this, faceId)

    /** Get the current Manakit state. */
    fun getManakit(): Manakit =
        ManakitStore.loadOrCreate(this)

    // ─── Mapping Queue (Phase 4) ──────────────────────────────────────────────

    /**
     * Queue a foreign app for interface mapping.
     * Returns the created MappingJob or null if the app is already queued.
     */
    fun requestMappingJob(
        packageName: String,
        appLabel: String,
        screenshotRef: String? = null
    ): MappingJob? {
        val manakit = ManakitStore.load(this)
        val priority = if (manakit?.tier == ManakitTier.PRO)
            MappingPriority.PRIORITY else MappingPriority.NORMAL
        return MappingQueue.enqueue(this, packageName, appLabel, priority, screenshotRef)
    }

    /** Get a snapshot of all mapping jobs for display in the UI queue. */
    fun getMappingQueueSnapshot(): List<MappingJob> =
        MappingQueue.snapshot(this)

    // ─── Capability Changes ───────────────────────────────────────────────────

    fun requestCapabilityChange(
        elementId: String,
        capability: CapabilityType,
        newValue: CapabilityValue,
        onResult: (accepted: Boolean, reason: RejectionReason?) -> Unit
    ) {
        val id = generateMarkerId()
        pendingCallbacks[id] = { marker ->
            when (marker) {
                is OverlayMarker.CapabilityAccepted -> onResult(true, null)
                is OverlayMarker.CapabilityRejected -> onResult(false, marker.reason)
                else -> onResult(false, null)
            }
        }
        markerSurface.place(OverlayMarker.CapabilityChangeRequest(
            id, System.currentTimeMillis(), elementId, capability, newValue
        ))
    }

    // ─── Pre-Scout ────────────────────────────────────────────────────────────

    fun requestPreScout(targetPackage: String, onComplete: (() -> Unit)? = null) {
        val id = generateMarkerId()
        pendingCallbacks[id] = { if (it is OverlayMarker.PreScoutComplete) onComplete?.invoke() }
        markerSurface.place(OverlayMarker.PreScoutRequest(id, System.currentTimeMillis(), targetPackage))
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val channelId = "inyourface_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "In Your Face", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Overlay is active" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("In Your Face").setContentText("Overlay active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build()
    }

    // ─── Binder ───────────────────────────────────────────────────────────────

    inner class RepresentativeBinder : Binder() {
        fun getRuntime(): RepresentativeRuntime = this@RepresentativeRuntime
    }

    // ─── Constants ────────────────────────────────────────────────────────────

    companion object {
        private const val NOTIFICATION_ID = 1001

        // Phase 3
        const val ACTION_GRID_READY          = "com.inyourface.GRID_READY"
        const val ACTION_TEACH_COMPLETE      = "com.inyourface.TEACH_COMPLETE"
        const val ACTION_TEACH_FAILED        = "com.inyourface.TEACH_FAILED"
        const val ACTION_CAPABILITY_REJECTED = "com.inyourface.CAPABILITY_REJECTED"
        const val ACTION_KEY_STALE           = "com.inyourface.KEY_STALE"

        // Phase 4
        const val ACTION_AUTO_DEPLOY_ACTIVATED = "com.inyourface.AUTO_DEPLOY_ACTIVATED"
        const val ACTION_AUDIT_SUGGESTED       = "com.inyourface.AUDIT_SUGGESTED"
        const val ACTION_AUDIT_COMPLETE        = "com.inyourface.AUDIT_COMPLETE"
        const val ACTION_PERSONA_CHANGED       = "com.inyourface.PERSONA_CHANGED"
        const val ACTION_HEALTH_UPDATED        = "com.inyourface.HEALTH_UPDATED"
        const val ACTION_MAPPING_JOB_COMPLETE  = "com.inyourface.MAPPING_JOB_COMPLETE"

        // Extras
        const val EXTRA_ELEMENT_ID         = "element_id"
        const val EXTRA_CAPTURED_LABEL     = "captured_label"
        const val EXTRA_TEACH_FAIL_REASON  = "teach_fail_reason"
        const val EXTRA_REJECTION_REASON   = "rejection_reason"
        const val EXTRA_PACKAGE            = "package_name"
        const val EXTRA_FACE_ID            = "face_id"
        const val EXTRA_AUDIT_TYPE         = "audit_type"
        const val EXTRA_PERSONA_ID         = "persona_id"
        const val EXTRA_PERSONA_TYPE       = "persona_type"
        const val EXTRA_HEALTH_COLOR       = "health_color"
        const val EXTRA_HEALTH_EXPRESSION  = "health_expression"
        const val EXTRA_JOB_ID             = "job_id"
    }
}
