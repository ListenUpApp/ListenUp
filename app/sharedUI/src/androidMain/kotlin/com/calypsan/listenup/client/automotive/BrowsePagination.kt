package com.calypsan.listenup.client.automotive

/**
 * Slices a full browse/search result list to the head unit's requested page — the one
 * pagination shape shared by `onGetChildren` and `onGetSearchResult` (#1238).
 *
 * Legacy browsers that don't paginate send `pageSize = Int.MAX_VALUE` (or the callback's
 * documented "no paging" values) — long arithmetic keeps the end index from overflowing,
 * and a non-positive [pageSize] returns the whole list.
 */
internal fun <T> paginate(
    items: List<T>,
    page: Int,
    pageSize: Int,
): List<T> {
    if (pageSize <= 0) return items
    val start = page.coerceAtLeast(0).toLong() * pageSize
    if (start >= items.size) return emptyList()
    val end = minOf(start + pageSize, items.size.toLong())
    return items.subList(start.toInt(), end.toInt())
}
