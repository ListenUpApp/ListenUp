package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.remote.CollectionInboxApiContract
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.api.result.AppResult

/**
 * [InboxRepository] backed by the 1b admin REST routes via [CollectionInboxApiContract].
 *
 * A thin pass-through: the inbox is admin-internal and not mirrored into Room, so the
 * repository simply forwards to the REST client. Mutations on release propagate into
 * Room through the normal collection/book sync stream, not through this repository.
 */
internal class InboxRepositoryImpl(
    private val api: CollectionInboxApiContract,
) : InboxRepository {
    override suspend fun listInbox(libraryId: String): AppResult<List<String>> = api.listInbox(libraryId)

    override suspend fun releaseBooks(
        libraryId: String,
        assignments: Map<String, List<String>>,
    ): AppResult<Unit> = api.releaseBooks(libraryId, assignments)
}
