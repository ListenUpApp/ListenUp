package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.api.dto.MergeUndoResult
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.MergeReceiptId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.SeriesRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * What a series merge changed, and how to put it back.
 *
 * [record] runs inside the merge's own transaction, before the relink: a merge that fails leaves no
 * receipt, and a receipt never exists without its merge. Receipts are server-only and fetched on
 * demand — undo needs the server anyway.
 */
internal class SeriesMergeReceipts(
    private val sqlDb: ListenUpDatabase,
    private val seriesRepo: SeriesRepository,
    private val bookRepo: BookRepository,
    private val clock: Clock,
) {
    private val receipts get() = sqlDb.seriesMergeReceiptsQueries

    /**
     * Records [source] → [target] by [mergedBy]. Must be called inside the merge's transaction and
     * BEFORE its membership relink — the snapshot reads the memberships the relink rewrites.
     */
    fun record(
        source: SeriesId,
        target: SeriesId,
        mergedBy: String,
    ) {
        val receiptId = Uuid.random().toString()
        receipts.insertReceipt(
            id = receiptId,
            source_id = source.value,
            target_id = target.value,
            merged_at = clock.now().toEpochMilliseconds(),
            merged_by = mergedBy,
        )
        receipts.snapshotSourceMemberships(receipt_id = receiptId, target_id = target.value, source_id = source.value)
    }

    /** Open receipts naming [target] as the survivor, newest first. */
    suspend fun openFor(target: SeriesId): List<MergeReceipt> =
        suspendTransaction(sqlDb) {
            receipts.selectOpenForTarget(target.value).executeAsList().map { row ->
                MergeReceipt(
                    id = MergeReceiptId(row.id),
                    sourceName = row.source_name,
                    mergedAt = row.merged_at,
                    mergedByName = row.merged_by_name,
                    bookCount = row.book_count.toInt(),
                )
            }
        }

    /**
     * Undoes the merge [receiptId] recorded. The receipt is claimed in one transaction — validated,
     * every still-as-merged membership restored, `undone_at` set — which is what serializes two
     * admins undoing at once. The source revival and book re-upserts then run as sequential
     * aggregate writes, the same shape as the merge itself.
     */
    suspend fun undo(receiptId: MergeReceiptId): AppResult<MergeUndoResult> {
        val claim =
            when (val decided = suspendTransaction(sqlDb) { claim(receiptId) }) {
                is SeriesUndoClaim.Refused -> return AppResult.Failure(decided.error)
                is SeriesUndoClaim.Granted -> decided
            }
        when (val revived = seriesRepo.revive(claim.sourceId)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return AppResult.Failure(revived.error)
        }
        for (book in bookRepo.findAllByIds(claim.restoredBookIds)) {
            when (val upserted = bookRepo.upsert(book)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> return AppResult.Failure(upserted.error)
            }
        }
        return AppResult.Success(
            MergeUndoResult(
                restoredSourceId = claim.sourceId.value,
                booksRestored = claim.restoredBookIds.size,
                booksSkipped = claim.skipped,
                restoredAtTopLevel = false,
            ),
        )
    }

    /** The in-transaction half of [undo]: decide, restore memberships, mark undone. */
    private fun claim(receiptId: MergeReceiptId): SeriesUndoClaim {
        val receipt =
            receipts.selectReceipt(receiptId.value).executeAsOneOrNull()
                ?: return SeriesUndoClaim.Refused(
                    SeriesError.MergeReceiptNotFound(debugInfo = "receipt=${receiptId.value}"),
                )
        if (receipt.undone_at != null) {
            return SeriesUndoClaim.Refused(SeriesError.MergeAlreadyUndone(debugInfo = "receipt=${receipt.id}"))
        }
        val target = sqlDb.seriesQueries.selectById(receipt.target_id).executeAsOneOrNull()
        if (target == null || target.deleted_at != null) {
            return SeriesUndoClaim.Refused(
                SeriesError.MergeTargetGone(debugInfo = "receipt=${receipt.id} target=${receipt.target_id}"),
            )
        }
        val restorable =
            receipts.selectRestorableBooks(receipt_id = receipt.id, target_id = receipt.target_id).executeAsList()
        val recorded = receipts.countReceiptBooks(receipt.id).executeAsOne()
        for (book in restorable) {
            sqlDb.bookSeriesMembershipsQueries.insertIfAbsent(
                book_id = book.book_id,
                series_id = receipt.source_id,
                sequence = book.sequence,
                ordinal = book.ordinal,
            )
            if (book.was_in_target == 0L) {
                sqlDb.bookSeriesMembershipsQueries.deleteMembership(
                    book_id = book.book_id,
                    series_id = receipt.target_id,
                )
            }
        }
        receipts.markUndone(undone_at = clock.now().toEpochMilliseconds(), id = receipt.id)
        return SeriesUndoClaim.Granted(
            sourceId = SeriesId(receipt.source_id),
            restoredBookIds = restorable.map { it.book_id },
            skipped = (recorded - restorable.size).toInt(),
        )
    }
}

/** The outcome of claiming a series merge receipt for undo. */
private sealed interface SeriesUndoClaim {
    /** The undo may not proceed; nothing was written. */
    data class Refused(
        val error: SeriesError,
    ) : SeriesUndoClaim

    /** Memberships are restored and the receipt is marked; the source and books still need re-upserting. */
    data class Granted(
        val sourceId: SeriesId,
        val restoredBookIds: List<String>,
        val skipped: Int,
    ) : SeriesUndoClaim
}
