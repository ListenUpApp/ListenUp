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
import com.calypsan.listenup.server.sync.ChangeBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val logger = loggerFor<RestoreOrchestrator>()

/**
 * Orchestrates a live in-process restore: validate → drain → safety-copy → extract → close pool
 * → swap db file in place → reopen pool → migrate → done. On any failure in the swap/migrate
 * window, rolls back to the safety copy and reopens the pool on the original db.
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
                maintenance.drain()

                // 3. Safety copy (pool still live)
                SystemFileSystem.createDirectories(paths.rollbackDir)
                val rollbackDb = Path(paths.rollbackDir, "listenup.db")
                SystemFileSystem.delete(rollbackDb, mustExist = false)
                dbHandle.vacuumInto(rollbackDb.toString())
                val rollbackCovers = copyDirAside(paths.coversDir, Path(paths.rollbackDir, "covers"))
                val rollbackAvatars = copyDirAside(paths.avatarsDir, Path(paths.rollbackDir, "avatars"))

                // 4. Extract snapshot to staging
                if (SystemFileSystem.exists(paths.stagingDir)) deleteRecursively(paths.stagingDir)
                SystemFileSystem.createDirectories(paths.stagingDir)
                archive.extractTo(archivePath, paths.stagingDir)

                // 5. Swap — NonCancellable so a cancellation mid-swap cannot leave the
                // pool closed forever. Once closePool() is called the swap MUST run to a
                // consistent end (pool reopened on either success or rollback path) before
                // cancellation can propagate.
                withContext(NonCancellable) {
                    eventBus.tryEmit(BackupEvent.Swapping)
                    dbHandle.closePool()
                    try {
                        swapFile(Path(paths.stagingDir, "listenup.db"), paths.dbFile)
                        deleteDbSidecars()
                        if (manifest.includesImages) {
                            swapDir(Path(paths.stagingDir, "covers"), paths.coversDir)
                            swapDir(Path(paths.stagingDir, "avatars"), paths.avatarsDir)
                        }
                        dbHandle.reopenPool()

                        // 6. migrate the swapped-in db forward to the current schema
                        eventBus.tryEmit(BackupEvent.Migrating)
                        val migratedTo = dbHandle.migrate() ?: manifest.schemaVersion

                        eventBus.tryEmit(BackupEvent.RestoreComplete(manifest.includesImages))

                        // The DB was swapped wholesale; connected devices' cursors are AHEAD of the
                        // restored revision counter, so neither forward catch-up nor CursorStale can
                        // see the change. Broadcast the digest-reconcile accelerator so every
                        // connected client re-derives, exactly like ImportApplier's bulk path.
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
                        rollback(rollbackDb, rollbackCovers, rollbackAvatars)
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
     */
    private fun rollback(
        rollbackDb: Path,
        rollbackCovers: Path?,
        rollbackAvatars: Path?,
    ) {
        try {
            if (SystemFileSystem.exists(rollbackDb)) {
                swapFile(rollbackDb, paths.dbFile)
                deleteDbSidecars()
            }
            if (rollbackCovers != null &&
                SystemFileSystem.exists(rollbackCovers)
            ) {
                swapDir(rollbackCovers, paths.coversDir)
            }
            if (rollbackAvatars != null &&
                SystemFileSystem.exists(rollbackAvatars)
            ) {
                swapDir(rollbackAvatars, paths.avatarsDir)
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
