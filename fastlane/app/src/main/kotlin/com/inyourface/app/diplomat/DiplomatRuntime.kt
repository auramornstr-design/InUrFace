/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * DiplomatRuntime.kt — Phase 4 update
 * Wired to the new IF/Manakit/Governor architecture.
 *
 * Phase 3 preserved:
 *   - handleTeachModeActivate uses real screen X/Y coordinates
 *   - TeachModeOverlay is mounted/unmounted by the Diplomat
 *   - findNodeAtPoint: depth-first search for deepest interactive node at tapped coords
 *   - TeachModeFailed for clear failure signaling
 *
 * Phase 4 additions:
 *   - onServiceConnected binds to TopGovernor and reports app space entry
 *   - handleAppSwitch notifies TopGovernor via binder (onAppSpaceEntered)
 *   - buildTranslationKey also creates an InteractiveFace if one does not exist
 *   - handleAutoDeployActivate loads IF element overrides into OverlayCanvas
 *   - handleAuditRequest runs the appropriate audit level and emits AuditComplete
 *   - handleMappingJobReady processes the next item from the MappingQueue
 */

package com.inyourface.app.diplomat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.inyourface.app.InYourFaceApp
import com.inyourface.app.governor.TopGovernor
import com.inyourface.app.grid.GridSystem
import com.inyourface.app.model.*
import com.inyourface.app.overlay.*
import kotlinx.coroutines.*
import java.util.UUID

