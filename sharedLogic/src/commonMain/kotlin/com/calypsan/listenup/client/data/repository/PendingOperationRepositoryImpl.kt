package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.sync.BookEdit
import com.calypsan.listenup.client.data.sync.ContributorEdit
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PreferencesEdit
import com.calypsan.listenup.client.data.sync.ProfileEdit
import com.calypsan.listenup.client.data.sync.SeriesEdit
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
        val SILENT_DOMAINS = setOf("listening_events", "playback_positions", PreferencesEdit.name)
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
        BookEdit.name -> PendingOperationType.BOOK_UPDATE
        SeriesEdit.name -> PendingOperationType.SERIES_UPDATE
        ContributorEdit.name -> PendingOperationType.CONTRIBUTOR_UPDATE
        ProfileEdit.name -> PendingOperationType.PROFILE_UPDATE
        PreferencesEdit.name -> PendingOperationType.USER_PREFERENCES
        "playback_positions" -> PendingOperationType.PLAYBACK_POSITION
        "listening_events" -> PendingOperationType.LISTENING_EVENT
        else -> PendingOperationType.OTHER
    }
