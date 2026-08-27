package com.calypsan.listenup.client.features.shelf

/**
 * One laid-out grid cell, reduced to the only things a drop needs to know about it.
 *
 * Plain numbers rather than Compose geometry types so the hit-testing below can be tested without a
 * composition — the arithmetic is the part that goes wrong, and it does not need a screen to prove.
 *
 * @property key The item's stable key. For a book cell this is its book id; the shelf's header
 *   cells carry keys of their own, which is exactly why a drop has to check.
 */
data class ShelfCellBounds(
    val key: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * The key of the cell under ([x], [y]), or null when the pointer is over none of them.
 *
 * Null is a real answer, not a failure: a grid's last row is usually short, so the space beside its
 * final book belongs to no cell at all, and a drop there must leave the shelf alone rather than
 * quietly picking the nearest neighbour.
 *
 * Bounds are half-open on the right and bottom edges, so two adjacent cells never both claim the
 * pixel between them — otherwise the answer would depend on list order rather than geometry.
 */
fun cellKeyAt(
    cells: List<ShelfCellBounds>,
    x: Float,
    y: Float,
): String? =
    cells
        .firstOrNull { x >= it.left && x < it.right && y >= it.top && y < it.bottom }
        ?.key
