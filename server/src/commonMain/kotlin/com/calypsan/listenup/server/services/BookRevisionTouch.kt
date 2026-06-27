package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId

/**
 * Bumps a book's sync revision without changing its content, so a visibility-only change
 * (collection membership add/remove) re-enters every member's incremental
 * `revision > cursor AND <accessible>` pull and newly-visible books reach them.
 *
 * A narrow capability extracted from [BookRepository] so consumers (e.g. CollectionServiceImpl)
 * depend only on the touch, and seam-level tests can fake it.
 */
interface BookRevisionTouch {
    suspend fun touchRevision(id: BookId): AppResult<Unit>
}
