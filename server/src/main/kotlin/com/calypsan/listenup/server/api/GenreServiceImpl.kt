package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.dto.GenreSummary
import com.calypsan.listenup.api.dto.GenreUpdate
import com.calypsan.listenup.api.dto.UnmappedStringSummary
import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.GenreSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.sync.BookSearchReindexer
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * [GenreService] implementation. Genres are a curator-controlled hierarchical
 * taxonomy with materialized-path storage; this class implements the read,
 * admin, and unmapped-curation surfaces against [GenreRepository] +
 * [BookRepository] + [BookSearchReindexer].
 *
 * Every mutation method runs inside a single transaction; post-commit
 * `BookSearchReindexer.reindexAllBooksForGenre` / `reindexAllBooksForSubtree`
 * keeps `book_search.genres` consistent with the live junction state.
 *
 * Stub state: all methods currently return
 * [GenreError.NotFound] with `debugInfo = "not yet implemented"`. Real impls
 * land in Tasks 13–19 of the genres plan (TDD per method).
 *
 * // TODO: gate by user permissions when Multi-user lands
 */
@Suppress("UnusedPrivateProperty")
internal class GenreServiceImpl(
    private val genreRepository: GenreRepository,
    private val bookRepository: BookRepository,
    private val reindexer: BookSearchReindexer,
    private val db: Database,
) : GenreService {
    override suspend fun listGenres(): AppResult<List<GenreSummary>> = notYetImplemented()

    override suspend fun getGenre(id: GenreId): AppResult<GenreSyncPayload?> = notYetImplemented()

    override suspend fun getGenreChildren(parentId: GenreId): AppResult<List<GenreSyncPayload>> = notYetImplemented()

    override suspend fun browseBooks(
        genreId: GenreId,
        includeDescendants: Boolean,
        limit: Int,
    ): AppResult<List<BookId>> = notYetImplemented()

    override suspend fun createGenre(
        parentId: GenreId?,
        name: String,
        sortOrder: Int,
    ): AppResult<GenreId> = notYetImplemented()

    override suspend fun updateGenre(
        id: GenreId,
        patch: GenreUpdate,
    ): AppResult<Unit> = notYetImplemented()

    override suspend fun deleteGenre(id: GenreId): AppResult<Unit> = notYetImplemented()

    override suspend fun moveGenre(
        id: GenreId,
        newParentId: GenreId?,
    ): AppResult<Unit> = notYetImplemented()

    override suspend fun mergeGenres(
        source: GenreId,
        target: GenreId,
    ): AppResult<Unit> = notYetImplemented()

    override suspend fun listUnmappedStrings(): AppResult<List<UnmappedStringSummary>> = notYetImplemented()

    override suspend fun mapUnmappedToGenre(
        rawString: String,
        genreId: GenreId,
    ): AppResult<Unit> = notYetImplemented()

    private fun <T> notYetImplemented(): AppResult<T> =
        AppResult.Failure(GenreError.NotFound(debugInfo = "not yet implemented"))
}
