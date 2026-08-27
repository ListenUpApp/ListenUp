package com.calypsan.listenup.web.features.shelf

/**
 * Which shelf screen a URL asks for, if any.
 *
 * A parser rather than three conditions in the shell's route chain: `/shelf/new`, `/shelf/{id}` and
 * `/shelf/{id}/edit` differ only in the shape of their segments, and spelling that out inline made
 * the dispatch grow a branch per screen — the shell's `RouteContent` tripped its complexity limit
 * on the third one. This turns three branches into one and makes the URL grammar testable on its
 * own, without a composition.
 */
internal sealed interface ShelfRoute {
    /** `/shelf/new` — the create form. */
    data object Create : ShelfRoute

    /** `/shelf/{id}/edit` — the edit form for an existing shelf. */
    data class Edit(
        val shelfId: String,
    ) : ShelfRoute

    /** `/shelf/{id}` — one shelf and its books. */
    data class Detail(
        val shelfId: String,
    ) : ShelfRoute
}

/**
 * Read [segments] as a shelf route, or null when they are not one.
 *
 * Null for anything unrecognised — a blank id, a trailing segment nobody serves — so an odd URL
 * falls through to the shell's own not-found handling rather than opening a screen for a shelf that
 * cannot exist.
 */
internal fun shelfRouteOf(segments: List<String>): ShelfRoute? {
    if (segments.firstOrNull() != SHELF_SEGMENT) return null
    val second = segments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
    return when {
        second == NEW_SEGMENT && segments.size == 2 -> ShelfRoute.Create
        segments.size == 2 -> ShelfRoute.Detail(second)
        segments.size == 3 && segments[2] == EDIT_SEGMENT -> ShelfRoute.Edit(second)
        else -> null
    }
}

internal const val SHELF_SEGMENT = "shelf"

internal const val NEW_SEGMENT = "new"

private const val EDIT_SEGMENT = "edit"
