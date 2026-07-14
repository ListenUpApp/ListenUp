package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.OrganizeService
import com.calypsan.listenup.api.dto.organize.OrganizePreviewDto
import com.calypsan.listenup.api.dto.organize.OrganizeRunEvent
import com.calypsan.listenup.api.dto.organize.OrganizeRunId
import com.calypsan.listenup.api.dto.organize.OrganizeSettingsDto
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.OrganizeRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private val logger = KotlinLogging.logger {}

/**
 * [OrganizeRepository] over the [OrganizeService] [RpcChannel].
 *
 * Every RPC dispatches through the channel, which folds transport faults into typed
 * [AppResult.Failure]s, re-raises cancellation, and self-heals its own connection.
 * [getSettings], [preview] (plans without touching disk), and [resumeRun] (a query) are reads,
 * so they declare `idempotent = true`; [saveAndExecute] mutates and keeps the safe default.
 *
 * [observeRun] unwraps the server-pushed [Flow]<[RpcEvent]<[OrganizeRunEvent]>> into a plain
 * [Flow]<[OrganizeRunEvent]>: [RpcEvent.Data] values are emitted; [RpcEvent.Error] and
 * [RpcEvent.Complete] are logged/dropped (the guard already logs errors server-side) —
 * mirroring [ImportRepositoryImpl].
 */
internal class OrganizeRepositoryImpl(
    private val channel: RpcChannel<OrganizeService>,
) : OrganizeRepository {
    override suspend fun getSettings(): AppResult<OrganizeSettingsDto> =
        channel.call(idempotent = true) { it.getSettings() }

    override suspend fun preview(settings: OrganizeSettingsDto): AppResult<OrganizePreviewDto> =
        channel.call(idempotent = true) { it.preview(settings) }

    override suspend fun saveAndExecute(settings: OrganizeSettingsDto): AppResult<OrganizeRunId> =
        channel.call { it.saveAndExecute(settings) }

    override fun observeRun(runId: OrganizeRunId): Flow<OrganizeRunEvent> =
        flow {
            // Subscribe at collection time so the cold flow stays truly cold.
            channel.stream { it.observeRun(runId) }.collect { event ->
                when (event) {
                    is RpcEvent.Data -> emit(event.value)
                    is RpcEvent.Error -> logger.warn { "observeRun received RpcEvent.Error: ${event.error}" }
                    is RpcEvent.Complete -> Unit
                }
            }
        }

    override suspend fun resumeRun(): AppResult<OrganizeRunId?> = channel.call(idempotent = true) { it.resumeRun() }
}
