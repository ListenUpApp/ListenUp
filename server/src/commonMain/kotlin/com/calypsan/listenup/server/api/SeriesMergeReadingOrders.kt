package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ReadingOrderRepository

/**
 * What a series merge does to reading orders (#962): the source's live orders move onto the target, so a
 * maker's order stays in use instead of vanishing with the merged-away series, and undo moves them back.
 *
 * The merge's receipt records each order's name before the move (`series_merge_receipt_reading_orders`),
 * snapshotted inside the merge transaction. A name the target already uses gets " (from <source>)"
 * appended; undo restores the original. Follows are untouched: a follow on the source series returns
 * with the revived source, and a follow on a sub-series that pointed at a moved order still reaches it,
 * because the sub-series moved under the target too.
 */
internal class SeriesMergeReadingOrders(
    private val orders: ReadingOrderRepository,
    private val sqlDb: ListenUpDatabase,
) {
    private val receipts get() = sqlDb.seriesMergeReceiptsQueries

    /** Moves every order receipt [receiptId] recorded onto [target]. Returns the first failure, if any. */
    suspend fun moveOnto(
        receiptId: String,
        target: SeriesId,
        sourceName: String,
    ): AppError? {
        val recorded = suspendTransaction(sqlDb) { receipts.selectReceiptReadingOrders(receiptId).executeAsList() }
        for (row in recorded) {
            val order = orders.findLive(row.reading_order_id) ?: continue
            val name = freeName(target.value, row.name, sourceName)
            val moved = orders.upsert(order.copy(seriesId = target.value, name = name))
            if (moved is AppResult.Failure) return moved.error
        }
        return null
    }

    /**
     * Hands back every order receipt [receiptId] moved that is still live on [target], under its old
     * name — or, if the revived [source] has meanwhile gained an order of that name, a free variant of it.
     */
    suspend fun handBack(
        receiptId: String,
        source: SeriesId,
        target: SeriesId,
        targetName: String,
    ): AppError? {
        val restorable =
            suspendTransaction(sqlDb) {
                receipts.selectRestorableReadingOrders(receipt_id = receiptId, target_id = target.value).executeAsList()
            }
        for (row in restorable) {
            val order = orders.findLive(row.reading_order_id) ?: continue
            val name = freeName(source.value, row.name, targetName)
            val returned = orders.upsert(order.copy(seriesId = source.value, name = name))
            if (returned is AppResult.Failure) return returned.error
        }
        return null
    }

    /** [name] if [seriesId] has no live order called that, else "[name] (from [otherSeries])", numbered if needed. */
    private suspend fun freeName(
        seriesId: String,
        name: String,
        otherSeries: String,
    ): String {
        if (orders.liveIdForName(seriesId, name) == null) return name
        val suffixed = "$name (from $otherSeries)"
        var candidate = suffixed
        var number = 2
        while (orders.liveIdForName(seriesId, candidate) != null) {
            candidate = "$suffixed $number"
            number++
        }
        return candidate
    }
}
