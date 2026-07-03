package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.data.sync.PendingOperation as QueuedOperation
import com.calypsan.listenup.client.domain.model.PendingOperation
import com.calypsan.listenup.client.domain.model.PendingOperationStatus
import com.calypsan.listenup.client.domain.model.PendingOperationType
import com.calypsan.listenup.client.domain.repository.PendingOperationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Read-model over [PendingOperationQueue] for the sync indicator: maps outbox
 * rows to UI-facing [PendingOperation] values and routes retry/dismiss back
 * to the queue.
 */
internal class PendingOperationRepositoryImpl(
    private val queue: PendingOperationQueue,
) : PendingOperationRepository {
    override fun observeVisibleOperations(): Flow<List<PendingOperation>> =
        queue.observePendingOperations().map { ops ->
            ops
                .filterNot { it.domainName in SILENT_DOMAINS }
                .map { it.toDomainModel(PendingOperationStatus.PENDING) }
        }

    // The outbox has no in-flight marker (drain deletes on ack); there is no
    // "currently sending" row to observe. Syncing comes from SyncRepository.
    override fun observeInProgressOperation(): Flow<PendingOperation?> = flowOf(null)

    override fun observeFailedOperations(): Flow<List<PendingOperation>> =
        queue.observeFailedOperations().map { ops ->
            ops.map { it.toDomainModel(PendingOperationStatus.FAILED) }
        }

    override suspend fun retry(id: String) = queue.retryOp(id)

    override suspend fun dismiss(id: String) = queue.dismissOp(id)

    private companion object {
        /** Background domains users don't manage by hand; hidden from the pending count. */
        val SILENT_DOMAINS =
            setOf(OutboxChannels.ListeningEvents.name, OutboxChannels.Positions.name, OutboxChannels.Preferences.name)
    }
}

private fun QueuedOperation.toDomainModel(status: PendingOperationStatus): PendingOperation =
    PendingOperation(
        id = clientOpId,
        operationType = operationTypeFor(domainName),
        entityId = entityId,
        status = status,
        lastError = lastError,
    )

private fun operationTypeFor(domainName: String): PendingOperationType =
    when (domainName) {
        OutboxChannels.Books.name -> PendingOperationType.BOOK_UPDATE
        OutboxChannels.Series.name -> PendingOperationType.SERIES_UPDATE
        OutboxChannels.Contributors.name -> PendingOperationType.CONTRIBUTOR_UPDATE
        OutboxChannels.Profile.name -> PendingOperationType.PROFILE_UPDATE
        OutboxChannels.Preferences.name -> PendingOperationType.USER_PREFERENCES
        OutboxChannels.Positions.name -> PendingOperationType.PLAYBACK_POSITION
        OutboxChannels.ListeningEvents.name -> PendingOperationType.LISTENING_EVENT
        else -> PendingOperationType.OTHER
    }
