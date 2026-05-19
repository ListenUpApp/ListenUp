package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.domain.model.PendingOperation
import com.calypsan.listenup.client.domain.repository.PendingOperationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Empty compatibility facade after legacy pending operations are removed. */
class PendingOperationRepositoryImpl : PendingOperationRepository {
    override fun observeVisibleOperations(): Flow<List<PendingOperation>> = flowOf(emptyList())

    override fun observeInProgressOperation(): Flow<PendingOperation?> = flowOf(null)

    override fun observeFailedOperations(): Flow<List<PendingOperation>> = flowOf(emptyList())

    override suspend fun retry(id: String) = Unit

    override suspend fun dismiss(id: String) = Unit
}
