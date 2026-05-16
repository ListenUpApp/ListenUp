package com.calypsan.listenup.client.diagnostics

import android.app.job.JobScheduler
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Logs [JobScheduler] pending-reason statistics for WorkManager jobs on Android 17+ (API 37,
 * [Build.VERSION_CODES.CINNAMON_BUN]).
 *
 * ## Purpose
 * Android 17 introduced [JobScheduler.getPendingJobReasonStats], which returns a histogram of
 * how long a job has been waiting for each constraint to be satisfied. Surfacing this as a
 * structured log line gives operators a direct view into why a WorkManager job is deferred —
 * e.g. battery saver, quota, or a missing network — without requiring ADB or on-device tooling.
 *
 * ## API gate
 * All runtime paths are guarded behind [Build.VERSION_CODES.CINNAMON_BUN] (API 37). On earlier
 * releases [shouldLog] returns `false` and [logPendingReasonsFor] is unreachable by design.
 *
 * ## WorkManager integration
 * WorkManager does not expose JobScheduler job IDs directly. This logger searches
 * [JobScheduler.getAllPendingJobs] for the job whose extras contain a matching
 * [EXTRA_WORK_SPEC_ID_KEY] string. That matching strategy is brittle by nature — it depends on
 * WorkManager's internal extras key — so the entire lookup is wrapped in [runCatching]. A
 * diagnostics failure must never propagate up to the worker or crash the host process.
 *
 * ## `formatReasonStats` contract
 * [formatReasonStats] accepts `Map<Int, Long>` where keys are [JobScheduler] `PENDING_JOB_REASON_*`
 * int constants and values are accumulated milliseconds. Stable as a public helper and visible
 * for testing.
 */
object JobReasonLogger {
    /**
     * WorkManager's internal extras key for the work spec ID.
     * Brittle by design — see class-level KDoc for rationale.
     */
    private const val EXTRA_WORK_SPEC_ID_KEY = "EXTRA_WORK_SPEC_ID"

    /**
     * API level for Android 17 ([Build.VERSION_CODES.CINNAMON_BUN]).
     * Verified against android-37 android.jar: `CINNAMON_BUN = 37`.
     */
    private const val ANDROID_17: Int = Build.VERSION_CODES.CINNAMON_BUN

    /**
     * Returns `true` when [JobScheduler.getPendingJobReasonStats] is available.
     *
     * This is the testable mirror of the API gate. Production call sites must gate with an
     * inline `Build.VERSION.SDK_INT >= CINNAMON_BUN` check rather than this function — Android
     * Lint's `@RequiresApi` data-flow analysis recognizes the inline form but not a call through
     * a custom predicate, so routing the gate through here would raise a false `NewApi` error on
     * [logPendingReasonsFor].
     */
    fun shouldLog(sdkInt: Int): Boolean = sdkInt >= ANDROID_17

    /**
     * Formats a pending-reason stats map into a human-readable string for structured logs.
     *
     * Returns `"no reasons recorded"` for an empty map. Otherwise returns a comma-separated
     * list of `NAME=<ms>ms` entries sorted by reason code for deterministic output.
     *
     * Visible for testing.
     */
    fun formatReasonStats(stats: Map<Int, Long>): String {
        if (stats.isEmpty()) return "no reasons recorded"
        return stats.entries
            .sortedBy { it.key }
            .joinToString(", ") { (reason, ms) -> "${reasonName(reason)}=${ms}ms" }
    }

    private val reasonNames: Map<Int, String> =
        mapOf(
            JobScheduler.PENDING_JOB_REASON_APP to "APP",
            JobScheduler.PENDING_JOB_REASON_APP_STANDBY to "APP_STANDBY",
            JobScheduler.PENDING_JOB_REASON_BACKGROUND_RESTRICTION to "BACKGROUND_RESTRICTION",
            JobScheduler.PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW to "CONSTRAINT_BATTERY_NOT_LOW",
            JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CHARGING to "CONSTRAINT_CHARGING",
            JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY to "CONSTRAINT_CONNECTIVITY",
            JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONTENT_TRIGGER to "CONSTRAINT_CONTENT_TRIGGER",
            JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEADLINE to "CONSTRAINT_DEADLINE",
            JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEVICE_IDLE to "CONSTRAINT_DEVICE_IDLE",
            JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY to "CONSTRAINT_MINIMUM_LATENCY",
            JobScheduler.PENDING_JOB_REASON_CONSTRAINT_PREFETCH to "CONSTRAINT_PREFETCH",
            JobScheduler.PENDING_JOB_REASON_CONSTRAINT_STORAGE_NOT_LOW to "CONSTRAINT_STORAGE_NOT_LOW",
            JobScheduler.PENDING_JOB_REASON_DEVICE_STATE to "DEVICE_STATE",
            JobScheduler.PENDING_JOB_REASON_EXECUTING to "EXECUTING",
            JobScheduler.PENDING_JOB_REASON_INVALID_JOB_ID to "INVALID_JOB_ID",
            JobScheduler.PENDING_JOB_REASON_JOB_SCHEDULER_OPTIMIZATION to "JOB_SCHEDULER_OPTIMIZATION",
            JobScheduler.PENDING_JOB_REASON_QUOTA to "QUOTA",
            JobScheduler.PENDING_JOB_REASON_UNDEFINED to "UNDEFINED",
            JobScheduler.PENDING_JOB_REASON_USER to "USER",
        )

    private fun reasonName(code: Int): String = reasonNames[code] ?: "UNKNOWN($code)"

    /**
     * Looks up the WorkManager job for [workSpecId] in [JobScheduler.getAllPendingJobs] and
     * logs its pending-reason histogram at INFO level.
     *
     * The full lookup and stats call are wrapped in [runCatching] so that any failure —
     * including SecurityException, a missing extras key, or an unexpected API shape — is
     * caught and logged at WARN level. A diagnostics failure must never kill a worker.
     *
     * @param context used to retrieve the [JobScheduler] system service.
     * @param workSpecId the [WorkManager] work spec ID (from [WorkerParameters.id]).
     * @param correlationId opaque ID threaded through log lines for operator correlation.
     */
    @RequiresApi(ANDROID_17)
    fun logPendingReasonsFor(
        context: Context,
        workSpecId: String,
        correlationId: String,
    ) {
        runCatching {
            val scheduler =
                context.getSystemService(JobScheduler::class.java)
                    ?: return@runCatching

            val job =
                scheduler.allPendingJobs
                    .firstOrNull { it.extras.getString(EXTRA_WORK_SPEC_ID_KEY) == workSpecId }

            if (job == null) {
                logger.debug {
                    "JobReasonLogger: no pending job found for workSpecId=$workSpecId " +
                        "correlationId=$correlationId"
                }
                return@runCatching
            }

            val statsRaw: Map<Int, java.time.Duration> =
                scheduler.getPendingJobReasonStats(job.id)
            val statsMs: Map<Int, Long> = statsRaw.mapValues { (_, d) -> d.toMillis() }

            logger.info {
                "JobReasonLogger: workSpecId=$workSpecId correlationId=$correlationId " +
                    "reasons=${formatReasonStats(statsMs)}"
            }
        }.onFailure { t ->
            logger.warn(t) {
                "JobReasonLogger: failed to read pending-reason stats for " +
                    "workSpecId=$workSpecId correlationId=$correlationId"
            }
        }
    }
}
