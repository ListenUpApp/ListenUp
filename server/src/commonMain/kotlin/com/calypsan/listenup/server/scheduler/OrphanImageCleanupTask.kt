package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.server.io.statFile
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.util.runCatchingCancellable
import com.calypsan.listenup.server.logging.loggerFor
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val log = loggerFor<OrphanImageCleanupTask>()

/**
 * Periodic sweep that removes image files under `{imageHome}/contributors/` and
 * `{imageHome}/series/` that no live row points at.
 *
 * **Liveness is by reference, not by name.** Every writer — the metadata applier and the
 * upload route — names a photo or cover by the SHA-256 of its bytes (`contributors/<sha>.jpg`),
 * never by the entity id, so a file is live exactly when some non-tombstoned contributor's
 * `imagePath` (or series' `coverPath`) is that file. The rule is extension-agnostic: whatever
 * a row points at is kept, whatever nothing points at goes. An earlier rule that matched the
 * filename stem against entity ids called every real photo an orphan.
 *
 * Orphans arise from two sources:
 *  1. [com.calypsan.listenup.server.api.ContributorMetadataApplier] wrote a
 *     photo before the DB transaction committed and then the transaction rolled
 *     back — the file persists, the row does not. The same write-then-commit order
 *     means a sweep can land *between* the two, which is what [ORPHAN_GRACE] is for.
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
    private val clock: Clock = Clock.System,
) {
    /**
     * Start the sweep loop on [scope]. Returns the [Job] — cancel it to stop.
     */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                delay(interval)
                runCatchingCancellable { runOnce() }
                    .onFailure { log.warn(it) { "OrphanImageCleanupTask sweep failed; will retry next interval" } }
            }
        }

    /**
     * Sweeps both image directories once. Loads the image paths every live row points at,
     * then deletes any regular file in the directory that none of them names and that is
     * older than [ORPHAN_GRACE]. Testable without a running coroutine.
     */
    suspend fun runOnce() {
        val liveContributorImages = contributorRepository.listLiveImagePaths().toFilenames()
        val liveSeriesCovers = seriesRepository.listLiveCoverPaths().toFilenames()
        sweepDir(Path(imageHome, "contributors"), liveContributorImages, "contributor")
        sweepDir(Path(imageHome, "series"), liveSeriesCovers, "series")
    }

    private fun sweepDir(
        dir: Path,
        referencedNames: Set<String>,
        label: String,
    ) {
        if (!SystemFileSystem.exists(dir)) return
        val graceCutoffMs = clock.now().toEpochMilliseconds() - ORPHAN_GRACE.inWholeMilliseconds
        SystemFileSystem.list(dir).forEach { file ->
            if (file.name in referencedNames) return@forEach
            if (SystemFileSystem.metadataOrNull(file)?.isRegularFile != true) return@forEach
            val mtimeMs = statFile(file)?.mtimeMs ?: return@forEach
            if (mtimeMs > graceCutoffMs) return@forEach
            SystemFileSystem.delete(file, mustExist = false)
            log.info { "OrphanImageCleanupTask deleted orphan $label image: $file" }
        }
    }

    internal companion object {
        /**
         * How recently written a file must be to be spared even though no row references it yet.
         *
         * The applier writes the photo to disk *before* the DB transaction that references it
         * commits; a sweep landing in that gap would delete a photo that was about to become
         * live. Fifteen minutes covers a slow download plus its commit many times over, and
         * costs nothing — a real orphan is simply swept next time.
         */
        val ORPHAN_GRACE: Duration = 15.minutes
    }
}

/** Stored paths are `contributors/<sha>.jpg`; directory entries are compared by filename alone. */
private fun Set<String>.toFilenames(): Set<String> = mapTo(HashSet()) { it.substringAfterLast('/') }
