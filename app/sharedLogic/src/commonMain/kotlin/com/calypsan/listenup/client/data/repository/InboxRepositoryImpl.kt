package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.LibraryId

/**
 * [InboxRepository] backed by [CollectionService.listInbox] / [CollectionService.releaseBooks].
 *
 * A thin pass-through: the inbox is admin-internal and not mirrored into Room, so the
 * repository simply forwards to the RPC channel, mapping the domain-interface's plain
 * `String` ids to their typed contract counterparts at the boundary. Mutations on release
 * propagate into Room through the normal collection/book sync stream, not through this
 * repository.
 */
internal class InboxRepositoryImpl(
    private val channel: RpcChannel<CollectionService>,
    // Scan issues are a scanner concern, not collection membership — they ride the scanner's own
    // channel rather than being bolted onto the collection surface for proximity's sake.
    private val scannerChannel: RpcChannel<ScannerService>,
) : InboxRepository {
    override suspend fun listInbox(libraryId: String): AppResult<List<String>> =
        channel
            .call(idempotent = true) { it.listInbox(LibraryId(libraryId)) }
            .map { bookIds -> bookIds.map { it.value } }

    override suspend fun releaseBooks(
        libraryId: String,
        assignments: Map<String, List<String>>,
    ): AppResult<Unit> =
        channel.call {
            it.releaseBooks(
                LibraryId(libraryId),
                assignments.entries.associate { (bookId, targets) ->
                    BookId(bookId) to targets.map(::CollectionId)
                },
            )
        }

    override suspend fun listScanIssues(): AppResult<List<ScanIssue>> =
        scannerChannel.call(idempotent = true) { it.listScanIssues() }

    override suspend fun dismissScanIssue(issueId: String): AppResult<Unit> =
        scannerChannel.call { it.dismissScanIssue(issueId) }
}
