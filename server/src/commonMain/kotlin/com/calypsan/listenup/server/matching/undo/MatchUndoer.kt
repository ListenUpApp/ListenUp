package com.calypsan.listenup.server.matching.undo

import com.calypsan.listenup.api.dto.match.UndoResult
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.MatchCoverColumns
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import com.calypsan.listenup.server.sync.FrameCapture
import kotlinx.coroutines.currentCoroutineContext

/**
 * Undoes a book match (spec, *Undo*): in one transaction, as an ordinary write (the revision moves, a sync
 * frame goes out), it restores the snapshot — the aggregate, its cover columns, its genre set — reverses the
 * match's own mood links, and marks the receipt undone. It refuses with [MetadataError.UndoExpired] when the
 * receipt was already undone or the book has changed since the match; Undo can't itself be undone.
 */
internal class MatchUndoer(
    private val db: ListenUpDatabase,
    private val books: BookRepository,
    private val moods: BookMoodRepository,
    private val receipts: MatchReceiptStore,
    private val now: () -> Long,
) {
    suspend fun undo(receiptId: String): AppResult<UndoResult> {
        val suppressed = currentCoroutineContext()[FirehoseSuppressed.Key] != null
        val capture = currentCoroutineContext()[FrameCapture.Key]
        return suspendTransaction(db) {
            val receipt = receipts.findInTransaction(receiptId)?.takeIf { it.entity == ReceiptEntity.BOOK.value }
            val revision = receipt?.let { books.revisionInTransaction(it.entityId) }
            if (receipt == null || receipt.undoneAt != null || revision != receipt.revisionAfter) {
                return@suspendTransaction AppResult.Failure(MetadataError.UndoExpired(debugInfo = "receipt $receiptId"))
            }
            val snapshot = MatchReceiptCodec.decodeSnapshot(receipt.snapshotJson)
            val bookId = receipt.entityId
            receipts.markUndoneInTransaction(receiptId, now())
            snapshot.moodsLinked.forEach { moodId ->
                moods.unlinkInTransaction(
                    tx = this,
                    bookId = bookId,
                    moodId = moodId,
                    suppressed = suppressed,
                    capture = capture,
                )
            }
            snapshot.moodsUnlinked.forEach { moodId ->
                moods.linkInTransaction(
                    tx = this,
                    bookId = bookId,
                    moodId = moodId,
                    suppressed = suppressed,
                    capture = capture,
                )
            }
            books.writeMatchInTransaction(
                tx = this,
                value = snapshot.book,
                cover =
                    MatchCoverColumns(
                        source =
                            CoverSource.entries.firstOrNull { entry ->
                                entry.name.equals(snapshot.cover.source, ignoreCase = true)
                            },
                        path = snapshot.cover.path,
                        hash = snapshot.cover.hash,
                    ),
                genreIds = snapshot.genreIds,
                ladderRungIds = emptyList(),
                revision = books.allocateRevision(),
                suppressed = suppressed,
                capture = capture,
            )
            AppResult.Success(UndoResult(receiptId, receipt.changes))
        }
    }
}
