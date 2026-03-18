/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * TopGovernor.kt
 * Standalone supervisor service for the entire Interactive Face ecosystem.
 *
 * The Diplomat maps. The Governor maintains.
 * The Diplomat writes a TranslationKey once. The Governor keeps the IF honest over time.
 *
 * Responsibilities:
 *   1. Auto-deploy  — When the user enters a foreign app space with a kept IF,
 *                     the Governor detects the match and signals the overlay to lay out.
 *   2. Health       — Tracks drift across all kept IFs. Degrades health when
 *                     a TranslationKey goes stale. Restores health after audits.
 *   3. Battery      — Monitors battery level and degrades active personas toward
 *                     LEAN as battery drops. Free users have no control over this.
 *   4. Audit mgmt   — Recommends and triggers the appropriate audit level when
 *                     an IF's health color crosses a threshold.
 *   5. Queue drain  — Watches the MappingQueue and signals the Diplomat to process
 *                     the next job when it becomes idle.
 *
 * Signal paths:
 *   Receives  ← broadcast: ACTION_KEY_STALE (from RepresentativeRuntime)
 *   Receives  ← broadcast: ACTION_TEACH_COMPLETE (confirm map + create IF)
 *   Receives  ← battery sticky intent
 *   Receives  ← onAppSpaceEntered() called by DiplomatRuntime via binder
 *   Emits     → broadcast: ACTION_HEALTH_UPDATED
 *   Emits     → broadcast: ACTION_AUTO_DEPLOY_READY
 *   Emits     → broadcast: ACTION_AUDIT_SUGGESTED
 *   Emits     → broadcast: ACTION_PERSONA_CHANGED
 *   Emits     → broadcast: ACTION_MAPPING_JOB_READY (tell Diplomat to process next job)
 */

package com.inyourface.app.governor

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import com.inyourface.app.InYourFaceApp
import com.inyourface.app.model.*
import com.inyourface.app.overlay.OverlayMarker
import com.inyourface.app.representative.RepresentativeRuntime
import kotlinx.coroutines.*

class TopGovernor : Service() {

    private val governorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val markerSurface = InYourFaceApp.markerSurface
    private val mainHandler   = Handler(Looper.getMainLooper())

    private var activePackage: String = ""
    private var batteryLevel:  Int    = 100

    // ─── Receivers ────────────────────────────────────────────────────────────

