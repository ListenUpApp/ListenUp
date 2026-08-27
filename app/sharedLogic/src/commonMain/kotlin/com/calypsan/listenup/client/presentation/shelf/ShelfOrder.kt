package com.calypsan.listenup.client.presentation.shelf

/**
 * The list that results from moving the item at [from] onto position [to].
 *
 * Remove-then-insert, which is what makes a downward move land where the pointer is rather than one
 * short of it: once the item is lifted, everything after it has already shifted up by one, and
 * inserting at the raw target index would account for that shift twice.
 *
 * Out-of-range or no-op moves return the list unchanged rather than throwing. A drag can end
 * anywhere — outside the list, on itself, on a row that has since gone — and none of those are
 * errors worth interrupting someone over.
 *
 * Shared rather than per-client because every platform's drag ends in the same question, and the
 * off-by-one is invisible in a screenshot: it looks like the list simply moved to the wrong place.
 */
fun <T> reorderedBy(
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

/**
 * [reorderedBy] over plain ids — the shape Swift can call.
 *
 * The generic version does not survive the export boundary usefully, and a Swift reimplementation
 * would be a third copy of the one rule this file exists to keep singular. Ids rather than models
 * because that is what a reorder sends anyway.
 */
fun reorderedIds(
    ids: List<String>,
    from: Int,
    to: Int,
): List<String> = reorderedBy(ids, from, to)