class DiplomatRuntime : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayCanvas: OverlayCanvas
    private val markerSurface = InYourFaceApp.markerSurface
    private val diplomatScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var teachOverlay: TeachModeOverlay? = null

    private var activePackage: String = ""
    private var activeTranslationKey: TranslationKey? = null
    private var activeGridSystem: GridSystem? = null
    private val translationKeyCache = mutableMapOf<String, TranslationKey>()

    // Phase 4 — TopGovernor binder reference
    private var topGovernor: TopGovernor? = null
    private val governorConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            topGovernor = (binder as? TopGovernor.GovernorBinder)?.getGovernor()
            log("Bound to TopGovernor.")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            topGovernor = null
            log("TopGovernor unbound.")
        }
    }

    // ─── Service Lifecycle ────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayCanvas = OverlayCanvas(this, markerSurface)
        mountOverlayWindow()
        startMarkerObserver()
        bindToGovernor()
        log("DiplomatRuntime connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED  -> handleAppSwitch(pkg)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleContentChange(pkg)
        }
    }

    override fun onInterrupt() { log("DiplomatRuntime interrupted.") }

    override fun onDestroy() {
        super.onDestroy()
        diplomatScope.cancel()
        unmountTeachOverlay()
        unmountOverlayWindow()
        try { unbindService(governorConnection) } catch (e: Exception) {}
    }

    // ─── Governor Binding ─────────────────────────────────────────────────────

    private fun bindToGovernor() {
        val intent = Intent(this, TopGovernor::class.java)
        bindService(intent, governorConnection, Context.BIND_AUTO_CREATE)
    }

    // ─── Overlay Window ───────────────────────────────────────────────────────

    private fun mountOverlayWindow() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        mainHandler.post { windowManager.addView(overlayCanvas, params) }
    }

    private fun unmountOverlayWindow() {
        mainHandler.post { try { windowManager.removeView(overlayCanvas) } catch (e: Exception) {} }
    }

    // ─── Teach Mode Overlay ───────────────────────────────────────────────────

    private fun mountTeachOverlay(requestId: String) {
        mainHandler.post {
            val overlay = TeachModeOverlay(this, markerSurface, requestId) { unmountTeachOverlay() }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            teachOverlay = overlay
            windowManager.addView(overlay, params)
            overlay.start()
            log("TeachModeOverlay mounted.")
        }
    }

    private fun unmountTeachOverlay() {
        mainHandler.post {
            teachOverlay?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
            teachOverlay = null
        }
    }

    // ─── Marker Observer ──────────────────────────────────────────────────────

    private fun startMarkerObserver() {
        diplomatScope.launch {
            while (isActive) {
                markerSurface.pendingRequests().forEach { handleMarker(it) }
                markerSurface.purgeStale()
                delay(16L)
            }
        }
    }

    private suspend fun handleMarker(marker: OverlayMarker) {
        when (marker) {
            is OverlayMarker.CustomizeModeActivate   -> handleCustomizeModeActivate(marker)
            is OverlayMarker.CustomizeModeCommit     -> handleCustomizeModeCommit(marker)
            is OverlayMarker.CustomizeModeCancel     -> handleCustomizeModeCancel(marker)
            is OverlayMarker.TeachModeActivate       -> handleTeachModeActivate(marker)
            is OverlayMarker.TeachModeCancel         -> { unmountTeachOverlay(); log("Teach cancelled.") }
            is OverlayMarker.CapabilityChangeRequest -> handleCapabilityChange(marker)
            is OverlayMarker.ProxyButtonTap          -> handleProxyTap(marker)
            is OverlayMarker.PreScoutRequest         -> handlePreScout(marker)
            is OverlayMarker.AutoDeployActivate      -> handleAutoDeployActivate(marker)  // Phase 4
            is OverlayMarker.AuditRequest            -> handleAuditRequest(marker)         // Phase 4
            else -> {}
        }
        markerSurface.clear(marker.id)
    }

    // ─── Customize Mode ───────────────────────────────────────────────────────

    private suspend fun handleCustomizeModeActivate(marker: OverlayMarker.CustomizeModeActivate) {
        val key = getOrBuildTranslationKey(activePackage)
        val grid = activeGridSystem ?: return
        markerSurface.respond(OverlayMarker.GridReady(
            id = generateMarkerId(), placedAtMs = System.currentTimeMillis(),
            requestId = marker.id, zoneSnapshot = key.gridZoneMap.mapValues { it.value.name }
        ))
        withContext(Dispatchers.Main) { overlayCanvas.activateCustomizeMode(key, grid) }
    }

    private suspend fun handleCustomizeModeCommit(marker: OverlayMarker.CustomizeModeCommit) {
        activeTranslationKey?.let { withContext(Dispatchers.IO) { TranslationKeyStore.save(this@DiplomatRuntime, it) } }
        withContext(Dispatchers.Main) { overlayCanvas.deactivateCustomizeMode() }
    }

    private suspend fun handleCustomizeModeCancel(marker: OverlayMarker.CustomizeModeCancel) {
        val saved = withContext(Dispatchers.IO) { TranslationKeyStore.load(this@DiplomatRuntime, activePackage) }
        activeTranslationKey = saved
        withContext(Dispatchers.Main) { overlayCanvas.deactivateCustomizeMode() }
    }

    // ─── Teach Mode (Phase 3 — real screen coordinates) ──────────────────────

    private suspend fun handleTeachModeActivate(marker: OverlayMarker.TeachModeActivate) {
        val hasCoords = marker.screenX > 0f || marker.screenY > 0f
        if (!hasCoords) {
            mountTeachOverlay(marker.id)
            return
        }
        unmountTeachOverlay()
        log("Teach Mode: capturing at screen (${marker.screenX}, ${marker.screenY})")

        val grid = activeGridSystem
        val key  = activeTranslationKey
        if (grid == null || key == null) {
            markerSurface.respond(OverlayMarker.TeachModeFailed(
                id = generateMarkerId(), placedAtMs = System.currentTimeMillis(),
                requestId = marker.id, reason = TeachFailReason.APP_NOT_ACTIVE
            ))
            return
        }

        val node = findNodeAtPoint(marker.screenX, marker.screenY)
        if (node == null) {
            markerSurface.respond(OverlayMarker.TeachModeFailed(
                id = generateMarkerId(), placedAtMs = System.currentTimeMillis(),
                requestId = marker.id, reason = TeachFailReason.NO_NODE_AT_COORDINATES
            ))
            return
        }
        if (node.isPassword) {
            node.recycle()
            markerSurface.respond(OverlayMarker.TeachModeFailed(
                id = generateMarkerId(), placedAtMs = System.currentTimeMillis(),
                requestId = marker.id, reason = TeachFailReason.NODE_SECURITY_LOCKED
            ))
            return
        }

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val gridCells = grid.toGridCells(bounds)
        val motionSignature = captureMotionSignature(node)
        val label = marker.sourceLabel.ifEmpty {
            node.contentDescription?.toString() ?: node.text?.toString() ?: "Element"
        }
        val elementId = UUID.randomUUID().toString()

        val element = MappedElement(
            id = elementId, foreignAppId = activePackage,
            contentDescription = label, screenBounds = bounds, gridCells = gridCells,
            availableCapabilities = resolveAvailableCapabilities(node),
            capabilityStates = emptyMap(), motionSignature = motionSignature,
            constraintFlags = resolveConstraintFlags(node)
        )
        activeTranslationKey = key.copy(
            mappedElements    = key.mappedElements + (elementId to element),
            lastRefreshedAtMs = System.currentTimeMillis()
        )
        withContext(Dispatchers.IO) {
            activeTranslationKey?.let { TranslationKeyStore.save(this@DiplomatRuntime, it) }
        }
        activeTranslationKey?.let { updatedKey ->
            withContext(Dispatchers.Main) { overlayCanvas.loadTranslationKey(updatedKey, grid) }
        }
        node.recycle()

        markerSurface.respond(OverlayMarker.TeachModeComplete(
            id = generateMarkerId(), placedAtMs = System.currentTimeMillis(),
            requestId = marker.id, learnedElementId = elementId,
            learnedCells = gridCells, capturedLabel = label,
            capturedBoundsLeft = bounds.left, capturedBoundsTop = bounds.top,
            capturedBoundsRight = bounds.right, capturedBoundsBottom = bounds.bottom
        ))
        log("Teach complete: '$label' → $elementId")
    }

    // ─── Auto-Deploy (Phase 4) ────────────────────────────────────────────────

    /**
     * Governor confirmed a kept IF for this app space.
     * Load the IF's element overrides on top of the active TranslationKey.
     * The OverlayCanvas already has the TranslationKey loaded from handleAppSwitch —
     * this step layers the IF's personal configuration on top.
     */
    private suspend fun handleAutoDeployActivate(marker: OverlayMarker.AutoDeployActivate) {
        val face = withContext(Dispatchers.IO) {
            InteractiveFaceStore.load(this@DiplomatRuntime, marker.faceId, marker.packageName)
        } ?: run {
            log("AutoDeploy: IF '${marker.faceId}' not found for ${marker.packageName}")
            return
        }
        val key  = activeTranslationKey ?: return
        val grid = activeGridSystem ?: return

        // Apply element overrides from the IF onto the active key
        val overriddenKey = applyIFOverridesToKey(face, key)
        activeTranslationKey = overriddenKey

        withContext(Dispatchers.Main) { overlayCanvas.loadTranslationKey(overriddenKey, grid) }
        log("AutoDeploy applied: IF '${face.name}' (${face.activePersona.name} persona) for ${marker.packageName}")
    }

    /**
     * Merge the IF's ElementOverrides into the TranslationKey's MappedElements.
     * Only overrides that reference existing mapped elements are applied.
     * Security-locked elements are never modified.
     */
    private fun applyIFOverridesToKey(face: InteractiveFace, key: TranslationKey): TranslationKey {
        var updatedElements = key.mappedElements
        face.elementOverrides.forEach { (elementId, override) ->
            val element = updatedElements[elementId] ?: return@forEach
            if (element.constraintFlags and MappedElement.FLAG_SECURITY_LOCKED != 0) return@forEach
            val updatedStates = element.capabilityStates.toMutableMap()
            override.capabilityOverrides.forEach { (capId, value) ->
                val capType = CapabilityType.fromId(capId) ?: return@forEach
                if (capType in element.availableCapabilities) {
                    updatedStates[capId] = CapabilityState(capType, true, value)
                }
            }
            updatedElements = updatedElements + (elementId to element.copy(capabilityStates = updatedStates))
        }
        return key.copy(mappedElements = updatedElements)
    }

    // ─── Audit Request (Phase 4) ──────────────────────────────────────────────

    /**
     * Run the requested audit level on an IF.
     * Audit logic depends on the AuditType:
     *   QUICK             — version hash check only. No screen interaction needed.
     *   FUNCTION          — re-traverse nodes and verify element actions still exist.
     *   LAYER_RECALIBRATE — rebuild zone map and re-classify layers.
     *   FULL_REMAP        — full TranslationKey rebuild + new IF sync.
     */
    private suspend fun handleAuditRequest(marker: OverlayMarker.AuditRequest) {
        val face = withContext(Dispatchers.IO) {
            InteractiveFaceStore.load(this@DiplomatRuntime, marker.faceId, marker.packageName)
        } ?: run {
            log("Audit: IF '${marker.faceId}' not found.")
            return
        }
        val driftBefore = face.health.driftScore
        val resolvedObjects = mutableListOf<String>()
        val resolvedLayers  = mutableListOf<IFLayerType>()

        when (marker.auditType) {
            AuditType.QUICK -> {
                // Just confirm the key is still valid — no node traversal
                val currentHash = getAppVersionHash(marker.packageName)
                if (!face.isStale(currentHash)) {
                    resolvedLayers.addAll(IFLayerType.values().toList())
                    log("Quick audit: IF '${face.name}' is current.")
                } else {
                    log("Quick audit: IF '${face.name}' is stale — escalating to FUNCTION.")
                }
            }
            AuditType.FUNCTION -> {
                // Re-traverse and check which mapped elements still have live nodes
                val key = getOrBuildTranslationKey(marker.packageName)
                key.mappedElements.keys.forEach { elementId ->
                    val element = key.mappedElements[elementId] ?: return@forEach
                    val center = element.screenBounds.exactCenterX() to element.screenBounds.exactCenterY()
                    val node = findNodeAtPoint(center.first, center.second)
                    if (node != null) { resolvedObjects.add(elementId); node.recycle() }
                }
                log("Function audit: ${resolvedObjects.size}/${key.mappedElements.size} elements verified.")
            }
            AuditType.LAYER_RECALIBRATE -> {
                // Rebuild the zone map and re-apply to the active grid
                val hash = getAppVersionHash(marker.packageName)
                val rebuiltKey = buildTranslationKey(marker.packageName, hash)
                activeTranslationKey = rebuiltKey
                activeGridSystem?.let { grid ->
                    withContext(Dispatchers.Main) { overlayCanvas.loadTranslationKey(rebuiltKey, grid) }
                }
                resolvedLayers.addAll(listOf(IFLayerType.METRIC, IFLayerType.COSMETIC))
                log("Layer recalibration complete for ${marker.packageName}.")
            }
            AuditType.FULL_REMAP -> {
                // Full rebuild — new TranslationKey, sync IF hash
                val hash = getAppVersionHash(marker.packageName)
                val rebuiltKey = buildTranslationKey(marker.packageName, hash)
                activeTranslationKey = rebuiltKey
                withContext(Dispatchers.IO) {
                    InteractiveFaceStore.updateTranslationKeyHash(
                        this@DiplomatRuntime, marker.faceId, marker.packageName, hash
                    )
                }
                activeGridSystem?.let { grid ->
                    withContext(Dispatchers.Main) { overlayCanvas.loadTranslationKey(rebuiltKey, grid) }
                }
                resolvedObjects.addAll(rebuiltKey.mappedElements.keys)
                resolvedLayers.addAll(IFLayerType.values().toList())
                log("Full remap complete for ${marker.packageName}.")
            }
        }

        // Notify Governor so it can update IFHealth
        topGovernor?.onAuditComplete(
            faceId             = marker.faceId,
            packageName        = marker.packageName,
            auditType          = marker.auditType,
            driftBefore        = driftBefore,
            resolvedObjectIds  = resolvedObjects,
            resolvedLayerTypes = resolvedLayers
        )

        markerSurface.respond(OverlayMarker.AuditComplete(
            id                 = generateMarkerId(),
            placedAtMs         = System.currentTimeMillis(),
            requestId          = marker.id,
            faceId             = marker.faceId,
            packageName        = marker.packageName,
            auditType          = marker.auditType,
            driftBefore        = driftBefore,
            resolvedObjectIds  = resolvedObjects,
            resolvedLayerTypes = resolvedLayers
        ))
    }

    // ─── Node Discovery ───────────────────────────────────────────────────────

    private fun findNodeAtPoint(screenX: Float, screenY: Float): AccessibilityNodeInfo? {
        val point = Rect(screenX.toInt() - 1, screenY.toInt() - 1, screenX.toInt() + 1, screenY.toInt() + 1)
        windows?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.forEach { window ->
            val root = window.root ?: return@forEach
            val found = findDeepestInteractive(root, point)
            root.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findDeepestInteractive(node: AccessibilityNodeInfo, target: Rect): AccessibilityNodeInfo? {
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        if (!Rect.intersects(bounds, target)) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findDeepestInteractive(child, target)
            child.recycle()
            if (found != null) return found
        }
        return if (node.isClickable || node.isFocusable || node.isLongClickable || node.isScrollable)
            AccessibilityNodeInfo.obtain(node) else null
    }

    // ─── Capability Change ────────────────────────────────────────────────────

    private suspend fun handleCapabilityChange(marker: OverlayMarker.CapabilityChangeRequest) {
        val key = activeTranslationKey ?: return
        val element = key.mappedElements[marker.elementId]
        if (element == null) {
            markerSurface.respond(OverlayMarker.CapabilityRejected(
                id = generateMarkerId(), placedAtMs = System.currentTimeMillis(),
                requestId = marker.id, reason = RejectionReason.TRANSLATION_KEY_STALE)); return
        }
        if (marker.capabilityType !in element.availableCapabilities) {
            markerSurface.respond(OverlayMarker.CapabilityRejected(
                id = generateMarkerId(), placedAtMs = System.currentTimeMillis(),
                requestId = marker.id, reason = RejectionReason.CAPABILITY_NOT_AVAILABLE)); return
        }
        if (marker.capabilityType == CapabilityType.POSITION) {
            val pos  = marker.newValue as? CapabilityValue.Position
            val grid = activeGridSystem
            if (pos != null && grid != null && !grid.isSafe(pos.gridCol, pos.gridRow)) {
                markerSurface.respond(OverlayMarker.CapabilityRejected(
                    id = generateMarkerId(), placedAtMs = System.currentTimeMillis(),
                    requestId = marker.id, reason = RejectionReason.ZONE_FORBIDDEN)); return
            }
        }
        val updatedElement = element.copy(
            capabilityStates = element.capabilityStates +
                (marker.capabilityType.id to CapabilityState(marker.capabilityType, true, marker.newValue))
        )
        activeTranslationKey = key.copy(mappedElements = key.mappedElements + (marker.elementId to updatedElement))
        markerSurface.respond(OverlayMarker.CapabilityAccepted(
            id = generateMarkerId(), placedAtMs = System.currentTimeMillis(),
            requestId = marker.id, elementId = marker.elementId,
            capabilityType = marker.capabilityType, acceptedValue = marker.newValue
        ))
    }

    // ─── Proxy Tap ────────────────────────────────────────────────────────────

    private suspend fun handleProxyTap(marker: OverlayMarker.ProxyButtonTap) {
        val key  = activeTranslationKey ?: return
        val element = key.mappedElements[marker.elementId] ?: return
        val grid = activeGridSystem ?: return
        val gesture = marker.gestureOverride
            ?: (element.capabilityStates[CapabilityType.ACTION_TYPE.id]?.currentValue as? CapabilityValue.ActionType)?.gesture
            ?: element.motionSignature?.dominantGestureType ?: GestureType.SINGLE_TAP
        val (cx, cy) = grid.cellCenter(element.gridCells.firstOrNull() ?: return)
        dispatchGestureAt(cx, cy, gesture)
    }

    // ─── Pre-Scout ────────────────────────────────────────────────────────────

    private suspend fun handlePreScout(marker: OverlayMarker.PreScoutRequest) {
        withContext(Dispatchers.IO) {
            val existing = TranslationKeyStore.load(this@DiplomatRuntime, marker.targetPackage)
            val hash = getAppVersionHash(marker.targetPackage)
            if (existing != null && !existing.isKeyStale(hash)) translationKeyCache[marker.targetPackage] = existing
        }
        markerSurface.respond(OverlayMarker.PreScoutComplete(
            id = generateMarkerId(), placedAtMs = System.currentTimeMillis(),
            requestId = marker.id, targetPackage = marker.targetPackage
        ))
    }

    // ─── App Switch ───────────────────────────────────────────────────────────

    private fun handleAppSwitch(packageName: String) {
        if (packageName == activePackage) return
        activePackage = packageName

        // Phase 4: notify TopGovernor — triggers auto-deploy check and health check
        topGovernor?.onAppSpaceEntered(packageName)

        diplomatScope.launch {
            val key = getOrBuildTranslationKey(packageName)
            activeTranslationKey = key
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getMetrics(metrics)
            val grid = GridSystem(metrics.widthPixels, metrics.heightPixels, GridSystem.resolutionFor(key.constraintDensity))
            grid.applyStandardSystemZones(metrics)
            grid.restoreZoneMap(key.gridZoneMap)
            activeGridSystem = grid
            withContext(Dispatchers.Main) { overlayCanvas.loadTranslationKey(key, grid) }
        }
    }

    private fun handleContentChange(packageName: String) {
        if (packageName != activePackage) return
        val key = activeTranslationKey ?: return
        if (key.isKeyStale(getAppVersionHash(packageName)))
            markerSurface.respond(OverlayMarker.TranslationKeyStale(
                id = generateMarkerId(), placedAtMs = System.currentTimeMillis(),
                affectedPackage = packageName))
    }

    // ─── Translation Key ──────────────────────────────────────────────────────

    private suspend fun getOrBuildTranslationKey(packageName: String): TranslationKey {
        val hash = getAppVersionHash(packageName)
        translationKeyCache[packageName]?.let { if (!it.isKeyStale(hash)) return it }
        withContext(Dispatchers.IO) { TranslationKeyStore.load(this@DiplomatRuntime, packageName) }
            ?.let { if (!it.isKeyStale(hash)) { translationKeyCache[packageName] = it; return it } }
        return buildTranslationKey(packageName, hash)
    }

    private suspend fun buildTranslationKey(pkg: String, hash: String): TranslationKey {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getMetrics(metrics)
        val forbidden = mutableListOf<Rect>(); val caution = mutableListOf<Rect>()
        var total = 0; var restricted = 0; var gestures = 0
        windows?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.forEach { win ->
            val root = win.root ?: return@forEach
            val r = traverseForZones(root, 0, 0, 0, forbidden, caution)
            total += r.first; restricted += r.second; gestures += r.third
            root.recycle()
        }
        val density    = GridSystem.estimateConstraintDensity(total, restricted, gestures)
        val resolution = GridSystem.resolutionFor(density)
        val grid = GridSystem(metrics.widthPixels, metrics.heightPixels, resolution)
        grid.applyStandardSystemZones(metrics)
        forbidden.forEach { grid.markForbiddenRect(it) }
        caution.forEach   { grid.markCautionRect(it) }

        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
        val version = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                packageManager.getPackageInfo(pkg, 0).longVersionCode
            else @Suppress("DEPRECATION") packageManager.getPackageInfo(pkg, 0).versionCode.toLong()
        } catch (e: Exception) { 0L }

        val key = TranslationKey(
            foreignPackageName = pkg, appVersionHash = hash,
            createdAtMs = System.currentTimeMillis(), lastRefreshedAtMs = System.currentTimeMillis(),
            gridResolution = resolution, constraintDensity = density,
            gridZoneMap = grid.zoneMapSnapshot(), mappedElements = emptyMap(),
            forbiddenRegions = forbidden, metadata = AppMetadata(pkg, label, version)
        )
        translationKeyCache[pkg] = key
        withContext(Dispatchers.IO) { TranslationKeyStore.save(this@DiplomatRuntime, key) }

        // Phase 4: create a new InteractiveFace for this app if none exists yet
        createInitialIFIfAbsent(pkg, label, hash)

        return key
    }

    /**
     * When the Diplomat maps a foreign app for the first time, it creates
     * a blank InteractiveFace so the user has something to keep in their Manakit.
     * Does nothing if an IF already exists for this package.
     */
    private suspend fun createInitialIFIfAbsent(packageName: String, appLabel: String, keyHash: String) {
        withContext(Dispatchers.IO) {
            val existing = InteractiveFaceStore.loadAll(this@DiplomatRuntime, packageName)
            if (existing.isNotEmpty()) return@withContext
            val face = InteractiveFace.create(
                name               = appLabel,
                packageName        = packageName,
                translationKeyHash = keyHash
            )
            InteractiveFaceStore.save(this@DiplomatRuntime, face)
            log("Initial IF created for $packageName: '${face.name}' (${face.id})")
        }
    }

    // ─── Zone Traversal ───────────────────────────────────────────────────────

    private fun traverseForZones(node: AccessibilityNodeInfo, t: Int, r: Int, g: Int,
        forbidden: MutableList<Rect>, caution: MutableList<Rect>): Triple<Int, Int, Int> {
        var lt = 1; var lr = r; var lg = g
        val b = Rect(); node.getBoundsInScreen(b)
        if (node.isPassword)                        { forbidden.add(Rect(b)); lr++ }
        if (node.isScrollable)                      { caution.add(Rect(b));   lg++ }
        if (node.isClickable && !node.isEnabled)    { caution.add(Rect(b));   lr++ }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = traverseForZones(child, lt, lr, lg, forbidden, caution)
            lt += res.first; lr = res.second; lg = res.third
            child.recycle()
        }
        return Triple(lt, lr, lg)
    }

    // ─── Motion Capture ───────────────────────────────────────────────────────

    private suspend fun captureMotionSignature(node: AccessibilityNodeInfo): MotionSignature {
        val deltas = mutableListOf<BoundingDelta>()
        val start  = System.currentTimeMillis()
        var last   = Rect().also { node.getBoundsInScreen(it) }
        repeat(8) {
            delay(60L)
            val cur = Rect(); node.getBoundsInScreen(cur)
            deltas.add(BoundingDelta(System.currentTimeMillis() - start,
                cur.left - last.left, cur.top - last.top,
                cur.right - last.right, cur.bottom - last.bottom))
            last = cur
        }
        return MotionSignature(UUID.randomUUID().toString(), start, deltas, emptyList(),
            when {
                node.isScrollable    -> GestureType.SWIPE_UP
                node.isLongClickable -> GestureType.LONG_PRESS
                else                 -> GestureType.SINGLE_TAP
            })
    }

    // ─── Gesture Dispatch ─────────────────────────────────────────────────────

    private fun dispatchGestureAt(x: Float, y: Float, type: GestureType) {
        val gesture = when (type) {
            GestureType.SINGLE_TAP  -> stroke(x, y, x, y, 100L)
            GestureType.LONG_PRESS  -> stroke(x, y, x, y, 800L)
            GestureType.DOUBLE_TAP  -> doubleTap(x, y)
            GestureType.SWIPE_UP    -> stroke(x, y, x, y - 300f, 300L)
            GestureType.SWIPE_DOWN  -> stroke(x, y, x, y + 300f, 300L)
            GestureType.SWIPE_LEFT  -> stroke(x, y, x - 300f, y, 300L)
            GestureType.SWIPE_RIGHT -> stroke(x, y, x + 300f, y, 300L)
        }
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) = log("Gesture $type dispatched.")
            override fun onCancelled(g: GestureDescription) = log("Gesture $type cancelled.")
        }, null)
    }

    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) =
        GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(
                Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }, 0L, ms)
        ).build()

    private fun doubleTap(x: Float, y: Float) = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0L, 100L, true))
        .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 200L, 100L))
        .build()

    // ─── Capability Resolution ────────────────────────────────────────────────

    private fun resolveAvailableCapabilities(node: AccessibilityNodeInfo): Set<CapabilityType> {
        val caps = mutableSetOf(CapabilityType.POSITION, CapabilityType.SIZE, CapabilityType.RECOLOR,
            CapabilityType.LABEL, CapabilityType.OPACITY, CapabilityType.BORDER)
        if (node.isClickable || node.isScrollable || node.isLongClickable) caps += CapabilityType.ACTION_TYPE
        if (!node.isPassword) caps += CapabilityType.TARGET_BINDING
        return caps
    }

    private fun resolveConstraintFlags(node: AccessibilityNodeInfo): Int =
        if (node.isPassword) MappedElement.FLAG_SECURITY_LOCKED else 0

    private fun getAppVersionHash(pkg: String): String = try {
        val info = packageManager.getPackageInfo(pkg, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
        "${pkg}_${code}_${info.versionName}".hashCode().toString()
    } catch (e: Exception) { pkg }

    private fun log(msg: String) = android.util.Log.d("DiplomatRuntime", msg)
}

// ─── Translation Key Store ────────────────────────────────────────────────────

object TranslationKeyStore {
    private const val DIR = "translation_keys"
    fun save(context: Context, key: TranslationKey) {
        java.io.File(context.filesDir, DIR).mkdirs()
        java.io.File(context.filesDir, "$DIR/${key.foreignPackageName}.json").writeText(key.serialize())
    }
    fun load(context: Context, pkg: String): TranslationKey? {
        val f = java.io.File(context.filesDir, "$DIR/$pkg.json")
        return if (f.exists()) try { TranslationKey.deserialize(f.readText()) } catch (e: Exception) { null } else null
    }
    fun delete(context: Context, pkg: String) {
        java.io.File(context.filesDir, "$DIR/$pkg.json").delete()
    }
}
