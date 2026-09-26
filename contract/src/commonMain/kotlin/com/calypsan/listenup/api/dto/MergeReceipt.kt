package com.calypsan.listenup.api.dto

import com.calypsan.listenup.core.MergeReceiptId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One undoable merge: a series or genre that was merged into the one being viewed.
 *
 * [mergedAt] is epoch milliseconds. [mergedByName] is the merging user's display name, or null
 * once that account is gone. [bookCount] is the most an undo could move back — books changed since
 * the merge are left alone, so the real number is only known when the undo runs.
 */
@Serializable
data class MergeReceipt(
    @SerialName("id") val id: MergeReceiptId,
    @SerialName("sourceName") val sourceName: String,
    @SerialName("mergedAt") val mergedAt: Long,
    @SerialName("mergedByName") val mergedByName: String?,
    @SerialName("bookCount") val bookCount: Int,
)

/**
 * What an undo put back. [booksSkipped] counts the merged books that had changed since the merge —
 * deleted, or moved out of the surviving series or genre — and were left alone.
 * [restoredAtTopLevel] is true when a genre came back at the top of the tree because its old
 * parent no longer exists; it is always false for series.
 */
@Serializable
data class MergeUndoResult(
    @SerialName("restoredSourceId") val restoredSourceId: String,
    @SerialName("booksRestored") val booksRestored: Int,
    @SerialName("booksSkipped") val booksSkipped: Int,
    @SerialName("restoredAtTopLevel") val restoredAtTopLevel: Boolean,
)
