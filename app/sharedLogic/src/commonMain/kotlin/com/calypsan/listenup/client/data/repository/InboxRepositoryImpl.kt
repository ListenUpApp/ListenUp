package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.onSuccess
import com.calypsan.listenup.client.data.local.db.CollectionBookDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.currentEpochMilliseconds
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val logger = KotlinLogging.logger {}

/**
 * [InboxRepository] over the local held set ([CollectionBookDao]) and the `CollectionService` /
 * `ScannerService` RPCs.
 *
 * The held set is read from Room, which the collection sync stream keeps current. A release is the
 * RPC; once the server has committed it, [releaseBooks] tombstones the local INBOX memberships so
 * every held surface converges at once, and re-homes each book released to everyone into its
 * library's All Books, as the server just did — so the book never reads as in no collection at all.
 */
internal class InboxRepositoryImpl(
    private val channel: RpcChannel<CollectionService>,
    // Scan issues are a scanner concern, not collection membership — they ride the scanner's own
    // channel rather than being bolted onto the collection surface for proximity's sake.
    private val scannerChannel: RpcChannel<ScannerService>,
    private val collectionBookDao: CollectionBookDao,
    private val transactionRunner: TransactionRunner,
) : InboxRepository {
    private val systemMembership = SystemMembershipReconciler(collectionBookDao)

    // Room re-runs the held query on every collection_books / collections write, held or not. The
    // list (not the set) is compared, so a reorder still counts as a change: order is the contract.
    override fun observeHeldBookIds(): Flow<Set<BookId>> =
        collectionBookDao
            .observeHeldBookIds()
            .distinctUntilChanged()
            .map { ids -> ids.mapTo(LinkedHashSet<BookId>()) { BookId(it) } }

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
            }.onSuccess { writeReleaseThrough(assignments) }

    /**
     * Write the committed release through to Room in one transaction: the INBOX memberships end, and
     * each book released to everyone (an empty target list) is re-homed into its library's All Books
     * by the same reconcile the server ran ([SystemMembershipReconciler]) — so Book Detail reads
     * Public the moment Release succeeds, never a passing *Stranded*. A book released into named
     * collections is left for the echo: those memberships are the server's to mint, and re-homing it
     * here would show it to everyone for that moment.
     *
     * The release has already committed on the server, so a failed local write must not turn it into
     * an error: the caller would report a release that happened. The server's echo still converges
     * Room; until it lands the books simply stay in the inbox a moment longer.
     */
    private suspend fun writeReleaseThrough(assignments: Map<String, List<String>>) {
        try {
            val now = currentEpochMilliseconds()
            transactionRunner.atomically {
                collectionBookDao.tombstoneHeldRows(assignments.keys.toList(), now)
                systemMembership.reconcileLocally(assignments.filterValues { it.isEmpty() }.keys, now)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Release committed, but the local inbox write-through failed; awaiting the sync echo" }
        }
    }

    override suspend fun listScanIssues(): AppResult<List<ScanIssue>> =
        scannerChannel.call(idempotent = true) { it.listScanIssues() }

    override suspend fun dismissScanIssue(issueId: String): AppResult<Unit> =
        scannerChannel.call { it.dismissScanIssue(issueId) }
}
