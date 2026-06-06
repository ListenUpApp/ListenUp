package com.calypsan.listenup.server.backup

import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.error.BackupError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.server.db.DatabaseHandle
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val logger = KotlinLogging.logger {}

/**
 * Orchestrates a live in-process restore: validate → drain → safety-copy → extract → close pool
 * → swap db file in place → reopen pool → Flyway migrate → done. On any failure in the swap/migrate
 * window, rolls back to the safety copy and reopens the pool on the original db.
 *
 * The [maintenance] gate is single-flight: only one restore can run at a time.
 * The [DatabaseHandle.database] object identity is preserved across the swap so repositories
 * that captured it at construction keep working after the restore.
 */
class RestoreOrchestrator(
    private val paths: BackupPaths,
    private val archive: BackupArchive,
    private val dbHandle: DatabaseHandle,
    private val maintenance: MaintenanceState,
    private val eventBus: MutableSharedFlow<BackupEvent>,
) {
    suspend fun restore(id: BackupId): AppResult<RestoreResult> =
        withContext(Dispatchers.IO) {
            val archivePath = paths.archiveFor(id.value)
            if (!Files.exists(archivePath)) {
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
                Files.createDirectories(paths.rollbackDir)
                val rollbackDb = paths.rollbackDir.resolve("listenup.db")
                Files.deleteIfExists(rollbackDb)
                dbHandle.vacuumInto(rollbackDb)
                val rollbackCovers = copyDirAside(paths.coversDir, paths.rollbackDir.resolve("covers"))
                val rollbackAvatars = copyDirAside(paths.avatarsDir, paths.rollbackDir.resolve("avatars"))

                // 4. Extract snapshot to staging
                if (Files.exists(paths.stagingDir)) deleteRecursively(paths.stagingDir)
                Files.createDirectories(paths.stagingDir)
                archive.extractTo(archivePath, paths.stagingDir)

                // 5. Swap — NonCancellable so a cancellation mid-swap cannot leave the
                // pool closed forever. Once closePool() is called the swap MUST run to a
                // consistent end (pool reopened on either success or rollback path) before
                // cancellation can propagate.
                withContext(NonCancellable) {
                    eventBus.tryEmit(BackupEvent.Swapping)
                    dbHandle.closePool()
                    try {
                        val dbFileName = paths.dbFile.fileName.toString()
                        swapFile(paths.stagingDir.resolve("listenup.db"), paths.dbFile)
                        Files.deleteIfExists(paths.dbFile.resolveSibling("$dbFileName-wal"))
                        Files.deleteIfExists(paths.dbFile.resolveSibling("$dbFileName-shm"))
                        if (manifest.includesImages) {
                            swapDir(paths.stagingDir.resolve("covers"), paths.coversDir)
                            swapDir(paths.stagingDir.resolve("avatars"), paths.avatarsDir)
                        }
                        dbHandle.reopenPool()

                        // 6. Flyway migrate
                        eventBus.tryEmit(BackupEvent.Migrating)
                        val migratedTo = dbHandle.migrate() ?: manifest.schemaVersion

                        eventBus.tryEmit(BackupEvent.RestoreComplete(manifest.includesImages))
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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
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
     * Copies [src] directory aside to [dest], creating [dest] if it doesn't exist.
     * Returns the dest path (or null if src doesn't exist — nothing to copy).
     */
    private fun copyDirAside(
        src: Path,
        dest: Path,
    ): Path? {
        if (!Files.exists(src)) return null
        Files.createDirectories(dest)
        Files.walk(src).forEach { srcPath ->
            val relative = src.relativize(srcPath)
            val destPath = dest.resolve(relative)
            if (Files.isDirectory(srcPath)) {
                Files.createDirectories(destPath)
            } else {
                Files.copy(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return dest
    }

    /** Moves [src] to [dest], replacing any existing file at [dest]. */
    private fun swapFile(
        src: Path,
        dest: Path,
    ) {
        Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Replaces [dest] directory with [src]: deletes [dest] if it exists, then moves [src] in place.
     * Gracefully handles a missing [src] (nothing staged → nothing to swap).
     */
    private fun swapDir(
        src: Path,
        dest: Path,
    ) {
        if (!Files.exists(src)) return
        if (Files.exists(dest)) deleteRecursively(dest)
        Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Recursively deletes [path] and all children. No-op if [path] doesn't exist. */
    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files
            .walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
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
            if (Files.exists(rollbackDb)) {
                swapFile(rollbackDb, paths.dbFile)
                val dbFileName = paths.dbFile.fileName.toString()
                Files.deleteIfExists(paths.dbFile.resolveSibling("$dbFileName-wal"))
                Files.deleteIfExists(paths.dbFile.resolveSibling("$dbFileName-shm"))
            }
            if (rollbackCovers != null && Files.exists(rollbackCovers)) swapDir(rollbackCovers, paths.coversDir)
            if (rollbackAvatars != null && Files.exists(rollbackAvatars)) swapDir(rollbackAvatars, paths.avatarsDir)
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