    private val keyStaleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.getStringExtra(RepresentativeRuntime.EXTRA_PACKAGE) ?: return
            governorScope.launch { handleTranslationKeyStale(pkg) }
        }
    }

    private val teachCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val elementId = intent.getStringExtra(RepresentativeRuntime.EXTRA_ELEMENT_ID) ?: return
            governorScope.launch { drainMappingQueue() }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                val newLevel = (level * 100 / scale)
                if (newLevel != batteryLevel) {
                    batteryLevel = newLevel
                    governorScope.launch { applyBatteryPersonaDegradation() }
                }
            }
        }
    }

    // ─── Service Lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerReceiver(keyStaleReceiver,    IntentFilter(RepresentativeRuntime.ACTION_KEY_STALE))
        registerReceiver(teachCompleteReceiver, IntentFilter(RepresentativeRuntime.ACTION_TEACH_COMPLETE))
        registerReceiver(batteryReceiver,     IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startGovernorLoop()
        log("TopGovernor started.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder = GovernorBinder()

    override fun onDestroy() {
        super.onDestroy()
        governorScope.cancel()
        try { unregisterReceiver(keyStaleReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(teachCompleteReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
        log("TopGovernor destroyed.")
    }

    // ─── Governor Loop ────────────────────────────────────────────────────────

    /**
     * Periodic background loop — polls for stale marker surface responses
     * and keeps the mapping queue moving when the Diplomat is idle.
     */
    private fun startGovernorLoop() {
        governorScope.launch {
            while (isActive) {
                // Catch any TranslationKeyStale markers that bypassed the broadcast
                markerSurface.pendingResponses()
                    .filterIsInstance<OverlayMarker.TranslationKeyStale>()
                    .forEach { handleTranslationKeyStale(it.affectedPackage) }
                delay(GOVERNOR_POLL_MS)
            }
        }
    }

    // ─── App Space Entry — Auto-Deploy ────────────────────────────────────────

    /**
     * Called by DiplomatRuntime (via binder) when the user switches into a foreign app.
     * Checks the Manakit for a kept IF matching the package and signals auto-deploy.
     * Also triggers a health check on all kept IFs for this package.
     */
    fun onAppSpaceEntered(packageName: String) {
        if (packageName == activePackage) return
        activePackage = packageName
        governorScope.launch {
            checkAutoDeployForPackage(packageName)
            checkHealthForPackage(packageName)
        }
    }

    private suspend fun checkAutoDeployForPackage(packageName: String) {
        val kit       = withContext(Dispatchers.IO) { ManakitStore.load(this@TopGovernor) } ?: return
        val allFaces  = withContext(Dispatchers.IO) {
            InteractiveFaceStore.loadAll(this@TopGovernor, packageName)
        }
        val keptFace  = kit.getKeptFaceForPackage(packageName, allFaces) ?: return
        withContext(Dispatchers.IO) {
            ManakitStore.recordAutoDeploy(this@TopGovernor, keptFace.id, packageName)
        }
        broadcastAutoDeployReady(keptFace)
        log("Auto-deploy signalled: IF '${keptFace.name}' for $packageName")
    }

    // ─── Health Monitoring ────────────────────────────────────────────────────

    private suspend fun checkHealthForPackage(packageName: String) {
        val faces = withContext(Dispatchers.IO) {
            InteractiveFaceStore.loadAll(this@TopGovernor, packageName)
        }
        faces.forEach { face ->
            evaluateAndApplyDrift(face)
        }
    }

    /**
     * Called when a TranslationKeyStale signal arrives for a package.
     * Increments drift on all kept IFs for that package. Suggests audit if needed.
     */
    private suspend fun handleTranslationKeyStale(packageName: String) {
        val faces = withContext(Dispatchers.IO) {
            InteractiveFaceStore.loadAll(this@TopGovernor, packageName)
        }
        val kit = withContext(Dispatchers.IO) { ManakitStore.load(this@TopGovernor) }

        faces.forEach { face ->
            // Only kept IFs accumulate active drift — inactive ones stay GRAY
            val isKept = kit?.keptFaceIds?.contains(face.id) == true
            if (!isKept) return@forEach

            val newDrift = (face.health.driftScore + DRIFT_INCREMENT_ON_STALE).coerceAtMost(1f)
            val newHealth = IFHealth.fromDriftScore(
                score            = newDrift,
                lastAuditedAtMs  = face.health.lastAuditedAtMs,
                flaggedObjects   = face.health.flaggedObjectIds,
                flaggedLayers    = face.health.flaggedLayerTypes,
                existingHistory  = face.health.auditHistory
            )
            withContext(Dispatchers.IO) {
                InteractiveFaceStore.updateHealth(this@TopGovernor, face.id, packageName, newHealth)
            }
            broadcastHealthUpdate(face.id, packageName, newHealth)
            suggestAuditIfNeeded(face, newHealth)
            log("Drift incremented for IF '${face.name}': ${face.health.driftScore} → $newDrift")
        }
    }

    private suspend fun evaluateAndApplyDrift(face: InteractiveFace) {
        // Called on app entry — checks whether the drift level already warrants action
        suggestAuditIfNeeded(face, face.health)
    }

    private fun suggestAuditIfNeeded(face: InteractiveFace, health: IFHealth) {
        val auditType = when (health.color) {
            HealthColor.RED    -> AuditType.FULL_REMAP
            HealthColor.ORANGE -> AuditType.LAYER_RECALIBRATE
            HealthColor.YELLOW -> AuditType.FUNCTION
            else               -> return  // GREEN or BLUE — no audit needed
        }
        broadcastAuditSuggested(face.id, face.foreignPackageName, auditType)
        log("Audit suggested: ${auditType.name} for IF '${face.name}'")
    }

    /**
     * Called by DiplomatRuntime (via binder) when an audit completes.
     * Resets drift, records audit history, restores health to BLUE/HAPPY.
     */
    fun onAuditComplete(
        faceId: String,
        packageName: String,
        auditType: AuditType,
        driftBefore: Float,
        resolvedObjectIds: List<String> = emptyList(),
        resolvedLayerTypes: List<IFLayerType> = emptyList()
    ) {
        governorScope.launch {
            val face = withContext(Dispatchers.IO) {
                InteractiveFaceStore.load(this@TopGovernor, faceId, packageName)
            } ?: return@launch

            val auditRecord = AuditRecord(
                auditType          = auditType,
                performedAtMs      = System.currentTimeMillis(),
                driftBefore        = driftBefore,
                driftAfter         = 0f,
                resolvedObjectIds  = resolvedObjectIds,
                resolvedLayerTypes = resolvedLayerTypes
            )
            val newHealth = IFHealth.postAudit(auditRecord, face.health.auditHistory)
            withContext(Dispatchers.IO) {
                InteractiveFaceStore.updateHealth(this@TopGovernor, faceId, packageName, newHealth)
            }
            broadcastHealthUpdate(faceId, packageName, newHealth)
            log("Audit complete for IF '${face.name}': health restored to BLUE/HAPPY")
        }
    }

    // ─── Battery Persona Degradation ──────────────────────────────────────────

    /**
     * Runs when battery level changes.
     * Iterates all IFs across all packages.
     * Finds the appropriate system persona for the current battery level.
     * Switches the active persona if it differs from current.
     * Free users have no say — system always controls this.
     */
    private suspend fun applyBatteryPersonaDegradation() {
        val allFaces = withContext(Dispatchers.IO) {
            InteractiveFaceStore.loadAllAcrossPackages(this@TopGovernor)
        }
        allFaces.forEach { face ->
            val targetPersona = resolvePersonaForBattery(face) ?: return@forEach
            if (face.activePersonaId == targetPersona.id) return@forEach

            withContext(Dispatchers.IO) {
                InteractiveFaceStore.updateActivePersona(
                    this@TopGovernor,
                    face.id,
                    face.foreignPackageName,
                    targetPersona.id
                )
            }
            broadcastPersonaChanged(face.id, face.foreignPackageName, targetPersona)
            log("Persona degraded for IF '${face.name}': → ${targetPersona.type.name} at battery $batteryLevel%")
        }
    }

    /**
     * Resolve which system persona should be active for the current battery level.
     * Returns null if the current persona is already the correct one.
     *
     * Degradation order: FULL → BALANCED (≤40%) → LEAN (≤20%)
     * Recovery order:    LEAN → BALANCED (>40%) → FULL (>40% if BALANCED is current and battery fine)
     */
    private fun resolvePersonaForBattery(face: InteractiveFace): Persona? {
        val systemPersonas = face.personas
            .filter { it.isSystemManaged }
            .sortedByDescending { it.batteryThreshold ?: -1 }

        // Find the highest-threshold persona whose threshold we are at or below
        val degradedTarget = systemPersonas
            .firstOrNull { p -> p.batteryThreshold != null && batteryLevel <= p.batteryThreshold!! }

        // If no threshold is triggered, target is FULL
        val target = degradedTarget
            ?: face.personas.firstOrNull { it.id == Persona.ID_FULL }
            ?: return null

        return if (target.id == face.activePersonaId) null else target
    }

    // ─── Mapping Queue Drain ──────────────────────────────────────────────────

    /**
     * Called after a teach/map completes, or periodically.
     * If there are pending mapping jobs, signals the Diplomat to process the next one.
     */
    private suspend fun drainMappingQueue() {
        val nextJob = withContext(Dispatchers.IO) {
            MappingQueue.dequeueNext(this@TopGovernor)
        } ?: return
        broadcastMappingJobReady(nextJob)
        log("Mapping job dispatched: ${nextJob.packageName} (${nextJob.priority})")
    }

    // ─── Broadcast Helpers ────────────────────────────────────────────────────

    private fun broadcastAutoDeployReady(face: InteractiveFace) {
        sendBroadcast(Intent(ACTION_AUTO_DEPLOY_READY)
            .putExtra(EXTRA_FACE_ID, face.id)
            .putExtra(EXTRA_PACKAGE, face.foreignPackageName)
            .putExtra(EXTRA_ACTIVE_PERSONA_ID, face.activePersonaId))
    }

    private fun broadcastHealthUpdate(faceId: String, packageName: String, health: IFHealth) {
        sendBroadcast(Intent(ACTION_HEALTH_UPDATED)
            .putExtra(EXTRA_FACE_ID, faceId)
            .putExtra(EXTRA_PACKAGE, packageName)
            .putExtra(EXTRA_HEALTH_COLOR, health.color.name)
            .putExtra(EXTRA_HEALTH_EXPRESSION, health.expression.name)
            .putExtra(EXTRA_DRIFT_SCORE, health.driftScore))
    }

    private fun broadcastAuditSuggested(faceId: String, packageName: String, auditType: AuditType) {
        sendBroadcast(Intent(ACTION_AUDIT_SUGGESTED)
            .putExtra(EXTRA_FACE_ID, faceId)
            .putExtra(EXTRA_PACKAGE, packageName)
            .putExtra(EXTRA_AUDIT_TYPE, auditType.name))
    }

    private fun broadcastPersonaChanged(faceId: String, packageName: String, persona: Persona) {
        sendBroadcast(Intent(ACTION_PERSONA_CHANGED)
            .putExtra(EXTRA_FACE_ID, faceId)
            .putExtra(EXTRA_PACKAGE, packageName)
            .putExtra(EXTRA_PERSONA_ID, persona.id)
            .putExtra(EXTRA_PERSONA_TYPE, persona.type.name))
    }

    private fun broadcastMappingJobReady(job: MappingJob) {
        sendBroadcast(Intent(ACTION_MAPPING_JOB_READY)
            .putExtra(EXTRA_JOB_ID, job.id)
            .putExtra(EXTRA_PACKAGE, job.packageName))
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val channelId = "inyourface_governor"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "In Your Face — Governor",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Monitoring your Interactive Faces" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("In Your Face")
            .setContentText("Governor active — watching your IFs")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun log(msg: String) = android.util.Log.d("TopGovernor", msg)

    // ─── Binder ───────────────────────────────────────────────────────────────

    inner class GovernorBinder : Binder() {
        fun getGovernor(): TopGovernor = this@TopGovernor
    }

    // ─── Constants ────────────────────────────────────────────────────────────

    companion object {
        private const val NOTIFICATION_ID          = 1002
        private const val GOVERNOR_POLL_MS         = 5000L
        private const val DRIFT_INCREMENT_ON_STALE = 0.15f

        // Broadcast actions
        const val ACTION_HEALTH_UPDATED     = "com.inyourface.HEALTH_UPDATED"
        const val ACTION_AUTO_DEPLOY_READY  = "com.inyourface.AUTO_DEPLOY_READY"
        const val ACTION_AUDIT_SUGGESTED    = "com.inyourface.AUDIT_SUGGESTED"
        const val ACTION_PERSONA_CHANGED    = "com.inyourface.PERSONA_CHANGED"
        const val ACTION_MAPPING_JOB_READY  = "com.inyourface.MAPPING_JOB_READY"

        // Intent extras
        const val EXTRA_FACE_ID            = "face_id"
        const val EXTRA_PACKAGE            = "package_name"
        const val EXTRA_HEALTH_COLOR       = "health_color"
        const val EXTRA_HEALTH_EXPRESSION  = "health_expression"
        const val EXTRA_DRIFT_SCORE        = "drift_score"
        const val EXTRA_PERSONA_ID         = "persona_id"
        const val EXTRA_PERSONA_TYPE       = "persona_type"
        const val EXTRA_AUDIT_TYPE         = "audit_type"
        const val EXTRA_ACTIVE_PERSONA_ID  = "active_persona_id"
        const val EXTRA_JOB_ID             = "job_id"
    }
}
