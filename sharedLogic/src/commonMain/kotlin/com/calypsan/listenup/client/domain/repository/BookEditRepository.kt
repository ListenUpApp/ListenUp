package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.result.AppResult
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
     * Replaces the full genre list for the book identified by [id].
     *
     * Unlike contributors/series, genres are NOT auto-created — each input's
     * `genreId` must reference an existing live genre. Unknown ids surface as
     * [com.calypsan.listenup.api.error.BookError.InvalidInput]. Curators add new
     * genres through [GenreRepository.createGenre], not by editing a book.
     */
    suspend fun setBookGenres(
        id: BookId,
        genres: List<BookGenreInput>,
    ): AppResult<Unit>

    /**
     * Removes the cover from the book identified by [id]: nulls cover state on
     * the book row and best-effort-deletes the underlying file after commit.
     */
    suspend fun deleteBookCover(id: BookId): AppResult<Unit>

    /**
     * Replace-sets the collections the book identified by [id] belongs to (admin-only).
     *
     * Diffs the book's current live memberships against [collectionIds]: soft-deletes
     * removed, upserts added. The server emits per-user `AccessChanged` to the affected
     * users so their clients reconcile — there is no optimistic Room write here. Dispatches
     * via [com.calypsan.listenup.api.CollectionService.setBookCollections].
     */
    suspend fun setBookCollections(
        id: BookId,
        collectionIds: List<String>,
    ): AppResult<Unit>
}
