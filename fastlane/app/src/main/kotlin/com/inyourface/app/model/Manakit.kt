/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * Manakit.kt
 * The user's personal container for their Interactive Faces.
 *
 * The Manakit is the gallery — the user's collection of IFs they've chosen to keep.
 * Keeping an IF means it auto-deploys when the user enters that app's space.
 * An IF can be mapped without being kept. Keeping is the intentional act of storing it.
 *
 * Tier limits:
 *   FREE  —  3 IF slots  | system personas only — no custom persona control
 *   PLUS  — 10 IF slots  | custom personas unlocked
 *   PRO   — 50 IF slots  | full persona control + priority mapping queue
 *
 * Auto-deploy:
 *   When the user enters a foreign app space that has an IF in their Manakit,
 *   TopGovernor detects the match and signals the overlay to lay out automatically.
 *   The user does not have to manually trigger it.
 *
 * Persona control:
 *   Free users — system degrades personas with battery. No user configuration.
 *   Plus/Pro   — can create, name, and configure custom personas per IF.
 */

package com.inyourface.app.model

import android.content.Context
import com.google.gson.GsonBuilder
import java.io.File

// ─── Tier ─────────────────────────────────────────────────────────────────────

enum class ManakitTier(val label: String) {
    FREE ("Free"),
    PLUS ("Plus"),
    PRO  ("Pro")
}

// ─── Manakit ──────────────────────────────────────────────────────────────────

data class Manakit(
    val userId: String,
    val tier: ManakitTier,
    val keptFaceIds: List<String>,               // Ordered list of IF ids being kept
    val lastAutoDeployedFaceId: String? = null,  // Most recently auto-deployed IF
    val lastAutoDeployedPackage: String? = null
) {
    // ─── Tier Limits ─────────────────────────────────────────────────────────

    val slotLimit: Int get() = when (tier) {
        ManakitTier.FREE -> FREE_SLOT_LIMIT
        ManakitTier.PLUS -> PLUS_SLOT_LIMIT
        ManakitTier.PRO  -> PRO_SLOT_LIMIT
    }

    val usedSlots: Int     get() = keptFaceIds.size
    val availableSlots: Int get() = (slotLimit - usedSlots).coerceAtLeast(0)
    val isFull: Boolean    get() = usedSlots >= slotLimit

    /** Free users cannot create or configure custom personas. */
    val canUseCustomPersonas: Boolean get() = tier != ManakitTier.FREE

    /** Pro users get priority position in the Diplomat's mapping queue. */
    val hasPriorityMapping: Boolean get() = tier == ManakitTier.PRO

    // ─── Slot Management ─────────────────────────────────────────────────────

    /**
     * Keep an IF in the Manakit. Returns updated Manakit or same instance if full.
     * Duplicate IDs are silently ignored.
     */
    fun keepFace(faceId: String): Manakit {
        if (isFull || keptFaceIds.contains(faceId)) return this
        return copy(keptFaceIds = keptFaceIds + faceId)
    }

    /**
     * Remove an IF from the Manakit. The IF itself is not deleted from storage —
     * only the kept reference is removed. Auto-deploy will no longer fire for it.
     */
    fun releaseFace(faceId: String): Manakit =
        copy(keptFaceIds = keptFaceIds - faceId)

    // ─── Auto-Deploy Lookup ───────────────────────────────────────────────────

    /**
     * True if the Manakit contains a kept IF for the given package name.
     * Used by TopGovernor to decide whether to signal auto-deploy on app entry.
     */
    fun hasKeptFaceForPackage(packageName: String, allFaces: List<InteractiveFace>): Boolean =
        allFaces.any { it.id in keptFaceIds && it.foreignPackageName == packageName }

    /**
     * Returns the kept IF for the given package, or null if none is kept.
     * If multiple IFs exist for the same package (possible in Plus/Pro),
     * returns the most recently modified one that is kept.
     */
    fun getKeptFaceForPackage(
        packageName: String,
        allFaces: List<InteractiveFace>
    ): InteractiveFace? =
        allFaces
            .filter { it.id in keptFaceIds && it.foreignPackageName == packageName }
            .maxByOrNull { it.lastModifiedAtMs }

    /** Record the most recently auto-deployed IF for diagnostic/UI use. */
    fun withLastAutoDeploy(faceId: String, packageName: String): Manakit =
        copy(lastAutoDeployedFaceId = faceId, lastAutoDeployedPackage = packageName)

    fun serialize(): String = gson.toJson(this)

    companion object {
        const val FREE_SLOT_LIMIT = 3
        const val PLUS_SLOT_LIMIT = 8
        const val PRO_SLOT_LIMIT  = 50

        private val gson = GsonBuilder().setPrettyPrinting().create()

        fun create(userId: String, tier: ManakitTier = ManakitTier.FREE): Manakit =
            Manakit(userId = userId, tier = tier, keptFaceIds = emptyList())

        fun deserialize(json: String): Manakit =
            gson.fromJson(json, Manakit::class.java)
    }
}

// ─── Manakit Store ────────────────────────────────────────────────────────────

/**
 * Persistent storage for the single Manakit instance owned by this device user.
 * The Manakit is a singleton per user — one file, always the same path.
 */
object ManakitStore {
    private const val MANAKIT_DIR  = "manakit"
    private const val MANAKIT_FILE = "manakit/manakit.json"
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun save(context: Context, manakit: Manakit) {
        File(context.filesDir, MANAKIT_DIR).mkdirs()
        File(context.filesDir, MANAKIT_FILE).writeText(manakit.serialize())
    }

    fun load(context: Context): Manakit? {
        val file = File(context.filesDir, MANAKIT_FILE)
        return if (file.exists()) {
            try { Manakit.deserialize(file.readText()) } catch (e: Exception) { null }
        } else null
    }

    /**
     * Load the Manakit or create a new FREE one and save it.
     * Safe to call on first launch.
     */
    fun loadOrCreate(context: Context, userId: String = "local"): Manakit =
        load(context) ?: Manakit.create(userId).also { save(context, it) }

    /**
     * Add an IF to the Manakit.
     * Returns true if the face was successfully kept, false if the kit is full.
     */
    fun keepFace(context: Context, faceId: String): Boolean {
        val kit = load(context) ?: return false
        if (kit.isFull) return false
        save(context, kit.keepFace(faceId))
        return true
    }

    /**
     * Remove an IF from the Manakit. Does not delete the IF from InteractiveFaceStore.
     */
    fun releaseFace(context: Context, faceId: String) {
        val kit = load(context) ?: return
        save(context, kit.releaseFace(faceId))
    }

    /**
     * Record the most recently auto-deployed IF. Called by TopGovernor on auto-deploy.
     */
    fun recordAutoDeploy(context: Context, faceId: String, packageName: String) {
        val kit = load(context) ?: return
        save(context, kit.withLastAutoDeploy(faceId, packageName))
    }

    /**
     * Upgrade the user's tier. Called when a subscription is confirmed.
     */
    fun upgradeTier(context: Context, newTier: ManakitTier) {
        val kit = load(context) ?: return
        save(context, kit.copy(tier = newTier))
    }
}
