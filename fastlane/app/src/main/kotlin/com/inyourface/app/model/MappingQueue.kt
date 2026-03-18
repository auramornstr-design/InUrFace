/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * MappingQueue.kt
 * The queue of foreign app interfaces waiting to be mapped by DiplomatRuntime.
 *
 * Users queue interfaces by selecting an app (and optionally a screenshot reference).
 * The Diplomat processes the queue asynchronously — it maps the interface, creates
 * a TranslationKey, and creates the initial InteractiveFace for the user.
 *
 * Queue order:
 *   Pro users' jobs are processed with PRIORITY status.
 *   Plus and Free users' jobs are NORMAL.
 *   The Diplomat always drains PRIORITY jobs before NORMAL.
 *
 * A package can only have one PENDING or IN_PROGRESS job at a time.
 * If the user queues the same app twice, the second request is a no-op.
 *
 * This is the mechanism that avoids storing app-specific data upfront —
 * nothing is mapped until the Diplomat picks the job off the queue.
 */

package com.inyourface.app.model

import android.content.Context
import com.google.gson.GsonBuilder
import java.io.File
import java.util.UUID

// ─── Job Priority ─────────────────────────────────────────────────────────────

enum class MappingPriority {
    PRIORITY, // Pro users — processed before NORMAL
    NORMAL    // Free and Plus users
}

// ─── Job Status ───────────────────────────────────────────────────────────────

enum class MappingJobStatus(val label: String) {
    PENDING    ("Pending"),     // Queued, waiting for Diplomat to process
    IN_PROGRESS("In Progress"), // Diplomat is actively mapping this interface
    COMPLETE   ("Complete"),    // IF created successfully — faceId populated
    FAILED     ("Failed")       // Mapping failed — reason populated
}

// ─── Mapping Job ──────────────────────────────────────────────────────────────

/**
 * A single interface mapping request.
 *
 * [id]              Stable job identifier.
 * [packageName]     The foreign app to map.
 * [appLabel]        Human-readable app name for display.
 * [priority]        PRIORITY for Pro users, NORMAL otherwise.
 * [requestedAtMs]   When the user queued this job.
 * [status]          Current pipeline stage.
 * [resultFaceId]    Populated on COMPLETE — the id of the created InteractiveFace.
 * [failureReason]   Populated on FAILED — a human-readable reason string.
 * [screenshotRef]   Optional path to a user-provided reference screenshot.
 *                   Used by Diplomat as a visual hint during mapping.
 */
data class MappingJob(
    val id: String,
    val packageName: String,
    val appLabel: String,
    val priority: MappingPriority,
    val requestedAtMs: Long,
    val status: MappingJobStatus,
    val resultFaceId: String? = null,
    val failureReason: String? = null,
    val screenshotRef: String? = null
) {
    val isPending: Boolean     get() = status == MappingJobStatus.PENDING
    val isInProgress: Boolean  get() = status == MappingJobStatus.IN_PROGRESS
    val isTerminal: Boolean    get() = status == MappingJobStatus.COMPLETE
                                    || status == MappingJobStatus.FAILED

    fun asInProgress(): MappingJob = copy(status = MappingJobStatus.IN_PROGRESS)

    fun asComplete(faceId: String): MappingJob =
        copy(status = MappingJobStatus.COMPLETE, resultFaceId = faceId)

    fun asFailed(reason: String): MappingJob =
        copy(status = MappingJobStatus.FAILED, failureReason = reason)

    companion object {
        fun create(
            packageName: String,
            appLabel: String,
            priority: MappingPriority = MappingPriority.NORMAL,
            screenshotRef: String? = null
        ): MappingJob = MappingJob(
            id             = UUID.randomUUID().toString(),
            packageName    = packageName,
            appLabel       = appLabel,
            priority       = priority,
            requestedAtMs  = System.currentTimeMillis(),
            status         = MappingJobStatus.PENDING,
            screenshotRef  = screenshotRef
        )
    }
}

// ─── Mapping Queue ────────────────────────────────────────────────────────────

