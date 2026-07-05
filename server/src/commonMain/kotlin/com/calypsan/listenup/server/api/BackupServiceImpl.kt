package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.BackupService
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BackupError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.backup.BackupArchive
import com.calypsan.listenup.server.backup.BackupPaths
import com.calypsan.listenup.server.backup.RestoreOrchestrator
import com.calypsan.listenup.server.backup.isSafeBackupId
import com.calypsan.listenup.server.io.fileIoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Clock

/**
 * Admin-only backup/restore surface. All methods are gated behind [requireAdmin].
 *
 * Route handlers call [copyWith] to bind each request to the authenticated principal;
 * the Koin singleton carries an unscoped placeholder that throws if invoked without binding.
 */
class BackupServiceImpl(
    private val paths: BackupPaths,
    private val archive: BackupArchive,
    private val restoreOrchestrator: RestoreOrchestrator,
    private val eventBus: MutableSharedFlow<BackupEvent>,
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : BackupService {
    /** Returns a copy scoped to the given [provider]. Route handlers call this per-request. */
    fun copyWith(provider: PrincipalProvider): BackupServiceImpl =
        BackupServiceImpl(paths, archive, restoreOrchestrator, eventBus, provider)

    override suspend fun createBackup(includeImages: Boolean): AppResult<BackupSummary> {
        requireAdmin()?.let { return it }
        return try {
            val id =
                "backup-" +
                    Clock.System
                        .now()
                        .toString()
                        .replace(":", "-")
            val archivePath = archive.create(id, includeImages) { eventBus.tryEmit(it) }
            val summary = summaryFrom(id, archivePath)
            eventBus.tryEmit(BackupEvent.Created(summary))
            AppResult.Success(summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(BackupError.SnapshotFailed(debugInfo = e.message))
        }
    }

    override suspend fun listBackups(): AppResult<List<BackupSummary>> {
        requireAdmin()?.let { return it }
        return withContext(fileIoDispatcher) {
            val backupsDir = paths.backupsDir
            if (!SystemFileSystem.exists(backupsDir)) return@withContext AppResult.Success(emptyList())

            val summaries =
                SystemFileSystem
                    .list(backupsDir)
                    .filter { it.name.endsWith(".listenup.zip") }
                    .mapNotNull { archivePath ->
                        val stem = archivePath.name.removeSuffix(".listenup.zip")
                        runCatching { summaryFrom(stem, archivePath) }.getOrNull()
                    }.sortedByDescending { it.createdAt }

            AppResult.Success(summaries)
        }
    }

    override suspend fun getBackup(id: BackupId): AppResult<BackupSummary> {
        requireAdmin()?.let { return it }
        return withContext(fileIoDispatcher) {
            if (!isSafeBackupId(id.value)) {
                return@withContext AppResult.Failure(BackupError.BackupNotFound())
            }
            val archivePath = paths.archiveFor(id.value)
            if (!SystemFileSystem.exists(archivePath)) {
                return@withContext AppResult.Failure(BackupError.BackupNotFound())
            }
            AppResult.Success(summaryFrom(id.value, archivePath))
        }
    }

    override suspend fun deleteBackup(id: BackupId): AppResult<Unit> {
        requireAdmin()?.let { return it }
        return withContext(fileIoDispatcher) {
            if (!isSafeBackupId(id.value)) {
                return@withContext AppResult.Failure(BackupError.BackupNotFound())
            }
            val archivePath = paths.archiveFor(id.value)
            val existed = SystemFileSystem.exists(archivePath)
            SystemFileSystem.delete(archivePath, mustExist = false)
            if (!existed) {
                AppResult.Failure(BackupError.BackupNotFound())
            } else {
                AppResult.Success(Unit)
            }
        }
    }

    override suspend fun restoreBackup(id: BackupId): AppResult<RestoreResult> {
        requireAdmin()?.let { return it }
        if (!isSafeBackupId(id.value)) return AppResult.Failure(BackupError.BackupNotFound())
        return restoreOrchestrator.restore(id)
    }

    override fun observeProgress(): Flow<RpcEvent<BackupEvent>> =
        if (principal.current()?.role?.isAdmin() == true) {
            eventBus.map { RpcEvent.Data(it) }
        } else {
            emptyFlow()
        }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /** null = allowed; a Failure (PermissionDenied / SessionExpired) otherwise. */
    private fun requireAdmin(): AppResult.Failure? {
        val caller = principal.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        return if (caller.role.isAdmin()) null else AppResult.Failure(AuthError.PermissionDenied())
    }

    private fun UserRole.isAdmin(): Boolean = this == UserRole.ROOT || this == UserRole.ADMIN

    private fun summaryFrom(
        id: String,
        archivePath: Path,
    ): BackupSummary {
        val manifest = archive.open(archivePath)
        val sizeBytes = SystemFileSystem.metadataOrNull(archivePath)?.size ?: 0L
        return BackupSummary(
            id = BackupId(id),
            createdAt = manifest.createdAt,
            sizeBytes = sizeBytes,
            includesImages = manifest.includesImages,
            schemaVersion = manifest.schemaVersion,
            appVersion = manifest.appVersion,
            bookCount = manifest.bookCount,
            userCount = manifest.userCount,
        )
    }
}
