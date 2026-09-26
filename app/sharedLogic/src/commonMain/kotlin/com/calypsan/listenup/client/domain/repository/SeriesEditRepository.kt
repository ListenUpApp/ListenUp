@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.MergeReceiptId
import com.calypsan.listenup.api.dto.MergeUndoResult
import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.SeriesId

/**
 * Client-side write surface for series editing.
 *
 * RPC-backed. The firehose delivers authoritative state back via the `series` sync domain.
 */
interface SeriesEditRepository {
    /**
     * Applies the PATCH payload [patch] to the series identified by [id].
     *
     * Every non-null field on [patch] replaces the current value; null fields
     * leave existing state untouched. The server emits a sync event with the
     * updated payload on success; clients update Room reactively.
     */
    suspend fun updateSeries(
        id: SeriesId,
        patch: SeriesUpdate,
    ): AppResult<Unit>

    /**
     * Hard-deletes all `book_series_memberships` junction rows referencing [id],
     * then soft-deletes the series row. The server emits sync events for the
     * affected books and the series; clients update Room reactively.
     */
    suspend fun deleteSeries(id: SeriesId): AppResult<Unit>

    /**
     * Merges series [source] into series [target]. After firehose delivery:
     * - All books in source's series now show target's series.
     * - Source is soft-deleted.
     *
     * Server-canonical — no optimistic Room writes.
     *
     * Returns [com.calypsan.listenup.api.error.SeriesError.MergeSelfTarget] when `source == target`.
     * Returns [com.calypsan.listenup.api.error.SeriesError.NotFound] when either is missing.
     */
    suspend fun mergeSeries(
        source: SeriesId,
        target: SeriesId,
    ): AppResult<Unit>

    /**
     * The merges folded into [target] that can still be undone, newest first (#1061). Server-only:
     * the receipts live on the server, and merges made before receipts existed are not listed.
     */
    suspend fun listMergeReceipts(target: SeriesId): AppResult<List<MergeReceipt>>

    /**
     * Undoes the merge [receiptId]: the merged-away series comes back and the books that are still
     * as the merge left them move back to it. Books changed since are left alone and counted in
     * [MergeUndoResult.booksSkipped]. The effects reach Room through the firehose.
     */
    suspend fun undoMerge(receiptId: MergeReceiptId): AppResult<MergeUndoResult>
}
