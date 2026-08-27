package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for on-demand book access and user-edit mutations.
 *
 * Two surface categories:
 * - **Observation** — [getBook] is safe to call repeatedly.
 * - **Mutation** — [updateBook], [setBookContributors], [setBookSeries],
 *   [deleteBookCover] mutate server state; the sync firehose delivers the authoritative
 *   payload back to all connected clients.
 *
 * REST mirrors are defined in `BookResources`.
 */
@Rpc
interface BookService {
    // ── Observation (existing) ───────────────────────────────────────────────

    /**
     * Returns the full book aggregate for [id], or a [com.calypsan.listenup.api.error.SyncError.NotFound]
     * failure when no such book exists on the server.
     *
     * The primary use case is a cache miss: a search result or deep link
     * references a book the client's Room database hasn't synced yet. Rather
     * than showing an error, the client calls [getBook] to fetch the aggregate
     * on demand and render immediately while sync catches up in the background.
     */
    suspend fun getBook(id: BookId): AppResult<BookSyncPayload>

    // ── Mutation (new in Books-C1) ───────────────────────────────────────────

    /**
     * Applies the PATCH payload [patch] to the book identified by [id].
     *
     * Every non-null field on [patch] replaces the current value; null fields
     * leave existing state untouched. Returns
     * [com.calypsan.listenup.api.error.BookError.NotFound] when no book with
     * the given id exists.
     *
     * On success the server emits a sync event with the updated
     * [BookSyncPayload]; clients update Room reactively.
     */
    suspend fun updateBook(
        id: BookId,
        patch: BookUpdate,
    ): AppResult<Unit>

    /**
     * Replaces the full contributor list for the book identified by [id] with
     * [contributors]. Inputs without [BookContributorInput.id] resolve via
     * `ContributorRepository.resolveOrCreate`; unknown names create fresh
     * contributor rows in the same transaction.
     *
     * Returns [com.calypsan.listenup.api.error.BookError.NotFound] when no book
     * exists. Server-side guard limits inputs to 200; overflow surfaces as
     * [com.calypsan.listenup.api.error.BookError.InvalidInput].
     */
    suspend fun setBookContributors(
        id: BookId,
        contributors: List<BookContributorInput>,
    ): AppResult<Unit>

    /**
     * Replaces the full series list for the book identified by [id] with [series].
     * Same find-or-create semantics as [setBookContributors] for unknown names.
     */
    suspend fun setBookSeries(
        id: BookId,
        series: List<BookSeriesInput>,
    ): AppResult<Unit>

    /**
     * Replaces the full genre list for the book identified by [id] with [genres].
     *
     * Unlike [setBookContributors] / [setBookSeries], genres are NOT auto-created.
     * Each input's [com.calypsan.listenup.api.dto.BookGenreInput.genreId] must
     * reference an existing live genre; unknown ids surface as
     * [com.calypsan.listenup.api.error.BookError.InvalidInput]. The genre
     * taxonomy is curator-controlled — books can only join existing genres.
     *
     * Returns [com.calypsan.listenup.api.error.BookError.NotFound] when no book
     * exists. Server-side guard limits inputs to 200; overflow surfaces as
     * [com.calypsan.listenup.api.error.BookError.InvalidInput].
     */
    suspend fun setBookGenres(
        id: BookId,
        genres: List<BookGenreInput>,
    ): AppResult<Unit>

    /**
     * Replaces the full chapter list for the book identified by [id] with
     * [chapters], and marks the book's chapter provenance as
     * [com.calypsan.listenup.api.sync.ChapterSource.USER] so a later rescan
     * will not overwrite the edit.
     *
     * Chapters are contiguous and absolute-time: [com.calypsan.listenup.api.dto.ChapterInput.startTime]
     * is the offset from the start of the book. The server validates the set
     * (strictly increasing starts, all within the book duration); violations
     * surface as [com.calypsan.listenup.api.error.BookError.InvalidInput].
     * Returns [com.calypsan.listenup.api.error.BookError.NotFound] when no book
     * exists. On success the substrate emits a sync `Updated<BookSyncPayload>`;
     * clients update Room reactively.
     */
    suspend fun setBookChapters(
        id: BookId,
        chapters: List<ChapterInput>,
    ): AppResult<Unit>

    /**
     * Renames the two chapter-grouping tiers of the book identified by [id] — the vocabulary the
     * book uses for its own structure ("Part"/"Book", "Sequence"/"Era").
     *
     * Either may be null to leave that tier unnamed. A non-null label must be non-blank and at most
     * [com.calypsan.listenup.domain.TierLabelLimits.MAX_LENGTH] characters; violations surface as
     * [com.calypsan.listenup.api.error.BookError.InvalidInput]. Returns
     * [com.calypsan.listenup.api.error.BookError.NotFound] when no book exists.
     *
     * This is a targeted two-column update, not a read-then-upsert of the whole aggregate: a tier
     * name the user chose must survive a concurrent rescan rewriting the rest of the row. On
     * success the substrate emits a sync `Updated<BookSyncPayload>` carrying
     * [com.calypsan.listenup.api.sync.BookSyncPayload.bookTierLabel] /
     * [com.calypsan.listenup.api.sync.BookSyncPayload.partTierLabel]; clients update Room reactively.
     */
    suspend fun setBookTierLabels(
        id: BookId,
        bookTierLabel: String?,
        partTierLabel: String?,
    ): AppResult<Unit>

    /**
     * Removes the cover from the book identified by [id]: nulls cover state on
     * the book row and best-effort-deletes the underlying file after commit.
     *
     * Returns [com.calypsan.listenup.api.error.CoverError.NotPresent] when the
     * book has no cover to delete. Returns
     * [com.calypsan.listenup.api.error.BookError.NotFound] when no book exists.
     */
    suspend fun deleteBookCover(id: BookId): AppResult<Unit>

    /**
     * **Deletes the book identified by [id] from the library and from the disk** — its whole
     * directory, including every non-audio file in it (bonus PDFs, cover art, anything else the
     * folder holds). Admin-only. There is no undo.
     *
     * The folder goes as a unit deliberately: deleting only the audio would strand its companions
     * in a directory that no longer corresponds to anything the app can show — litter the user can
     * neither see nor manage. Callers must say so plainly before asking; a confirmation that does
     * not mention the extra files is consent to something the user was never told.
     *
     * Three refusals, each re-checked at delete time and each leaving **everything on disk
     * untouched**:
     * - [com.calypsan.listenup.api.error.BookError.FolderNotExclusive] — another live book sits in
     *   the same directory, or in one nested beneath it. The error names that book.
     * - [com.calypsan.listenup.api.error.LibraryWriteError.ProtectedPath] — the directory resolves
     *   to a library folder root itself, or is a symbolic link.
     * - [com.calypsan.listenup.api.error.LibraryWriteError.OutsideLibrary] — the directory does not
     *   resolve inside any live library folder.
     *
     * Returns [com.calypsan.listenup.api.error.BookError.NotFound] when no such book exists or it
     * is already tombstoned, and [com.calypsan.listenup.api.error.AuthError.PermissionDenied] for a
     * non-admin caller.
     *
     * On success the book's tombstone rides the sync firehose, so every other device drops it.
     */
    suspend fun deleteBook(id: BookId): AppResult<Unit>
}
