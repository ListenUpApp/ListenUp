package com.calypsan.listenup.server.api

import app.cash.sqldelight.TransactionWithReturn
import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.api.dto.MergeUndoResult
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.MergeReceiptId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.EntityRepository
import com.calypsan.listenup.server.sync.WorldEventRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * What a series merge changed, and how to put it back.
 *
 * [record] runs inside the merge's own transaction, before the relink: a merge refused before its
 * relink leaves no receipt, and a receipt never exists without its merge. Receipts are server-only
 * and fetched on demand — undo needs the server anyway.
 */
internal class SeriesMergeReceipts(
    private val sqlDb: ListenUpDatabase,
    private val seriesRepo: SeriesRepository,
    private val bookRepo: BookRepository,
    private val hierarchy: SeriesHierarchyWrites,
    private val entityRepo: EntityRepository?,
    private val worldEventRepo: WorldEventRepository?,
    private val readingOrders: SeriesMergeReadingOrders,
    private val clock: Clock,
) {
    private val receipts get() = sqlDb.seriesMergeReceiptsQueries

    /**
     * Records [source] → [target] by [mergedBy]. Must be called inside the merge's transaction and
     * BEFORE its membership relink — the snapshot reads the memberships the relink rewrites — and
     * BEFORE the merge re-parents the source's sub-series or moves its reading orders. Returns the receipt
     * id, which the Story World re-home snapshots its entities against.
     */
    fun record(
        source: SeriesId,
        target: SeriesId,
        mergedBy: String,
    ): String {
        val receiptId = Uuid.random().toString()
        receipts.insertReceipt(
            id = receiptId,
            source_id = source.value,
            target_id = target.value,
            merged_at = clock.now().toEpochMilliseconds(),
            merged_by = mergedBy,
        )
        receipts.snapshotSourceMemberships(receipt_id = receiptId, target_id = target.value, source_id = source.value)
        receipts.snapshotSourceChildren(receipt_id = receiptId, source_id = source.value)
        receipts.snapshotSourceReadingOrders(receipt_id = receiptId, source_id = source.value)
        return receiptId
    }

    /** Moves the reading orders [receiptId] recorded onto [target] (#962). The first failure, if any. */
    suspend fun moveReadingOrders(
        receiptId: String,
        target: SeriesId,
        sourceName: String,
    ): AppError? = readingOrders.moveOnto(receiptId, target, sourceName)

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
     * Undoes the merge [receiptId] recorded, in five steps:
     *
     *  1. A read-only [decide] transaction checks the receipt is open and its target still live —
     *     nothing is written, so a refusal here leaves everything exactly as it was.
     *  2. [SeriesRepository.revive] brings the source series back (idempotent — a no-op if it's
     *     already live, e.g. through a prior partial undo). This runs BEFORE the claim: if it fails,
     *     nothing else has changed and the receipt stays open for a retry, instead of leaving books
     *     pointed at a tombstoned series with no way back.
     *  3. A write [claim] transaction re-validates exactly as [decide] did, then restores every
     *     still-as-merged membership, carries back every Story World entity, and then every event, the
     *     merge moved that is still live under the target, in its current state
     *     ([EntityRepository.prepareSeriesMergeUndo], [WorldEventRepository.prepareSeriesMergeUndo]), and marks the receipt undone — see [claim] for
     *     why marking it is the transaction's first write. The entities move inside the claim because
     *     the mark makes any retry `MergeAlreadyUndone`: anything left for after it could be stranded.
     *  4. Every restored book is re-upserted (bumps revision, publishes `Updated`). One bad book
     *     doesn't stop the rest — the loop keeps going and the first failure is reported at the end.
     *  5. Every sub-series the merge moved, and that is still under the target, goes back to the
     *     source at its recorded position — unless the source now sits below it.
     *  6. Every reading order the merge moved, and that is still on the target, goes back to the source
     *     under its old name (#962).
     */
    suspend fun undo(receiptId: MergeReceiptId): AppResult<MergeUndoResult> {
        val sourceId =
            when (val decision = suspendTransaction(sqlDb) { decide(receiptId) }) {
                is SeriesUndoDecision.Refused -> return AppResult.Failure(decision.error)
                is SeriesUndoDecision.Allowed -> decision.sourceId
            }
        when (val revived = seriesRepo.revive(sourceId)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return AppResult.Failure(revived.error)
        }
        val entityUndo = entityRepo?.prepareSeriesMergeUndo()
        val eventUndo = worldEventRepo?.prepareSeriesMergeUndo()
        val claim =
            when (val decided = suspendTransaction(sqlDb) { claim(receiptId, entityUndo, eventUndo) }) {
                is SeriesUndoClaim.Refused -> return AppResult.Failure(decided.error)
                is SeriesUndoClaim.Granted -> decided
            }
        var firstFailure: AppError? = null
        for (book in bookRepo.findAllByIds(claim.restoredBookIds)) {
            when (val upserted = bookRepo.upsert(book)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> if (firstFailure == null) firstFailure = upserted.error
            }
        }
        // Hand back the sub-series still sitting where the merge put them, at their old positions.
        // The tree may have been rearranged since the merge: one that now sits above the revived
        // source would close a loop by going back under it, so it stays where it is.
        val tree = seriesRepo.liveTree()
        for (child in claim.restoredChildren) {
            if (tree.wouldCycle(child.id.value, claim.sourceId.value)) continue
            val payload = hierarchy.live(child.id) ?: continue
            when (val placed = hierarchy.place(payload, claim.sourceId.value, child.position)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> if (firstFailure == null) firstFailure = placed.error
            }
        }
        val targetName = seriesRepo.findById(claim.targetId.value)?.name.orEmpty()
        readingOrders
            .handBack(
                receiptId = receiptId.value,
                source = claim.sourceId,
                target = claim.targetId,
                targetName = targetName,
            )?.let { if (firstFailure == null) firstFailure = it }
        firstFailure?.let { return AppResult.Failure(it) }
        return AppResult.Success(
            MergeUndoResult(
                restoredSourceId = claim.sourceId.value,
                booksRestored = claim.restoredBookIds.size,
                booksSkipped = claim.skipped,
                // Series undo never restores at the top level — that flag only means anything for
                // genre undo, where a parent can itself be gone.
                restoredAtTopLevel = false,
            ),
        )
    }

    /** Read-only precondition check for [undo]'s step 1: is [receiptId] open, and is its target live? */
    private fun decide(receiptId: MergeReceiptId): SeriesUndoDecision {
        val receipt =
            receipts.selectReceipt(receiptId.value).executeAsOneOrNull()
                ?: return SeriesUndoDecision.Refused(
                    SeriesError.MergeReceiptNotFound(debugInfo = "receipt=${receiptId.value}"),
                )
        if (receipt.undone_at != null) {
            return SeriesUndoDecision.Refused(SeriesError.MergeAlreadyUndone(debugInfo = "receipt=${receipt.id}"))
        }
        val target = sqlDb.seriesQueries.selectById(receipt.target_id).executeAsOneOrNull()
        if (target == null || target.deleted_at != null) {
            return SeriesUndoDecision.Refused(
                SeriesError.MergeTargetGone(debugInfo = "receipt=${receipt.id} target=${receipt.target_id}"),
            )
        }
        return SeriesUndoDecision.Allowed(SeriesId(receipt.source_id))
    }

    /**
     * The write half of [undo]'s step 3: re-validates exactly as [decide] did (the source may have
     * been revived by [undo]'s step 2 in the meantime, but the receipt's own state and its target's
     * liveness can still have moved), then restores memberships and marks the receipt undone.
     *
     * `markUndone` runs FIRST, as the only write before this function has decided anything else —
     * its affected-row count doubles as the double-undo guard: 0 rows means a concurrent undo already
     * claimed this receipt between [decide] and here. That ordering matters because
     * [suspendTransaction] commits on a normal return, [Refused] included — a refusal returned AFTER
     * a membership write would still commit that write, so every read-only check that can refuse
     * (receipt exists, target live) runs before the transaction's first write.
     */
    private fun TransactionWithReturn<SeriesUndoClaim>.claim(
        receiptId: MergeReceiptId,
        entityUndo: EntityRepository.SeriesMergeUndo?,
        eventUndo: WorldEventRepository.SeriesMergeUndo?,
    ): SeriesUndoClaim {
        val receipt =
            receipts.selectReceipt(receiptId.value).executeAsOneOrNull()
                ?: return SeriesUndoClaim.Refused(
                    SeriesError.MergeReceiptNotFound(debugInfo = "receipt=${receiptId.value}"),
                )
        val target = sqlDb.seriesQueries.selectById(receipt.target_id).executeAsOneOrNull()
        if (target == null || target.deleted_at != null) {
            return SeriesUndoClaim.Refused(
                SeriesError.MergeTargetGone(debugInfo = "receipt=${receipt.id} target=${receipt.target_id}"),
            )
        }
        // Step 2's revive ran outside this transaction, so the source could have been merged away
        // again in the gap before we got here (an admin merging the just-revived source into a third
        // series). Catch that here, before markUndone — a refusal after it would still commit it.
        val source = sqlDb.seriesQueries.selectById(receipt.source_id).executeAsOneOrNull()
        if (source == null || source.deleted_at != null) {
            return SeriesUndoClaim.Refused(
                SeriesError.NotFound(debugInfo = "receipt=${receipt.id} source=${receipt.source_id}"),
            )
        }
        val claimed = receipts.markUndone(undone_at = clock.now().toEpochMilliseconds(), id = receipt.id)
        if (claimed.value == 0L) {
            return SeriesUndoClaim.Refused(SeriesError.MergeAlreadyUndone(debugInfo = "receipt=${receipt.id}"))
        }
        val restorable =
            receipts.selectRestorableBooks(receipt_id = receipt.id, target_id = receipt.target_id).executeAsList()
        val recorded = receipts.countReceiptBooks(receipt.id).executeAsOne()
        for (book in restorable) {
            if (book.was_in_target == 0L) {
                // The target row is the one the merge relinked — put it back in place rather than
                // delete+insert, so any reorder since the merge survives.
                val sourceRowExists =
                    sqlDb.bookSeriesMembershipsQueries
                        .existsMembership(book_id = book.book_id, series_id = receipt.source_id)
                        .executeAsOne()
                if (sourceRowExists) {
                    // A later decision (a manual re-add) already put the book back in the source —
                    // drop the now-redundant target row and leave that row exactly as it is.
                    sqlDb.bookSeriesMembershipsQueries.deleteMembership(
                        book_id = book.book_id,
                        series_id = receipt.target_id,
                    )
                } else {
                    sqlDb.bookSeriesMembershipsQueries.repointToSource(
                        source_id = receipt.source_id,
                        sequence = book.sequence,
                        book_id = book.book_id,
                        target_id = receipt.target_id,
                    )
                }
            } else {
                // The merge dropped this book's source row (it already held the target); restore it
                // at its recorded ordinal, alongside the surviving target row — shifting later rows
                // out of the way first so the restored row doesn't tie with whatever occupies that
                // slot now.
                sqlDb.bookSeriesMembershipsQueries.shiftOrdinalsFrom(book_id = book.book_id, ordinal = book.ordinal)
                sqlDb.bookSeriesMembershipsQueries.insertIfAbsent(
                    book_id = book.book_id,
                    series_id = receipt.source_id,
                    sequence = book.sequence,
                    ordinal = book.ordinal,
                )
            }
        }
        val restorableChildren =
            receipts
                .selectRestorableChildren(receipt_id = receipt.id, target_id = receipt.target_id)
                .executeAsList()
                .map { RestorableChild(SeriesId(it.child_id), it.position?.toInt()) }
        entityUndo?.restore(
            transaction = this,
            receiptId = receipt.id,
            source = SeriesId(receipt.source_id),
            target = SeriesId(receipt.target_id),
        )
        eventUndo?.restore(
            transaction = this,
            receiptId = receipt.id,
            source = SeriesId(receipt.source_id),
            target = SeriesId(receipt.target_id),
        )
        return SeriesUndoClaim.Granted(
            sourceId = SeriesId(receipt.source_id),
            targetId = SeriesId(receipt.target_id),
            restoredBookIds = restorable.map { it.book_id },
            skipped = (recorded - restorable.size).toInt(),
            restoredChildren = restorableChildren,
        )
    }
}

/** The outcome of [SeriesMergeReceipts.decide]'s read-only precondition check. */
private sealed interface SeriesUndoDecision {
    /** The undo may not proceed. */
    data class Refused(
        val error: SeriesError,
    ) : SeriesUndoDecision

    /** The receipt is open and its target is live. Carries the source id [undo] needs to revive it. */
    data class Allowed(
        val sourceId: SeriesId,
    ) : SeriesUndoDecision
}

/** The outcome of claiming a series merge receipt for undo. */
private sealed interface SeriesUndoClaim {
    /** The undo may not proceed; nothing was written. */
    data class Refused(
        val error: SeriesError,
    ) : SeriesUndoClaim

    /** Memberships are restored and the receipt is marked; the books and sub-series still need re-upserting. */
    data class Granted(
        val sourceId: SeriesId,
        val targetId: SeriesId,
        val restoredBookIds: List<String>,
        val skipped: Int,
        val restoredChildren: List<RestorableChild>,
    ) : SeriesUndoClaim
}

/** A sub-series the merge moved under the survivor, with the sibling position it had before. */
private data class RestorableChild(
    val id: SeriesId,
    val position: Int?,
)
