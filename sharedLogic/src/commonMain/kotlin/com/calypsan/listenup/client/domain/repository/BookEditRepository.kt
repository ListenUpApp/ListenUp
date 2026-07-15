@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId

/**
 * Client-side write surface for book editing.
 *
 * [updateBook] is offline-first; the remaining methods dispatch via
 * [com.calypsan.listenup.api.BookService] over RPC, with authoritative state
 * arriving back via the SSE sync engine (no optimistic Room writes).
 *
 * Wire-side DTOs ([BookUpdate], [BookContributorInput], [BookSeriesInput])
 * are passed through unchanged — the contract is the source of truth.
 */
interface BookEditRepository {
    /**
     * Applies the PATCH payload [patch] to the book identified by [id].
     *
     * Offline-first: every non-null field is written to Room immediately and a
     * durable pending op is enqueued, so the edit survives and replays on
     * reconnect rather than failing offline. The authoritative state still
     * arrives via the SSE sync engine and reconciles the optimistic write.
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
     * Replaces the full chapter list for the book identified by [id] and marks
     * provenance USER server-side. Dispatches via
     * [com.calypsan.listenup.api.BookService.setBookChapters]; authoritative
     * state returns through the SSE sync engine (no optimistic Room write).
     */
    suspend fun setBookChapters(
        id: BookId,
        chapters: List<ChapterInput>,
    ): AppResult<Unit>

    /**
     * Renames the book's two chapter-grouping tiers to [bookTierLabel] (outer) and
     * [partTierLabel] (inner). Dispatches via
     * [com.calypsan.listenup.api.BookService.setBookTierLabels]; authoritative state returns
     * through the SSE sync engine (no optimistic Room write).
     */
    suspend fun setBookTierLabels(
        id: BookId,
        bookTierLabel: String?,
        partTierLabel: String?,
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
