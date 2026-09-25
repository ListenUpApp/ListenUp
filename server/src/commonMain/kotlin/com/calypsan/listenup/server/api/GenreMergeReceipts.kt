package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.api.dto.MergeUndoResult
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.MergeReceiptId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.GenreRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * What a genre merge changed, and how to put it back.
 *
 * [record] runs inside the merge's own transaction, before the relink: a merge that fails leaves no
 * receipt, and a receipt never exists without its merge.
 */
internal class GenreMergeReceipts(
    private val sqlDb: ListenUpDatabase,
    private val genreRepository: GenreRepository,
    private val bookRepository: BookRepository,
    private val clock: Clock,
) {
    private val receipts get() = sqlDb.genreMergeReceiptsQueries

    /**
     * Records [source] → [target] by [mergedBy]. Must be called inside the merge's transaction and
     * BEFORE its link copy and alias re-point — the snapshots read what those rewrite.
     */
    fun record(
        source: GenreId,
        target: GenreId,
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
        receipts.snapshotSourceLinks(receipt_id = receiptId, target_id = target.value, source_id = source.value)
        receipts.snapshotSourceAliases(receipt_id = receiptId, source_id = source.value)
    }

    /** Open receipts naming [target] as the survivor, newest first. */
    suspend fun openFor(target: GenreId): List<MergeReceipt> =
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
     * Undoes the merge [receiptId] recorded, in four steps — the same hardened shape as
     * [SeriesMergeReceipts.undo], with genre's extra wrinkle (a source that must be re-filed in the
     * tree, not just revived) split across steps 1 and 2:
     *
     *  1. A read-only [decide] transaction checks the receipt is open, its target still live, and no
     *     OTHER live genre has taken the source's slug — and computes where the source belongs now
     *     (its parent's CURRENT path, or the top level if that parent is gone). Nothing is written,
     *     so a refusal here leaves everything exactly as it was.
     *  2. The source is revived at that computed place. This runs BEFORE the claim: if it fails,
     *     nothing else has changed and the receipt stays open for a retry, instead of leaving books
     *     pointed at a tombstoned genre with no way back.
     *  3. A write [claim] transaction re-validates receipt/target/source liveness, then restores
     *     every still-as-merged link and alias and marks the receipt undone — see [claim] for why
     *     marking it is the transaction's first write.
     *  4. Every restored book is re-upserted (bumps revision, publishes `Updated`). One bad book
     *     doesn't stop the rest — the loop keeps going and the first failure is reported at the end.
     */
    suspend fun undo(receiptId: MergeReceiptId): AppResult<MergeUndoResult> {
        val decision =
            when (val decided = suspendTransaction(sqlDb) { decide(receiptId) }) {
                is GenreUndoDecision.Refused -> return AppResult.Failure(decided.error)
                is GenreUndoDecision.Allowed -> decided
            }
        val source =
            genreRepository.findById(decision.sourceId.value)
                ?: return AppResult.Failure(GenreError.NotFound(debugInfo = "id=${decision.sourceId.value}"))
        val revived =
            source.copy(deletedAt = null, parentId = decision.parentId, path = decision.path, depth = decision.depth)
        when (val upserted = genreRepository.upsert(revived)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return AppResult.Failure(upserted.error)
        }
        val claim =
            when (val decided = suspendTransaction(sqlDb) { claim(receiptId) }) {
                is GenreUndoClaim.Refused -> return AppResult.Failure(decided.error)
                is GenreUndoClaim.Granted -> decided
            }
        var firstFailure: AppError? = null
        for (book in bookRepository.findAllByIds(claim.restoredBookIds)) {
            when (val upserted = bookRepository.upsert(book)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> if (firstFailure == null) firstFailure = upserted.error
            }
        }
        firstFailure?.let { return AppResult.Failure(it) }
        return AppResult.Success(
            MergeUndoResult(
                restoredSourceId = claim.sourceId,
                booksRestored = claim.restoredBookIds.size,
                booksSkipped = claim.skipped,
                restoredAtTopLevel = decision.restoredAtTopLevel,
            ),
        )
    }

    /**
     * Read-only precondition check for [undo]'s step 1: is [receiptId] open, is its target live, and
     * is the source's slug free (no OTHER live genre has taken it)? Also computes where the source
     * belongs — under its parent's CURRENT path, or the top level when that parent is gone — which
     * [undo]'s step 2 needs to revive it.
     */
    private fun decide(receiptId: MergeReceiptId): GenreUndoDecision {
        val receipt =
            receipts.selectReceipt(receiptId.value).executeAsOneOrNull()
                ?: return GenreUndoDecision.Refused(
                    GenreError.MergeReceiptNotFound(debugInfo = "receipt=${receiptId.value}"),
                )
        if (receipt.undone_at != null) {
            return GenreUndoDecision.Refused(GenreError.MergeAlreadyUndone(debugInfo = "receipt=${receipt.id}"))
        }
        val target = sqlDb.genresQueries.selectById(receipt.target_id).executeAsOneOrNull()
        if (target == null || target.deleted_at != null) {
            return GenreUndoDecision.Refused(
                GenreError.MergeTargetGone(debugInfo = "receipt=${receipt.id} target=${receipt.target_id}"),
            )
        }
        val source = sqlDb.genresQueries.selectById(receipt.source_id).executeAsOne()
        val slugHolder = sqlDb.genresQueries.findBySlug(source.slug).executeAsOneOrNull()
        if (slugHolder != null && slugHolder != source.id) {
            return GenreUndoDecision.Refused(
                GenreError.MergeSourceNameTaken(
                    debugInfo = "receipt=${receipt.id} slug=${source.slug} holder=$slugHolder",
                ),
            )
        }
        // Re-file under the parent's CURRENT path — it may have moved since — or at the top level
        // when the parent is gone.
        val parent =
            source.parent_id
                ?.let { sqlDb.genresQueries.selectById(it).executeAsOneOrNull() }
                ?.takeIf { it.deleted_at == null }
        return GenreUndoDecision.Allowed(
            sourceId = GenreId(receipt.source_id),
            parentId = parent?.id,
            path = (parent?.path ?: "") + "/" + source.slug,
            depth = parent?.let { it.depth.toInt() + 1 } ?: 0,
            restoredAtTopLevel = source.parent_id != null && parent == null,
        )
    }

    /**
     * The write half of [undo]'s step 3: re-validates receipt/target/source liveness (the source may
     * have been revived by [undo]'s step 2 in the meantime, but the receipt's own state, its target's
     * liveness, and the source's liveness can still have moved), then restores links and aliases and
     * marks the receipt undone.
     *
     * `markUndone` runs FIRST, as the only write before this function has decided anything else —
     * its affected-row count doubles as the double-undo guard: 0 rows means a concurrent undo already
     * claimed this receipt between [decide] and here. That ordering matters because
     * [suspendTransaction] commits on a normal return, [Refused] included — a refusal returned AFTER
     * a link or alias write would still commit that write, so every read-only check that can refuse
     * (receipt exists, target live, source live) runs before the transaction's first write.
     */
    private fun claim(receiptId: MergeReceiptId): GenreUndoClaim {
        val receipt =
            receipts.selectReceipt(receiptId.value).executeAsOneOrNull()
                ?: return GenreUndoClaim.Refused(
                    GenreError.MergeReceiptNotFound(debugInfo = "receipt=${receiptId.value}"),
                )
        val target = sqlDb.genresQueries.selectById(receipt.target_id).executeAsOneOrNull()
        if (target == null || target.deleted_at != null) {
            return GenreUndoClaim.Refused(
                GenreError.MergeTargetGone(debugInfo = "receipt=${receipt.id} target=${receipt.target_id}"),
            )
        }
        // Step 2's revive ran outside this transaction, so the source could have been merged away
        // again in the gap before we got here (an admin merging the just-revived source into a third
        // genre). Catch that here, before markUndone — a refusal after it would still commit it.
        val source = sqlDb.genresQueries.selectById(receipt.source_id).executeAsOneOrNull()
        if (source == null || source.deleted_at != null) {
            return GenreUndoClaim.Refused(
                GenreError.NotFound(debugInfo = "receipt=${receipt.id} source=${receipt.source_id}"),
            )
        }
        val claimed = receipts.markUndone(undone_at = clock.now().toEpochMilliseconds(), id = receipt.id)
        if (claimed.value == 0L) {
            return GenreUndoClaim.Refused(GenreError.MergeAlreadyUndone(debugInfo = "receipt=${receipt.id}"))
        }
        val restorable =
            receipts.selectRestorableBooks(receipt_id = receipt.id, target_id = receipt.target_id).executeAsList()
        val recorded = receipts.countReceiptBooks(receipt.id).executeAsOne()
        for (book in restorable) {
            sqlDb.bookGenresQueries.insertIfAbsent(book_id = book.book_id, genre_id = receipt.source_id)
            if (book.was_in_target == 0L) {
                sqlDb.bookGenresQueries.deleteLink(book_id = book.book_id, genre_id = receipt.target_id)
            }
        }
        for (raw in receipts.selectReceiptAliases(receipt.id).executeAsList()) {
            sqlDb.genreAliasesQueries.repointAliasIfOwnedBy(
                to_genre_id = receipt.source_id,
                raw_string = raw,
                from_genre_id = receipt.target_id,
            )
        }
        return GenreUndoClaim.Granted(
            sourceId = receipt.source_id,
            restoredBookIds = restorable.map { it.book_id },
            skipped = (recorded - restorable.size).toInt(),
        )
    }
}

/** The outcome of [GenreMergeReceipts.decide]'s read-only precondition check. */
private sealed interface GenreUndoDecision {
    /** The undo may not proceed. */
    data class Refused(
        val error: GenreError,
    ) : GenreUndoDecision

    /**
     * The receipt is open, its target is live, and the source's slug is free. Carries where the
     * source belongs — [path] under [parentId], or the top level when [parentId] is null — which
     * [GenreMergeReceipts.undo] needs to revive it.
     */
    data class Allowed(
        val sourceId: GenreId,
        val parentId: String?,
        val path: String,
        val depth: Int,
        val restoredAtTopLevel: Boolean,
    ) : GenreUndoDecision
}

/** The outcome of claiming a genre merge receipt for undo. */
private sealed interface GenreUndoClaim {
    /** The undo may not proceed; nothing was written. */
    data class Refused(
        val error: GenreError,
    ) : GenreUndoClaim

    /** Links and aliases are restored and the receipt is marked; the source and books still need re-upserting. */
    data class Granted(
        val sourceId: String,
        val restoredBookIds: List<String>,
        val skipped: Int,
    ) : GenreUndoClaim
}
