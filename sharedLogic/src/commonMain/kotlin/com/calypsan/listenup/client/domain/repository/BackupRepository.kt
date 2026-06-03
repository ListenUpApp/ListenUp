package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BackupId
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for the admin backup/restore domain.
 *
 * Suspend methods return [AppResult] so callers exhaustively fold over typed
 * [com.calypsan.listenup.api.error.BackupError] values instead of catching
 * exceptions. [observeProgress] is a cold server-pushed stream of progress
 * events for both backup creation and live restore; call sites convert it to
 * hot state with `.stateIn(scope)` as needed.
 *
 * Implementations back this contract with the [com.calypsan.listenup.api.BackupService]
 * RPC proxy via [com.calypsan.listenup.client.data.remote.BackupRpcFactory].
 */
interface BackupRepository {
    /**
     * Initiates backup creation on the server and waits for the resulting
     * [BackupSummary]. Pass [includeImages] to include the covers/avatars
     * image directories in the archive.
     */
    suspend fun createBackup(includeImages: Boolean): AppResult<BackupSummary>

    /** Returns the list of all stored backup archives, most-recent first. */
    suspend fun listBackups(): AppResult<List<BackupSummary>>

    /** Soft-deletes the backup archive identified by [id]. */
    suspend fun deleteBackup(id: BackupId): AppResult<Unit>

    /**
     * Initiates a live in-process restore from the archive identified by [id].
     * Returns the [RestoreResult] when the server is back online after the swap.
     */
    suspend fun restoreBackup(id: BackupId): AppResult<RestoreResult>

    /**
     * Cold server-pushed stream of [BackupEvent] progress markers emitted during
     * backup creation and restore. Subscribe before triggering the operation to
     * avoid missing early events. Convert to hot state at the call site with
     * `.stateIn(scope)`.
     */
    fun observeProgress(): Flow<BackupEvent>
}
