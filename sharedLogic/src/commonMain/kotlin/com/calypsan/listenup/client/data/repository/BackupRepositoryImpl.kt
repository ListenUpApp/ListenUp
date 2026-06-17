package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.remote.BackupRpcFactory
import com.calypsan.listenup.client.domain.repository.BackupRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private val logger = KotlinLogging.logger {}

/**
 * Production implementation of [BackupRepository].
 *
 * Every suspend call forwards to the [BackupService] RPC proxy obtained from
 * [BackupRpcFactory.get] and converts the wire [WireAppResult] to the client-layer
 * [AppResult] at this boundary. [CancellationException] is always re-raised.
 *
 * [observeProgress] unwraps the server-pushed [Flow]<[RpcEvent]<[BackupEvent]>> into a
 * plain [Flow]<[BackupEvent]>: [RpcEvent.Data] values are emitted; [RpcEvent.Error] and
 * [RpcEvent.Complete] are silently dropped (the guard already logs errors server-side).
 */
internal class BackupRepositoryImpl(
    private val rpcFactory: BackupRpcFactory,
) : BackupRepository {
    override suspend fun createBackup(includeImages: Boolean): AppResult<BackupSummary> =
        rpcCall { rpcFactory.get().createBackup(includeImages) }

    override suspend fun listBackups(): AppResult<List<BackupSummary>> = rpcCall { rpcFactory.get().listBackups() }

    override suspend fun deleteBackup(id: BackupId): AppResult<Unit> = rpcCall { rpcFactory.get().deleteBackup(id) }

    override suspend fun restoreBackup(id: BackupId): AppResult<RestoreResult> =
        rpcCall { rpcFactory.get().restoreBackup(id) }

    override fun observeProgress(): Flow<BackupEvent> =
        flow {
            // Acquire the proxy at collection time so the cold flow stays truly cold.
            // The service returns Flow<RpcEvent<BackupEvent>>; emit only the Data payload.
            // Error events are already logged by the KSP-generated guard on the server side.
            rpcFactory.get().observeProgress().collect { event ->
                when (event) {
                    is RpcEvent.Data -> emit(event.value)
                    is RpcEvent.Error -> logger.warn { "observeProgress received RpcEvent.Error: ${event.error}" }
                    is RpcEvent.Complete -> Unit
                }
            }
        }

    /**
     * Runs an RPC call that returns a wire [WireAppResult] and converts it to the
     * client-layer [AppResult]. [CancellationException] is re-raised; all other
     * throwables become [AppResult.Failure] via [ErrorMapper].
     */
    private suspend fun <T> rpcCall(block: suspend () -> WireAppResult<T>): AppResult<T> =
        try {
            when (val result = block()) {
                is WireAppResult.Success -> AppResult.Success(result.data)
                is WireAppResult.Failure -> AppResult.Failure(result.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppResult.Failure(ErrorMapper.map(e))
        }
}
