package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.MergeReceipt
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
}
