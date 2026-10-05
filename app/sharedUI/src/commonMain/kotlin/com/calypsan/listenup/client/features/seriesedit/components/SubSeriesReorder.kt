package com.calypsan.listenup.client.features.seriesedit.components

import com.calypsan.listenup.client.features.shelf.ShelfCellBounds
import com.calypsan.listenup.client.features.shelf.cellKeyAt

/** One laid-out sub-series row: its id and vertical extent in the list's coordinates. */
internal data class RowSpan(
    val id: String,
    val top: Float,
    val bottom: Float,
)

/**
 * The index of the row a dragged row's centre [y] is over, among [rows] in list order.
 *
 * Unlike a shelf drop, a list drag never lands "nowhere": past the first row is the first slot and
 * past the last is the last, so a fling off either end still moves the row to that end. The gap
 * between two rows belongs to neither and keeps the current slot (null).
 */
internal fun slotUnder(
    rows: List<RowSpan>,
    y: Float,
): Int? {
    if (rows.isEmpty()) return null
    if (y < rows.first().top) return 0
    if (y >= rows.last().bottom) return rows.lastIndex
    val key = cellKeyAt(rows.map { ShelfCellBounds(it.id, 0f, it.top, 1f, it.bottom) }, 0f, y) ?: return null
    return rows.indexOfFirst { it.id == key }
}
