package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.core.BackupId
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/** Admin-only backup/restore surface. Binary upload/download live on REST routes. */
@Rpc
interface BackupService {
    suspend fun createBackup(includeImages: Boolean): AppResult<BackupSummary>

    suspend fun listBackups(): AppResult<List<BackupSummary>>

    suspend fun getBackup(id: BackupId): AppResult<BackupSummary>

    suspend fun deleteBackup(id: BackupId): AppResult<Unit>

    suspend fun restoreBackup(id: BackupId): AppResult<RestoreResult>

    fun observeProgress(): Flow<RpcEvent<BackupEvent>>
}
