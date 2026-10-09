package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.match.LastMatch

/**
 * A book's [revision] beside the [lastMatch] its sync payload last carried, as Room holds them — what Book Detail's
 * "Undo last match" is derived from, offline included.
 */
data class BookMatchRecord(
    val revision: Long,
    val lastMatch: LastMatch?,
) {
    /**
     * The match that can still be undone: [lastMatch] while the book is at the revision the match left it at. Any
     * later change — an edit, a rescan, another match, a sync from another device — retires it, because restoring
     * the snapshot over that change would silently revert it.
     */
    val liveMatch: LastMatch?
        get() = lastMatch?.takeIf { it.revision == revision }
}