/**
 * The persistent queue manager.
 * Stored at: manakit/mapping_queue.json — co-located with the Manakit.
 *
 * enqueue()       — Add a job. No-op if same package is already pending/in-progress.
 * dequeueNext()   — Pull the next job in priority order. Marks it IN_PROGRESS.
 * markComplete()  — Record the created IF id. Moves job to COMPLETE.
 * markFailed()    — Record failure reason. Moves job to FAILED.
 * pendingJobs()   — All jobs not yet in a terminal state.
 * snapshot()      — Full current queue for UI display.
 */
object MappingQueue {
    private const val QUEUE_FILE = "manakit/mapping_queue.json"
    private val gson = GsonBuilder().setPrettyPrinting().create()

    // ─── Enqueue ──────────────────────────────────────────────────────────────

    /**
     * Add a package to the mapping queue.
     * Returns the new job, or null if that package already has an active job.
     */
    fun enqueue(
        context: Context,
        packageName: String,
        appLabel: String,
        priority: MappingPriority = MappingPriority.NORMAL,
        screenshotRef: String? = null
    ): MappingJob? {
        val jobs = loadAll(context).toMutableList()
        val existing = jobs.firstOrNull { it.packageName == packageName && !it.isTerminal }
        if (existing != null) return null  // Already queued or in progress
        val job = MappingJob.create(packageName, appLabel, priority, screenshotRef)
        jobs.add(job)
        saveAll(context, jobs)
        return job
    }

    // ─── Dequeue ──────────────────────────────────────────────────────────────

    /**
     * Pull the next job for the Diplomat to process.
     * PRIORITY jobs are always returned before NORMAL jobs.
     * Within the same priority, oldest request is returned first (FIFO).
     * Marks the job as IN_PROGRESS before returning it.
     * Returns null if the queue is empty or all jobs are already in progress.
     */
    fun dequeueNext(context: Context): MappingJob? {
        val jobs = loadAll(context).toMutableList()
        val next = jobs
            .filter { it.isPending }
            .sortedWith(compareByDescending<MappingJob> { it.priority == MappingPriority.PRIORITY }
                .thenBy { it.requestedAtMs })
            .firstOrNull() ?: return null
        val idx = jobs.indexOfFirst { it.id == next.id }
        val updated = next.asInProgress()
        jobs[idx] = updated
        saveAll(context, jobs)
        return updated
    }

    // ─── Status Updates ───────────────────────────────────────────────────────

    fun markComplete(context: Context, jobId: String, faceId: String) {
        updateJob(context, jobId) { it.asComplete(faceId) }
    }

    fun markFailed(context: Context, jobId: String, reason: String) {
        updateJob(context, jobId) { it.asFailed(reason) }
    }

    // ─── Query ────────────────────────────────────────────────────────────────

    fun pendingJobs(context: Context): List<MappingJob> =
        loadAll(context).filter { !it.isTerminal }
            .sortedWith(compareByDescending<MappingJob> { it.priority == MappingPriority.PRIORITY }
                .thenBy { it.requestedAtMs })

    fun snapshot(context: Context): List<MappingJob> =
        loadAll(context).sortedByDescending { it.requestedAtMs }

    fun hasActiveJobForPackage(context: Context, packageName: String): Boolean =
        loadAll(context).any { it.packageName == packageName && !it.isTerminal }

    // ─── Internal Persistence ─────────────────────────────────────────────────

    private fun loadAll(context: Context): List<MappingJob> {
        val file = File(context.filesDir, QUEUE_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<MappingJob>>() {}.type
            gson.fromJson<List<MappingJob>>(file.readText(), type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun saveAll(context: Context, jobs: List<MappingJob>) {
        File(context.filesDir, "manakit").mkdirs()
        File(context.filesDir, QUEUE_FILE).writeText(gson.toJson(jobs))
    }

    private fun updateJob(context: Context, jobId: String, transform: (MappingJob) -> MappingJob) {
        val jobs = loadAll(context).toMutableList()
        val idx  = jobs.indexOfFirst { it.id == jobId }
        if (idx < 0) return
        jobs[idx] = transform(jobs[idx])
        saveAll(context, jobs)
    }
}
