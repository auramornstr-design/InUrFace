/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * InteractiveFace.kt
 * The IF — a user's personal interactive face for a specific foreign app interface.
 * This file replaces OverlayProfile entirely.
 *
 * An IF is the single source of truth for a user's relationship with a foreign app.
 * The Diplomat builds the TranslationKey (what exists and where).
 * The IF stores how the user has personalized it (layers, personas, overrides, health).
 *
 * Structure:
 *   InteractiveFace
 *   ├── Grid Layers          [Metric, Cosmetic, Dynamic, Trigger]
 *   ├── Object Registry      [element overrides keyed by MappedElement id]
 *   ├── Health State         [belongs to IF — never to a Persona]
 *   └── Personas             [Lean, Balanced, Full, Custom...]
 *
 * Staleness:
 *   The IF stores the TranslationKey version hash it was built against.
 *   When the foreign app updates, isStale() returns true and TopGovernor is notified.
 *
 * Kept vs. Mapped:
 *   An IF can exist without being kept in the Manakit.
 *   Manakit.keptFaceIds determines which IFs auto-deploy.
 *   Unmapped (draft) IFs can exist and be edited before being kept.
 */

package com.inyourface.app.model

import android.content.Context
import com.google.gson.GsonBuilder
import java.io.File
import java.util.UUID

// ─── Interactive Face ─────────────────────────────────────────────────────────

data class InteractiveFace(
    val id: String,
    val name: String,
    val foreignPackageName: String,
    val translationKeyHash: String,          // Version hash — stale if foreign app updates
    val createdAtMs: Long,
    val lastModifiedAtMs: Long,
    val layers: Map<IFLayerType, IFLayer>    = IFLayer.defaultSet(),
    val personas: List<Persona>              = Persona.systemDefaults(),
    val activePersonaId: String              = Persona.ID_FULL,
    val health: IFHealth                     = IFHealth.fresh(),
    val elementOverrides: Map<String, ElementOverride> = emptyMap(),
    val notes: String                        = ""
) {
    // ─── Derived Properties ───────────────────────────────────────────────────

    /** The active Persona object. Falls back to full persona if id is not found. */
    val activePersona: Persona
        get() = personas.firstOrNull { it.id == activePersonaId }
            ?: personas.firstOrNull { it.type == PersonaType.FULL }
            ?: Persona.full()

    /** The set of IFLayerTypes that are active under the current persona. */
    fun activeEnabledLayers(): Set<IFLayerType> = activePersona.enabledLayers

    /** True if the IF was built against a different TranslationKey version. */
    fun isStale(currentVersionHash: String): Boolean =
        translationKeyHash.isNotEmpty() && translationKeyHash != currentVersionHash

    // ─── Immutable Updaters ───────────────────────────────────────────────────

    fun withHealth(newHealth: IFHealth): InteractiveFace =
        copy(health = newHealth, lastModifiedAtMs = System.currentTimeMillis())

    fun withActivePersona(personaId: String): InteractiveFace {
        if (personas.none { it.id == personaId }) return this
        return copy(activePersonaId = personaId, lastModifiedAtMs = System.currentTimeMillis())
    }

    fun withElementOverride(override: ElementOverride): InteractiveFace =
        copy(
            elementOverrides  = elementOverrides + (override.elementId to override),
            lastModifiedAtMs  = System.currentTimeMillis()
        )

    fun withoutElementOverride(elementId: String): InteractiveFace =
        copy(
            elementOverrides  = elementOverrides - elementId,
            lastModifiedAtMs  = System.currentTimeMillis()
        )

    fun withPersonaAdded(persona: Persona): InteractiveFace =
        copy(
            personas         = (personas + persona).distinctBy { it.id },
            lastModifiedAtMs = System.currentTimeMillis()
        )

    fun withPersonaRemoved(personaId: String): InteractiveFace {
        // System personas cannot be removed
        val target = personas.firstOrNull { it.id == personaId } ?: return this
        if (target.isSystemManaged) return this
        val updated = personas.filter { it.id != personaId }
        val newActiveId = if (activePersonaId == personaId) Persona.ID_FULL else activePersonaId
        return copy(
            personas         = updated,
            activePersonaId  = newActiveId,
            lastModifiedAtMs = System.currentTimeMillis()
        )
    }

    fun withLayerEnabled(layerType: IFLayerType, enabled: Boolean): InteractiveFace {
        val updatedLayers = layers.toMutableMap()
        updatedLayers[layerType] = (layers[layerType] ?: IFLayer(layerType))
            .copy(isEnabled = enabled)
        return copy(layers = updatedLayers, lastModifiedAtMs = System.currentTimeMillis())
    }

    fun withUpdatedTranslationKeyHash(newHash: String): InteractiveFace =
        copy(translationKeyHash = newHash, lastModifiedAtMs = System.currentTimeMillis())

    fun serialize(): String = gson.toJson(this)

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()

        /**
         * Create a fresh IF for a foreign app package.
         * Starts with full default layers, three system personas, FULL active, fresh health.
         */
        fun create(
            name: String,
            packageName: String,
            translationKeyHash: String
        ): InteractiveFace = InteractiveFace(
            id                 = UUID.randomUUID().toString(),
            name               = name,
            foreignPackageName = packageName,
            translationKeyHash = translationKeyHash,
            createdAtMs        = System.currentTimeMillis(),
            lastModifiedAtMs   = System.currentTimeMillis()
        )

        fun deserialize(json: String): InteractiveFace =
            gson.fromJson(json, InteractiveFace::class.java)
    }
}

