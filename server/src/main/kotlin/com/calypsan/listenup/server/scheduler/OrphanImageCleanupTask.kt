package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val log = KotlinLogging.logger {}

/**
 * Periodic sweep that removes `.jpg` image files under `{imageHome}/contributors/`
 * and `{imageHome}/series/` whose filename stem (the part before `.jpg`) does not
 * match the id of any non-tombstoned contributor or series in the database.
 *
 * Orphans arise from two sources:
 *  1. [com.calypsan.listenup.server.api.ContributorMetadataApplier] wrote a
 *     photo before the DB transaction committed and then the transaction rolled
 *     back — the file persists, the row does not.
 *  2. A contributor or series row was soft-deleted (tombstoned) without unlinking
 *     its image — the row is gone, but the file lingers.
 *
 * Runs every [interval] (default 7 days). The first sweep runs after [interval]
 * — startup-time scans on a large filesystem can stall boot.
 *
 * Runs on the supplied [CoroutineScope]; the caller cancels the returned [Job]
 * when the application stops. The loop re-raises [CancellationException] so
 * structured concurrency is respected.
 *
 * Mirrors [com.calypsan.listenup.server.scheduler.ActiveSessionCleanupTask].
 */
internal class OrphanImageCleanupTask(
    private val contributorRepository: ContributorRepository,
    private val seriesRepository: SeriesRepository,
    private val imageHome: Path,
    private val interval: Duration = 7.days,
) {
    /**
     * Start the sweep loop on [scope]. Returns the [Job] — cancel it to stop.
     */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                delay(interval)
                try {
                    runOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "OrphanImageCleanupTask sweep failed; will retry next interval" }
                }
            }
        }

    /**
     * Sweeps both image directories once. Loads the full set of live entity ids
     * (non-tombstoned) from the DB at the start, then deletes any `.jpg` file
     * whose stem is not in that set. Testable without a running coroutine.
     */
    suspend fun runOnce() {
        val liveContributorIds = contributorRepository.listLiveIds()
        val liveSeriesIds = seriesRepository.listLiveIds()
        val contributorDir = Path(imageHome, "contributors")
        val seriesDir = Path(imageHome, "series")
        sweepDir(contributorDir, liveContributorIds, "contributor")
        sweepDir(seriesDir, liveSeriesIds, "series")
    }

    private fun sweepDir(
        dir: Path,
        liveIds: Set<String>,
        label: String,
    ) {
        if (!SystemFileSystem.exists(dir)) return
        SystemFileSystem.list(dir).forEach { file ->
            val name = file.name
            if (!name.endsWith(".jpg")) return@forEach
            val id = name.removeSuffix(".jpg")
            if (id !in liveIds) {
                SystemFileSystem.delete(file, mustExist = false)
                log.info { "OrphanImageCleanupTask deleted orphan $label image: $file" }
            }
        }
    }
}
