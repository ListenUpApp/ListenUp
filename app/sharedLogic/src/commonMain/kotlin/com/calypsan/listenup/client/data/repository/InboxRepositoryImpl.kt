package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.CollectionBookDao
import com.calypsan.listenup.client.data.local.db.CollectionBookEntity
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
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * [InboxRepository] over the local held set ([CollectionBookDao]) and the `CollectionService` /
 * `ScannerService` RPCs.
 *
 * The held set is read from Room, which the collection sync stream keeps current. A release is the
 * RPC; once the server has committed it, [releaseBooks] writes it through to Room as the server just
 * did: the INBOX memberships end, so every held surface converges at once, and each book gains its
 * new memberships — All Books for a book released to everyone, the named collections otherwise — so
 * it never reads as in no collection at all. A partial release ([CollectionError.ReleaseIncomplete])
 * writes through only the books it does not name: the named ones stayed held on the server, so they
 * stay held here too.
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
    ): AppResult<Unit> {
        val result =
            channel.call { service ->
                service.releaseBooks(
                    LibraryId(libraryId),
                    assignments.entries.associate { (bookId, targets) ->
                        BookId(bookId) to targets.map(::CollectionId)
                    },
                )
            }
        // A partial release committed for every book it does not name, and those books have left the
        // inbox on the server. The ones it names stayed held, so they must stay held here too.
        val released =
            when (result) {
                is AppResult.Success -> assignments.keys.toList()
                is AppResult.Failure -> releasedDespite(result.error, assignments.keys)
            }
        if (released.isNotEmpty()) writeReleaseThrough(assignments.filterKeys { it in released })
        return result
    }

    /** The books that left the inbox even though the release as a whole failed: none, unless it was partial. */
    private fun releasedDespite(
        error: AppError,
        requested: Set<String>,
    ): List<String> =
        if (error is CollectionError.ReleaseIncomplete) {
            requested.filterNot { it in error.failedBookIds }
        } else {
            emptyList()
        }

    /**
     * Write the committed release through to Room in one transaction: the INBOX memberships end, each
     * book released into named collections gains a membership of each, and then every released book
     * is reconciled as the server did ([SystemMembershipReconciler]) — so Book Detail reads Public
     * (released to everyone) or Restricted (released into collections) the moment Release succeeds,
     * never a passing *Stranded*, and never a moment of Public for a book bound for a collection.
     *
     * The named memberships are `revision = 0` stubs under client-minted sync ids, as
     * `setBookCollections` writes its adds: the server's rows arrive under their own ids, which the
     * revision guard has never seen, and replace the stubs by their `(collectionId, bookId)` key.
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
                for ((bookId, targets) in assignments) {
                    for (collectionId in targets) writeMembershipStub(collectionId, bookId, now)
                }
                systemMembership.reconcileLocally(assignments.keys, now)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Release committed, but the local inbox write-through failed; awaiting the sync echo" }
        }
    }

    private suspend fun writeMembershipStub(
        collectionId: String,
        bookId: String,
        now: Long,
    ) {
        val existing = collectionBookDao.findByKey(collectionId, bookId)
        if (existing != null && existing.deletedAt == null) return
        collectionBookDao.upsert(
            CollectionBookEntity(
                collectionId = collectionId,
                bookId = bookId,
                syncId = Uuid.random().toString(),
                createdAt = now,
                revision = 0,
                deletedAt = null,
            ),
        )
    }

    override suspend fun listScanIssues(): AppResult<List<ScanIssue>> =
        scannerChannel.call(idempotent = true) { it.listScanIssues() }

    override suspend fun dismissScanIssue(issueId: String): AppResult<Unit> =
        scannerChannel.call { it.dismissScanIssue(issueId) }
}
