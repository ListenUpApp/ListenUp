package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.result.onSuccess
import com.calypsan.listenup.client.data.local.db.CollectionBookDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.currentEpochMilliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [InboxRepository] over the local held set ([CollectionBookDao]) and the `CollectionService` /
 * `ScannerService` RPCs.
 *
 * The held set is read from Room, which the collection sync stream keeps current. A release is the
 * RPC; once the server has committed it, [releaseBooks] tombstones the local INBOX memberships so
 * every held surface converges at once. The ALL_BOOKS membership still arrives through the echo —
 * for that moment the book is in neither, which reads as "not held", the truth.
 */
internal class InboxRepositoryImpl(
    private val channel: RpcChannel<CollectionService>,
    // Scan issues are a scanner concern, not collection membership — they ride the scanner's own
    // channel rather than being bolted onto the collection surface for proximity's sake.
    private val scannerChannel: RpcChannel<ScannerService>,
    private val collectionBookDao: CollectionBookDao,
) : InboxRepository {
    override fun observeHeldBookIds(): Flow<Set<BookId>> =
        collectionBookDao.observeHeldBookIds().map { ids -> ids.mapTo(LinkedHashSet<BookId>()) { BookId(it) } }

    override suspend fun listInbox(libraryId: String): AppResult<List<String>> =
        channel
            .call(idempotent = true) { it.listInbox(LibraryId(libraryId)) }
            .map { bookIds -> bookIds.map { it.value } }

    override suspend fun releaseBooks(
        libraryId: String,
        assignments: Map<String, List<String>>,
    ): AppResult<Unit> =
        channel
            .call {
                it.releaseBooks(
                    LibraryId(libraryId),
                    assignments.entries.associate { (bookId, targets) ->
                        BookId(bookId) to targets.map(::CollectionId)
                    },
                )
            }.onSuccess {
                collectionBookDao.tombstoneHeldRows(assignments.keys.toList(), currentEpochMilliseconds())
            }

    override suspend fun listScanIssues(): AppResult<List<ScanIssue>> =
        scannerChannel.call(idempotent = true) { it.listScanIssues() }

    override suspend fun dismissScanIssue(issueId: String): AppResult<Unit> =
        scannerChannel.call { it.dismissScanIssue(issueId) }
}