// ─── IF Store ─────────────────────────────────────────────────────────────────

/**
 * Persistent storage for Interactive Faces.
 * Organized by package name: interactive_faces/{packageName}/{faceId}.json
 *
 * loadAllAcrossPackages() is used by TopGovernor during battery persona sweeps.
 */
object InteractiveFaceStore {
    private const val DIR = "interactive_faces"
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun save(context: Context, face: InteractiveFace) {
        val dir = File(context.filesDir, "$DIR/${face.foreignPackageName}").apply { mkdirs() }
        File(dir, "${face.id}.json").writeText(face.serialize())
    }

    fun load(context: Context, faceId: String, packageName: String): InteractiveFace? {
        val file = File(context.filesDir, "$DIR/$packageName/$faceId.json")
        return if (file.exists()) {
            try { InteractiveFace.deserialize(file.readText()) } catch (e: Exception) { null }
        } else null
    }

    fun loadAll(context: Context, packageName: String): List<InteractiveFace> {
        val dir = File(context.filesDir, "$DIR/$packageName")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                try { InteractiveFace.deserialize(file.readText()) } catch (e: Exception) { null }
            } ?: emptyList()
    }

    /** Used by TopGovernor battery sweep — loads every IF across all packages. */
    fun loadAllAcrossPackages(context: Context): List<InteractiveFace> {
        val root = File(context.filesDir, DIR)
        if (!root.exists()) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { pkgDir ->
                pkgDir.listFiles { f -> f.extension == "json" }
                    ?.mapNotNull { file ->
                        try { InteractiveFace.deserialize(file.readText()) } catch (e: Exception) { null }
                    } ?: emptyList()
            } ?: emptyList()
    }

    fun delete(context: Context, faceId: String, packageName: String) {
        File(context.filesDir, "$DIR/$packageName/$faceId.json").delete()
    }

    /** Targeted health update — loads, patches health, saves. No full reload needed. */
    fun updateHealth(context: Context, faceId: String, packageName: String, health: IFHealth) {
        load(context, faceId, packageName)
            ?.withHealth(health)
            ?.let { save(context, it) }
    }

    /** Update the active persona on a persisted IF. */
    fun updateActivePersona(context: Context, faceId: String, packageName: String, personaId: String) {
        load(context, faceId, packageName)
            ?.withActivePersona(personaId)
            ?.let { save(context, it) }
    }

    /** Refresh the translation key hash after a successful re-map or audit. */
    fun updateTranslationKeyHash(context: Context, faceId: String, packageName: String, newHash: String) {
        load(context, faceId, packageName)
            ?.withUpdatedTranslationKeyHash(newHash)
            ?.let { save(context, it) }
    }

    /** Get a lightweight list of all IFs for a package — name + id only. */
    fun summaries(context: Context, packageName: String): List<IFSummary> =
        loadAll(context, packageName).map { face ->
            IFSummary(
                id                 = face.id,
                name               = face.name,
                foreignPackageName = face.foreignPackageName,
                activePersonaName  = face.activePersona.name,
                healthColor        = face.health.color,
                healthExpression   = face.health.expression,
                lastModifiedAtMs   = face.lastModifiedAtMs
            )
        }.sortedByDescending { it.lastModifiedAtMs }
}

// ─── IF Summary ───────────────────────────────────────────────────────────────

/**
 * Lightweight IF metadata for gallery display in the Manakit.
 * Avoids deserializing the full IF when only cards are being rendered.
 */
data class IFSummary(
    val id: String,
    val name: String,
    val foreignPackageName: String,
    val activePersonaName: String,
    val healthColor: HealthColor,
    val healthExpression: HealthExpression,
    val lastModifiedAtMs: Long
)
