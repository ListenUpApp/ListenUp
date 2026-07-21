@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.api.dto.GenreUpdate
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.client.domain.model.Genre
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for the curator-controlled genre taxonomy.
 *
 * The genre tree is a syncable domain — the server is authoritative, the firehose
 * delivers state changes, and Room is the client-side mirror. Tree reads
 * (`observeAll`, `getById`, `getBySlug`, `observeGenresForBook`) consult Room
 * directly. Admin mutations (`createGenre`, `updateGenre`, `deleteGenre`,
 * `moveGenre`, `mergeGenres`) dispatch through
 * [com.calypsan.listenup.api.GenreService] over RPC; authoritative state
 * arrives back via the sync engine.
 *
 * `setBookGenres` lives on [BookEditRepository], not here — it's a per-book
 * write, not a taxonomy mutation. Matches the C1/C2 placement of
 * `setBookContributors` / `setBookSeries`.
 *
 * Read methods cannot fail and return plain values. Mutation methods return
 * [AppResult]; success carries the payload (or [Unit]) and failure carries a
 * typed [com.calypsan.listenup.api.error.AppError].
 */
interface GenreRepository {
    // ── Observation (Room-backed) ────────────────────────────────────────────

    /** Observe all live genres reactively, hierarchical order, with book counts. */
    fun observeAll(): Flow<List<Genre>>

    /** Get all live genres synchronously. */
    suspend fun getAll(): List<Genre>

    /** Get a single live genre by id (null when missing or tombstoned). */
    suspend fun getById(id: String): Genre?

    /** Get a single live genre by URL slug (null when missing or tombstoned). */
    suspend fun getBySlug(slug: String): Genre?

    /** Observe a single book's genres reactively. */
    fun observeGenresForBook(bookId: String): Flow<List<Genre>>

    /** Get a single book's genres synchronously. */
    suspend fun getGenresForBook(bookId: String): List<Genre>

    /** Get all live book ids linked to the given genre. */
    suspend fun getBookIdsForGenre(genreId: String): List<String>

    // ── Curator admin (RPC-dispatched) ───────────────────────────────────────

    /**
     * Creates a new genre under [parentId] (or as a root when null) with the
     * given [name] and optional [sortOrder]. Server derives the slug from
     * [name]. Authoritative state arrives via the firehose; this returns the new
     * [GenreId] on success.
     */
    suspend fun createGenre(
        name: String,
        parentId: GenreId? = null,
        sortOrder: Int = 0,
    ): AppResult<GenreId>

    /** PATCHes the genre identified by [id] with [patch]. Null fields preserved. */
    suspend fun updateGenre(
        id: GenreId,
        patch: GenreUpdate,
    ): AppResult<Unit>

    /** Soft-deletes the genre; refuses if it has live descendants. */
    suspend fun deleteGenre(id: GenreId): AppResult<Unit>

    /** Moves the subtree rooted at [id] under [newParentId] (or to root when null). */
    suspend fun moveGenre(
        id: GenreId,
        newParentId: GenreId?,
    ): AppResult<Unit>

    /** Merges genre [source] into genre [target]; refuses if source has descendants. */
    suspend fun mergeGenres(
        source: GenreId,
        target: GenreId,
    ): AppResult<Unit>

    /** Returns books linked to [genreId], optionally including the subtree. */
    suspend fun browseBooks(
        genreId: GenreId,
        includeDescendants: Boolean = false,
        limit: Int = 100,
    ): AppResult<List<BookId>>

    /**
     * Aggregate book count + total length for [genreId], counting the whole subtree when
     * [includeDescendants]. Dispatches through [com.calypsan.listenup.api.GenreService] — not
     * Room-backed, since total book length isn't mirrored locally.
     */
    suspend fun getGenreStats(
        genreId: GenreId,
        includeDescendants: Boolean,
    ): AppResult<FacetStats>

    /**
     * Resolve a genre by [slug] via RPC for deep-linking — server-authoritative, unlike the
     * Room-backed [getBySlug]. Returns `null` inside [AppResult.Success] when no live genre
     * matches.
     */
    suspend fun getGenreBySlug(slug: String): AppResult<Genre?>
}
