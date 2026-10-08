package com.calypsan.listenup.server.matching.apply

import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.matching.undo.BookMatchSnapshot
import com.calypsan.listenup.server.matching.undo.MatchReceiptCodec
import com.calypsan.listenup.server.matching.undo.MatchReceiptRow
import com.calypsan.listenup.server.matching.undo.MatchReceiptStore
import com.calypsan.listenup.server.matching.undo.ReceiptEntity
import com.calypsan.listenup.server.matching.undo.SnapshotCover
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import com.calypsan.listenup.server.sync.FrameCapture
import kotlinx.coroutines.currentCoroutineContext
import kotlin.uuid.Uuid

/**
 * Writes a [MatchWritePlan] in **one** transaction (spec, *Apply*, step 3): the pre-apply snapshot and the receipt,
 * the mood links, the cover columns, the genre junctions and ladder links, and the book aggregate itself — whose
 * revision moves exactly once. Nothing lands unless all of it does; a fault anywhere rolls every write back.
 *
 * [basedOnRevision], when given, is re-checked inside the transaction: a book that moved since is
 * [MetadataError.ReviewOutdated] and nothing is written. The legacy apply passes null (last write wins, as it
 * always has).
 */
internal class BookMatchWriter(
    private val db: ListenUpDatabase,
    private val books: BookRepository,
    private val moods: BookMoodRepository,
    private val receipts: MatchReceiptStore,
    private val now: () -> Long,
    /**
     * Runs inside the transaction after every write has been issued, just before it commits. Production passes
     * nothing; the rollback test throws from it to prove a fault at the last moment undoes every write.
     */
    private val beforeCommit: () -> Unit = {},
) {
    suspend fun write(
        plan: MatchWritePlan,
        basedOnRevision: Long?,
        appliedBy: String,
    ): AppResult<MatchReceipt> {
        val suppressed = currentCoroutineContext()[FirehoseSuppressed.Key] != null
        val capture = currentCoroutineContext()[FrameCapture.Key]
        val bookId = plan.book.id
        return suspendTransaction(db) {
            val revision = books.revisionInTransaction(bookId)
            if (revision == null || (basedOnRevision != null && revision != basedOnRevision)) {
                return@suspendTransaction AppResult.Failure(
                    MetadataError.ReviewOutdated(
                        debugInfo = "book $bookId is at ${revision ?: "no revision"}, not ${basedOnRevision ?: "any"}",
                    ),
                )
            }
            val before =
                checkNotNull(books.readPayloadInTransaction(bookId)) { "book $bookId has a revision but no payload" }
            val cover = db.booksQueries.selectCoverColumnsById(bookId).executeAsOne()
            val snapshot =
                BookMatchSnapshot(
                    book = before.copy(lastMatch = null),
                    cover = SnapshotCover(cover.cover_source, cover.cover_path, cover.cover_hash),
                    genreIds = before.genres.map { it.id },
                    moodsLinked = plan.moodsToLink,
                    moodsUnlinked = plan.moodsToUnlink,
                )
            // Mood links take their revisions first, so the book's — taken last — is the highest this commit
            // emits, and frames go out in revision order.
            plan.moodsToUnlink.forEach { moodId ->
                moods.unlinkInTransaction(
                    tx = this,
                    bookId = bookId,
                    moodId = moodId,
                    suppressed = suppressed,
                    capture = capture,
                )
            }
            plan.moodsToLink.forEach { moodId ->
                moods.linkInTransaction(
                    tx = this,
                    bookId = bookId,
                    moodId = moodId,
                    suppressed = suppressed,
                    capture = capture,
                )
            }
            val revisionAfter = books.allocateRevision()
            val receipt =
                MatchReceipt(
                    receiptId = Uuid.random().toString(),
                    appliedAt = now(),
                    changes = plan.changes,
                    undoable = true,
                )
            receipts.replaceLiveInTransaction(
                MatchReceiptRow(
                    id = receipt.receiptId,
                    entity = ReceiptEntity.BOOK.value,
                    entityId = bookId,
                    appliedBy = appliedBy,
                    appliedAt = receipt.appliedAt,
                    revisionAfter = revisionAfter,
                    snapshotJson = MatchReceiptCodec.encodeSnapshot(snapshot),
                    changes = plan.changes,
                    undoneAt = null,
                ),
            )
            books.writeMatchInTransaction(
                tx = this,
                value = plan.book,
                cover = plan.cover,
                genreIds = plan.genreIds,
                ladderRungIds = plan.ladderRungIds,
                revision = revisionAfter,
                suppressed = suppressed,
                capture = capture,
            )
            beforeCommit()
            AppResult.Success(receipt)
        }
    }
}
