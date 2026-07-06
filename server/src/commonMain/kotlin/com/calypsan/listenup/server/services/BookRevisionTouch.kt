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

    /**
     * Bumps every book in [ids] in a single transaction, assigning each row its own revision from the
     * global counter — never one shared revision, because `pullSince` pages by `revision > cursor` and
     * equal revisions straddling a page boundary would be skipped (silent client divergence). Missing
     * ids are skipped (mirrors [BookRepository.reviveByIds]); an empty list is a no-op success. The
     * default implementation loops [touchRevision] so existing implementations and fakes stay
     * source-compatible; [BookRepository] overrides it with the batched, single-transaction form.
     */
    suspend fun touchRevisions(ids: List<BookId>): AppResult<Unit> {
        for (id in ids) touchRevision(id)
        return AppResult.Success(Unit)
    }
}
