package com.calypsan.listenup.client.features.seriesedit.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.features.shelf.ShelfCellBounds
import com.calypsan.listenup.client.presentation.shelf.reorderedBy
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

/**
 * A drag in progress over the sub-series list: which row is lifted, the order the rows show while
 * it moves, and how far the lifted row sits from its slot. Rows report their [spans] as they lay
 * out; the lifted row crosses into a neighbour's slot one step at a time.
 */
internal class SubSeriesDrag {
    var draggingId by mutableStateOf<String?>(null)
        private set
    var order by mutableStateOf<List<String>>(emptyList())
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    val spans = mutableStateMapOf<String, RowSpan>()

    /** Lift [id] out of [current]. */
    fun start(
        id: String,
        current: List<String>,
    ) {
        draggingId = id
        order = current
        offset = 0f
    }

    /** Move the lifted row by [dy]; true when it crossed into another slot. */
    fun dragBy(dy: Float): Boolean {
        val id = draggingId ?: return false
        offset += dy
        val index = order.indexOf(id)
        val self = spans[id] ?: return false
        val rows = order.mapNotNull { spans[it] }
        if (rows.size != order.size) return false
        val target = slotUnder(rows, (self.top + self.bottom) / 2 + offset) ?: return false
        if (target == index) return false
        val step = if (target > index) 1 else -1
        val neighbour = spans[order[index + step]] ?: return false
        // Keep the row under the finger as its slot moves by the neighbour's place, and move both
        // spans now rather than waiting a frame for layout to report them — a stale span would read
        // the row as still in its old slot and swap it straight back.
        val shift = if (step > 0) neighbour.bottom - self.bottom else neighbour.top - self.top
        offset -= shift
        spans[id] = self.copy(top = self.top + shift, bottom = self.bottom + shift)
        val neighbourShift = if (step > 0) self.top - neighbour.top else self.bottom - neighbour.bottom
        spans[neighbour.id] =
            neighbour.copy(top = neighbour.top + neighbourShift, bottom = neighbour.bottom + neighbourShift)
        order = reorderedBy(order, index, index + step)
        return true
    }

    /** Drop the lifted row: its id and the order it landed in, or null when nothing was lifted. */
    fun end(): Pair<String, List<String>>? {
        val id = draggingId ?: return null
        draggingId = null
        offset = 0f
        return id to order
    }
}
