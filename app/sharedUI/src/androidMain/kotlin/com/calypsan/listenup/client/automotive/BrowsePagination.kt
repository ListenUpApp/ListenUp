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

/**
 * Whether [page] is the final page of [items] under [paginate]'s clamping rules — kept beside
 * it so the boundary math has exactly one home. A non-positive [pageSize] means the head unit
 * didn't paginate: the whole list was returned, so it is by definition the last page.
 */
internal fun isLastPage(
    items: List<*>,
    page: Int,
    pageSize: Int,
): Boolean = pageSize <= 0 || (page.coerceAtLeast(0).toLong() + 1) * pageSize >= items.size
