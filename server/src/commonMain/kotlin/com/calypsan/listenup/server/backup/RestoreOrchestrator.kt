package com.calypsan.listenup.server.backup

import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.error.BackupError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.server.db.DatabaseHandle
import com.calypsan.listenup.server.io.copyDirectoryRecursively
import com.calypsan.listenup.server.io.deleteRecursively
import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.media.ManagedImageDirs
import com.calypsan.listenup.server.sync.ChangeBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val logger = loggerFor<RestoreOrchestrator>()

private const val ROLLBACK_DB_NAME = "listenup.db"

/**
 * Orchestrates a live in-process restore: validate → drain → extract → copy image dirs aside → close
 * pool → safety-copy the db (nothing can write now) → swap db file in place → migrate → reopen pool →
 * done. On any failure in the swap/migrate window, rolls back to the safety copy and reopens the pool
 * on the original db. A failure before the pool closes (e.g. extraction) touches nothing.
 *
 * The [maintenance] gate is single-flight: only one restore can run at a time.
 * The [DatabaseHandle.sqlDriver] object identity is preserved across the swap so repositories
 * that captured it at construction keep working after the restore.
 */
class RestoreOrchestrator(
    private val paths: BackupPaths,
    private val archive: BackupArchive,
    private val dbHandle: DatabaseHandle,
    private val maintenance: MaintenanceState,
    private val eventBus: MutableSharedFlow<BackupEvent>,
    private val changeBus: ChangeBus,
) {
    suspend fun restore(id: BackupId): AppResult<RestoreResult> =
        withContext(fileIoDispatcher) {
            val archivePath = paths.archiveFor(id.value)
            if (!SystemFileSystem.exists(archivePath)) {
                return@withContext AppResult.Failure(BackupError.BackupNotFound())
            }

            // 1. Validate (touches nothing)
            eventBus.tryEmit(BackupEvent.Validating)
            val manifest =
                try {
                    archive.validate(archivePath)
                } catch (e: BackupArchive.CorruptArchiveException) {
                    return@withContext AppResult.Failure(BackupError.CorruptArchive(debugInfo = e.message))
                }

            val currentSchema = dbHandle.currentSchemaVersion() ?: "0"
            val backupSchema = manifest.schemaVersion.toIntOrNull() ?: 0
            val currentSchemaInt = currentSchema.toIntOrNull() ?: 0
            if (backupSchema > currentSchemaInt) {
                return@withContext AppResult.Failure(BackupError.IncompatibleSchema())
            }

            // 2. Single-flight gate
            if (!maintenance.enter()) {
                return@withContext AppResult.Failure(BackupError.RestoreInProgress())
            }

            try {
                eventBus.tryEmit(BackupEvent.Draining)
                if (!maintenance.drain()) {
                    logger.warn {
                        "restore: requests still in flight after drain timeout; they fail once the database closes"
                    }
                }

                // 3. Extract snapshot to staging (slow; the database is untouched and still serving)
                if (SystemFileSystem.exists(paths.stagingDir)) deleteRecursively(paths.stagingDir)
                SystemFileSystem.createDirectories(paths.stagingDir)
                archive.extractTo(archivePath, paths.stagingDir)

                // 4. Image directories aside. A stale database copy from an earlier attempt must not
                // survive into this one, or rollback could swap it in.
                SystemFileSystem.createDirectories(paths.rollbackDir)
                val rollbackDb = Path(paths.rollbackDir, ROLLBACK_DB_NAME)
                val rollbackDbPartial = Path(paths.rollbackDir, "$ROLLBACK_DB_NAME.partial")
                SystemFileSystem.delete(rollbackDb, mustExist = false)
                SystemFileSystem.delete(rollbackDbPartial, mustExist = false)
                val asideDirs =
                    ManagedImageDirs.ALL.mapNotNull { name ->
                        copyDirAside(paths.imageDir(name), Path(paths.rollbackDir, name))
                            ?.let { aside -> aside to paths.imageDir(name) }
                    }

                // 5. Swap — NonCancellable so a cancellation mid-swap cannot leave the
                // pool closed forever. Once closePool() is called the swap MUST run to a
                // consistent end (pool reopened on either success or rollback path) before
                // cancellation can propagate.
                withContext(NonCancellable) {
                    eventBus.tryEmit(BackupEvent.Swapping)
                    val revisionBeforeRestore = dbHandle.revisionCounter()
                    dbHandle.closePool()
                    // Deliberate: inside NonCancellable every throwable, an explicit cancellation included,
                    // must roll back and reopen the pool — see the catch below.
                    @Suppress("SuspendFunSwallowedCancellation")
                    try {
                        // Safety copy of the database as it stands now that the driver refuses work. Written
                        // to a .partial name and renamed only when complete, so rollback never sees half a copy.
                        dbHandle.vacuumInto(rollbackDbPartial.toString())
                        swapFile(rollbackDbPartial, rollbackDb)

                        swapFile(Path(paths.stagingDir, "listenup.db"), paths.dbFile)
                        deleteDbSidecars()
                        if (manifest.includesImages) {
                            ManagedImageDirs.ALL.forEach { swapDir(Path(paths.stagingDir, it), paths.imageDir(it)) }
                        }

                        // 6. migrate the swapped-in db forward to the current schema while the pool is
                        // still closed (the migration runs on its own admin connection), so no request or
                        // worker ever queries an old-schema file with today's SQL.
                        eventBus.tryEmit(BackupEvent.Migrating)
                        val migratedTo = dbHandle.migrate() ?: manifest.schemaVersion
                        revisionBeforeRestore?.let { dbHandle.raiseRevisionCounterTo(it) }
                        dbHandle.reopenPool()

                        eventBus.tryEmit(BackupEvent.RestoreComplete(manifest.includesImages))

                        // The revision counter was carried forward, so new writes reach every client
                        // normally. The restored rows still differ from what connected clients hold, and
                        // only a digest reconcile finds that: broadcast the accelerator so every connected
                        // client re-derives, exactly like ImportApplier's bulk path.
                        changeBus.broadcastControl(SyncControl.LibraryDataChanged)

                        deleteRecursively(paths.rollbackDir)
                        deleteRecursively(paths.stagingDir)

                        AppResult.Success(
                            RestoreResult(
                                restoredFrom = id,
                                includedImages = manifest.includesImages,
                                schemaMigratedFrom = manifest.schemaVersion,
                                schemaMigratedTo = migratedTo,
                            ),
                        )
                    } catch (e: Exception) {
                        // Inside NonCancellable there is no cooperative cancellation to preserve;
                        // any throwable (incl. an explicit CancellationException) must trigger
                        // rollback so the pool is always reopened.
                        logger.error(e) { "restore swap/migrate failed — rolling back to safety copy" }
                        rollback(
                            rollbackDb = rollbackDb,
                            asideDirs = asideDirs,
                        )
                        deleteRecursively(paths.stagingDir)
                        eventBus.tryEmit(BackupEvent.RolledBack(e.message ?: "restore failed"))
                        AppResult.Failure(BackupError.RestoreFailed(rolledBack = true, debugInfo = e.message))
                    }
                }
            } finally {
                maintenance.exit()
            }
        }

    /**
     * Copies [src] directory aside to [dest]. Returns [dest] (or null if [src] doesn't exist —
     * nothing to copy).
     */
    private fun copyDirAside(
        src: Path,
        dest: Path,
    ): Path? {
        if (!SystemFileSystem.exists(src)) return null
        copyDirectoryRecursively(src, dest)
        return dest
    }

    /** Moves [src] to [dest], replacing any existing file at [dest] (same-filesystem atomic rename). */
    private fun swapFile(
        src: Path,
        dest: Path,
    ) {
        SystemFileSystem.atomicMove(src, dest)
    }

    /**
     * Replaces [dest] directory with [src]: deletes [dest] if it exists, then moves [src] in place.
     * Gracefully handles a missing [src] (nothing staged → nothing to swap).
     */
    private fun swapDir(
        src: Path,
        dest: Path,
    ) {
        if (!SystemFileSystem.exists(src)) return
        if (SystemFileSystem.exists(dest)) deleteRecursively(dest)
        SystemFileSystem.atomicMove(src, dest)
    }

    /** Deletes the SQLite `-wal` / `-shm` sidecar files next to the live db, if present. */
    private fun deleteDbSidecars() {
        val parent = paths.dbFile.parent ?: return
        val name = paths.dbFile.name
        SystemFileSystem.delete(Path(parent, "$name-wal"), mustExist = false)
        SystemFileSystem.delete(Path(parent, "$name-shm"), mustExist = false)
    }

    /**
     * Rolls back to the safety copy after a failed swap/migrate.
     *
     * The pool is already hard-closed by [restore] before the swap begins. This function swaps
     * the safety copy back in place and then reopens the pool in a [finally] block so the server
     * is never left with a closed pool regardless of whether the file swap itself succeeds.
     * [asideDirs] pairs each directory that was copied aside with the live directory it restores.
     */
    private fun rollback(
        rollbackDb: Path,
        asideDirs: List<Pair<Path, Path>>,
    ) {
        try {
            if (SystemFileSystem.exists(rollbackDb)) {
                swapFile(rollbackDb, paths.dbFile)
                deleteDbSidecars()
            }
            for ((aside, live) in asideDirs) {
                if (SystemFileSystem.exists(aside)) swapDir(aside, live)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "rollback failed — server may be in an inconsistent state" }
        } finally {
            // Always reopen so the server isn't left with a closed pool.
            try {
                dbHandle.reopenPool()
            } catch (e: Exception) {
                logger.error(e) { "failed to reopen pool after rollback — server may be unavailable" }
            }
        }
    }
}
