package com.calypsan.listenup.server.matching.person

import com.calypsan.listenup.api.dto.match.UndoResult
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.matching.undo.MatchReceiptCodec
import com.calypsan.listenup.server.matching.undo.MatchReceiptStore
import com.calypsan.listenup.server.matching.undo.ReceiptEntity
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import com.calypsan.listenup.server.sync.FrameCapture
import kotlinx.coroutines.currentCoroutineContext

/**
 * Undoes a person match: in one transaction, as an ordinary write (the revision moves, a sync frame goes out), it
 * restores the snapshot — bio, photo path, refs, the asin column, provenance — and marks the receipt undone. It
 * refuses with [MetadataError.UndoExpired] when the receipt isn't a person's, was already undone, or the person has
 * changed or gone since; Undo can't itself be undone.
 */
internal class PersonMatchUndoer(
    private val db: ListenUpDatabase,
    private val contributors: ContributorRepository,
    private val receipts: MatchReceiptStore,
    private val now: () -> Long,
) {
    suspend fun undo(receiptId: String): AppResult<UndoResult> {
        val suppressed = currentCoroutineContext()[FirehoseSuppressed.Key] != null
        val capture = currentCoroutineContext()[FrameCapture.Key]
        return suspendTransaction(db) {
            val receipt = receipts.findInTransaction(receiptId)?.takeIf { it.entity == ReceiptEntity.CONTRIBUTOR.value }
            val current = receipt?.let { contributors.readPayloadInTransaction(it.entityId) }?.takeIf { it.deletedAt == null }
            if (receipt == null || receipt.undoneAt != null || current?.revision != receipt.revisionAfter) {
                return@suspendTransaction AppResult.Failure(MetadataError.UndoExpired(debugInfo = "receipt $receiptId"))
            }
            val snapshot = MatchReceiptCodec.decodeContributorSnapshot(receipt.snapshotJson)
            receipts.markUndoneInTransaction(receiptId, now())
            contributors.writeMatchInTransaction(
                tx = this,
                value = snapshot.contributor,
                revision = contributors.allocateRevision(),
                suppressed = suppressed,
                capture = capture,
            )
            AppResult.Success(UndoResult(receiptId, receipt.changes))
        }
    }
}
