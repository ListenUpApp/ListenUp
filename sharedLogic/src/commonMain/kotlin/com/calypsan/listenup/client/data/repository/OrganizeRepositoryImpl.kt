package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.organize.OrganizePreviewDto
import com.calypsan.listenup.api.dto.organize.OrganizeRunEvent
import com.calypsan.listenup.api.dto.organize.OrganizeRunId
import com.calypsan.listenup.api.dto.organize.OrganizeSettingsDto
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.remote.OrganizeRpcFactory
import com.calypsan.listenup.client.domain.repository.OrganizeRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private val logger = KotlinLogging.logger {}

/**
 * [OrganizeRepository] over the [com.calypsan.listenup.api.OrganizeService] kotlinx.rpc proxy.
 *
 * [observeRun] unwraps the server-pushed [Flow]<[RpcEvent]<[OrganizeRunEvent]>> into a plain
 * [Flow]<[OrganizeRunEvent]>: [RpcEvent.Data] values are emitted; [RpcEvent.Error] and
 * [RpcEvent.Complete] are logged/dropped (the guard already logs errors server-side) —
 * mirroring [ImportRepositoryImpl].
 */
internal class OrganizeRepositoryImpl(
    private val rpcFactory: OrganizeRpcFactory,
) : OrganizeRepository {
    override suspend fun getSettings(): AppResult<OrganizeSettingsDto> = rpcCall { rpcFactory.get().getSettings() }

    override suspend fun preview(settings: OrganizeSettingsDto): AppResult<OrganizePreviewDto> =
        rpcCall { rpcFactory.get().preview(settings) }

    override suspend fun saveAndExecute(settings: OrganizeSettingsDto): AppResult<OrganizeRunId> =
        rpcCall { rpcFactory.get().saveAndExecute(settings) }

    override fun observeRun(runId: OrganizeRunId): Flow<OrganizeRunEvent> =
        flow {
            // Acquire the proxy at collection time so the cold flow stays truly cold.
            rpcFactory.get().observeRun(runId).collect { event ->
                when (event) {
                    is RpcEvent.Data -> emit(event.value)
                    is RpcEvent.Error -> logger.warn { "observeRun received RpcEvent.Error: ${event.error}" }
                    is RpcEvent.Complete -> Unit
                }
            }
        }

    override suspend fun resumeRun(): AppResult<OrganizeRunId?> = rpcCall { rpcFactory.get().resumeRun() }

    /**
     * Runs an RPC call that returns a wire [AppResult] as-is. [CancellationException] is
     * re-raised; all other throwables become [AppResult.Failure] via [ErrorMapper].
     */
    private suspend fun <T> rpcCall(block: suspend () -> AppResult<T>): AppResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppResult.Failure(ErrorMapper.map(e))
        }
}
