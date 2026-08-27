package com.calypsan.listenup.web.features.shelf

/**
 * The list that results from dragging the item at [from] onto position [to].
 *
 * Remove-then-insert, which is what makes a downward drag land where the pointer is rather than one
 * short of it: once the item is lifted, everything after it has already shifted up by one, and
 * inserting at the raw target index would account for that shift twice.
 *
 * Out-of-range or no-op moves return the list unchanged rather than throwing. A drag can end
 * anywhere — outside the list, on itself, on a row that has since gone — and none of those are
 * errors worth interrupting someone over.
 */
internal fun <T> reorderedBy(
    items: List<T>,
    from: Int,
    to: Int,
): List<T> {
    if (from == to) return items
    if (from !in items.indices) return items
    if (to !in items.indices) return items
    val mutable = items.toMutableList()
    val moved = mutable.removeAt(from)
    mutable.add(to, moved)
    return mutable
}
