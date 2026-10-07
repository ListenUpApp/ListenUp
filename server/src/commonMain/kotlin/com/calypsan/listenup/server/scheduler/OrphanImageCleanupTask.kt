package com.calypsan.listenup.server.scheduler

import kotlinx.coroutines.CancellationException

import com.calypsan.listenup.server.io.statFile
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.util.runCatchingCancellable
import com.calypsan.listenup.server.logging.loggerFor
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val log = loggerFor<OrphanImageCleanupTask>()

/**
 * What keeps a file in `covers/` alive: every cover path a book row names (soft-deleted rows too — a book can be
 * revived) and every cover path a live match receipt's snapshot names (Undo restores it). Either read may throw;
 * the sweep then deletes nothing.
 */
internal interface CoverReferences {
    /** Every `cover_path` any book row holds. */
    suspend fun bookCoverPaths(): Set<String>

    /** Every cover path a live match receipt keeps for Undo. */
    suspend fun pinnedCoverPaths(): Set<String>
}

/**
 * The contributor photos a live person-match receipt keeps for Undo. May throw; the `contributors/` sweep then
 * deletes nothing.
 */
internal fun interface PhotoPins {
    /** Every `contributors/` path a live person receipt's snapshot names. */
    suspend fun pinnedPhotoPaths(): Set<String>
}

/**
 * Periodic sweep that removes image files under `{imageHome}/contributors/`,
 * `{imageHome}/series/` and `{imageHome}/covers/` that nothing points at.
 *
 * **Liveness is by reference, not by name.** Every writer — the metadata applier and the
 * upload route — names a photo or cover by the SHA-256 of its bytes (`contributors/<sha>.jpg`),
 * never by the entity id, so a file is live exactly when some non-tombstoned contributor's
 * `imagePath` (or series' `coverPath`) is that file — or a live person-match receipt keeps it for Undo
 * ([PhotoPins]). The rule is extension-agnostic: whatever
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
 * Runs every [interval] (default 7 days), and once at boot unless the persisted last run says a
 * sweep happened within [interval] — a server restarted nightly would otherwise never reach a
 * 7-day timer, and never sweep at all.
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
    /** Null leaves `covers/` alone (the sweep predates cover matching). */
    private val coverReferences: CoverReferences? = null,
    /** Null keeps only what live rows name in `contributors/` (the sweep predates person matching). */
    private val photoPins: PhotoPins? = null,
    private val interval: Duration = 7.days,
    private val clock: Clock = Clock.System,
    /** Nullable — without it the last run is not persisted and every boot sweeps at once. */
    private val settings: ServerSettingsRepository? = null,
    /** Upper bound on the random delay before the first sweep; zero in tests. */
    private val startJitter: Duration = START_JITTER,
) {
    /**
     * Start the sweep loop on [scope]. Returns the [Job] — cancel it to stop.
     *
     * Sweeps at boot — after a short random delay up to [startJitter], so tasks restarted together
     * do not hit the DB in the same instant — unless the persisted last run says one happened
     * within [interval], in which case it waits out the remainder. A nightly-restarted server thus
     * sweeps once per [interval], never once per restart and never never.
     */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            delay(Random.nextLong(startJitter.inWholeMilliseconds + 1))
            delay(untilNextSweep())
            while (isActive) {
                runCatchingCancellable {
                    runOnce()
                    recordSweep()
                }.onFailure { log.warn(it) { "OrphanImageCleanupTask sweep failed; will retry next interval" } }
                delay(interval)
            }
        }

    /**
     * Sweeps both image directories once. Loads the image paths every live row points at,
     * then deletes any regular file in the directory that none of them names and that is
     * older than [ORPHAN_GRACE]. Testable without a running coroutine.
     */
    suspend fun runOnce() {
        val liveContributorImages = contributorRepository.listLiveImagePaths()
        val liveSeriesCovers = seriesRepository.listLiveCoverPaths().toFilenames()
        pinnedPhotos()?.let { pinned ->
            sweepDir(Path(imageHome, "contributors"), (liveContributorImages + pinned).toFilenames(), "contributor")
        }
        sweepDir(Path(imageHome, "series"), liveSeriesCovers, "series")
        sweepCovers()
    }

    /**
     * Sweeps `covers/` (decision D1: a matched cover is named by its content, so the file it replaced stays for
     * Undo and nothing overwrites it). A file is kept when a book row names it, when a live receipt keeps it, or
     * when it is younger than [ORPHAN_GRACE]. **Fail closed:** if either read throws, or no book row names any
     * cover at all (an empty or half-restored database looks exactly like that), nothing is deleted.
     */
    private suspend fun sweepCovers() {
        val references = coverReferences ?: return
        val live =
            try {
                val books = references.bookCoverPaths()
                if (books.isEmpty()) {
                    log.warn { "OrphanImageCleanupTask: no book names a cover — leaving covers/ untouched" }
                    return
                }
                books + references.pinnedCoverPaths()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "OrphanImageCleanupTask couldn't read which covers are live — leaving covers/ untouched" }
                return
            }
        sweepDir(Path(imageHome, "covers"), live.toFilenames(), "cover")
    }

    /**
     * The photos live person receipts keep for Undo; empty without [photoPins]. **Fail closed:** null when they
     * can't be read, and the `contributors/` sweep then deletes nothing.
     */
    private suspend fun pinnedPhotos(): Set<String>? {
        val pins = photoPins ?: return emptySet()
        return try {
            pins.pinnedPhotoPaths()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "OrphanImageCleanupTask couldn't read which photos are pinned — leaving contributors/ untouched" }
            null
        }
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

    /** What is left of [interval] since the last recorded sweep; zero when unknown or unreadable. */
    private suspend fun untilNextSweep(): Duration =
        runCatchingCancellable {
            val lastRunMs =
                settings?.getValue(LAST_RUN_KEY)?.toLongOrNull() ?: return@runCatchingCancellable Duration.ZERO
            val elapsed = (clock.now().toEpochMilliseconds() - lastRunMs).milliseconds
            (interval - elapsed).coerceIn(Duration.ZERO, interval)
        }.onFailure { log.warn(it) { "OrphanImageCleanupTask could not read its last run; sweeping now" } }
            .getOrDefault(Duration.ZERO)

    /** Recorded only after a sweep succeeded — a failed sweep must not push the next one out. */
    private suspend fun recordSweep() {
        settings?.setValue(LAST_RUN_KEY, clock.now().toEpochMilliseconds().toString())
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

        /** `server_settings` key holding the epoch-millis of the last successful sweep. */
        const val LAST_RUN_KEY = "scheduler.orphanImageCleanup.lastRunAtMs"

        /** Enough to spread the boot sweeps of the cleanup tasks apart; small enough to be invisible. */
        private val START_JITTER = 10.seconds
    }
}

/** Stored paths are `contributors/<sha>.jpg`; directory entries are compared by filename alone. */
private fun Set<String>.toFilenames(): Set<String> = mapTo(HashSet()) { it.substringAfterLast('/') }
