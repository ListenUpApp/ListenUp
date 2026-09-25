package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.MergeReceipt
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
}
