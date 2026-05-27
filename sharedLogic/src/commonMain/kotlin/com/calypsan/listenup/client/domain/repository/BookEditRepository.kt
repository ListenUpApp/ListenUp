package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId

/**
 * Client-side write surface for book editing.
 *
 * All methods dispatch via [com.calypsan.listenup.api.BookService] over RPC.
 * Authoritative state arrives back via the SSE sync engine; this interface
 * makes no optimistic Room writes.
 *
 * Wire-side DTOs ([BookUpdate], [BookContributorInput], [BookSeriesInput])
 * are passed through unchanged — the contract is the source of truth.
 */
interface BookEditRepository {
    /**
     * Applies the PATCH payload [patch] to the book identified by [id].
     *
     * Every non-null field on [patch] replaces the current value; null fields
     * leave existing state untouched. The server emits an SSE event with the
     * updated payload on success; clients update Room reactively.
     */
    suspend fun updateBook(
        id: BookId,
        patch: BookUpdate,
    ): AppResult<Unit>

    /**
     * Replaces the full contributor list for the book identified by [id].
     * Inputs without an id resolve via the server's `resolveOrCreate` path.
     */
    suspend fun setBookContributors(
        id: BookId,
        contributors: List<BookContributorInput>,
    ): AppResult<Unit>

    /**
     * Replaces the full series list for the book identified by [id].
     * Inputs without an id resolve via the server's `resolveOrCreate` path.
     */
    suspend fun setBookSeries(
        id: BookId,
        series: List<BookSeriesInput>,
    ): AppResult<Unit>

    /**
     * Removes the cover from the book identified by [id]: nulls cover state on
     * the book row and best-effort-deletes the underlying file after commit.
     */
    suspend fun deleteBookCover(id: BookId): AppResult<Unit>
}
