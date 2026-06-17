package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.GenreUpdate
import com.calypsan.listenup.api.dto.UnmappedStringSummary
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.local.db.GenreEntity
import com.calypsan.listenup.client.data.local.db.GenreWithBookCount
import com.calypsan.listenup.client.data.remote.GenreRpcFactory
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.client.core.error.ErrorMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val logger = KotlinLogging.logger {}

/**
 * Genre repository — Room-backed reads, RPC-dispatched mutations.
 *
 * Tree reads (`observeAll`, `getById`, …) come from the local Room mirror,
 * which the sync engine populates via the substrate's SSE stream and
 * [com.calypsan.listenup.client.data.sync.handlers.GenreSyncDomainHandler].
 * `bookCount` on the returned [Genre] is computed at read time via JOIN on
 * `book_genres` — there is no denormalized column.
 *
 * Mutations call [com.calypsan.listenup.api.GenreService] over RPC. No
 * optimistic Room writes — the SSE echo from the server is the single write
 * path back into Room, mirroring the C2 contributor/series pattern.
 */
internal class GenreRepositoryImpl(
    private val dao: GenreDao,
    private val rpcFactory: GenreRpcFactory,
) : GenreRepository {
    // ── Observation ──────────────────────────────────────────────────────────

    override fun observeAll(): Flow<List<Genre>> =
        dao.observeAllGenresWithBookCount().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getAll(): List<Genre> = dao.getAllGenres().map { it.toDomain(bookCount = 0) }

    override suspend fun getById(id: String): Genre? = dao.getById(id)?.toDomain(bookCount = 0)

    override suspend fun getBySlug(slug: String): Genre? = dao.getBySlug(slug)?.toDomain(bookCount = 0)

    override fun observeGenresForBook(bookId: String): Flow<List<Genre>> =
        dao.observeGenresForBook(BookId(bookId)).map { entities ->
            entities.map { it.toDomain(bookCount = 0) }
        }

    override suspend fun getGenresForBook(bookId: String): List<Genre> =
        dao.getGenresForBook(BookId(bookId)).map { it.toDomain(bookCount = 0) }

    override suspend fun getBookIdsForGenre(genreId: String): List<String> =
        dao.getBookIdsForGenre(genreId).map { it.value }

    // ── Curator admin (RPC) ──────────────────────────────────────────────────

    override suspend fun createGenre(
        name: String,
        parentId: GenreId?,
        sortOrder: Int,
    ): AppResult<GenreId> = rpcCall { rpcFactory.genreService().createGenre(parentId, name, sortOrder) }

    override suspend fun updateGenre(
        id: GenreId,
        patch: GenreUpdate,
    ): AppResult<Unit> = rpcCallUnit { rpcFactory.genreService().updateGenre(id, patch) }

    override suspend fun deleteGenre(id: GenreId): AppResult<Unit> =
        rpcCallUnit { rpcFactory.genreService().deleteGenre(id) }

    override suspend fun moveGenre(
        id: GenreId,
        newParentId: GenreId?,
    ): AppResult<Unit> = rpcCallUnit { rpcFactory.genreService().moveGenre(id, newParentId) }

    override suspend fun mergeGenres(
        source: GenreId,
        target: GenreId,
    ): AppResult<Unit> = rpcCallUnit { rpcFactory.genreService().mergeGenres(source, target) }

    override suspend fun browseBooks(
        genreId: GenreId,
        includeDescendants: Boolean,
        limit: Int,
    ): AppResult<List<BookId>> = rpcCall { rpcFactory.genreService().browseBooks(genreId, includeDescendants, limit) }

    // ── Unmapped queue (RPC) ─────────────────────────────────────────────────

    override suspend fun listUnmappedStrings(): AppResult<List<UnmappedStringSummary>> =
        rpcCall { rpcFactory.genreService().listUnmappedStrings() }

    override suspend fun mapUnmappedToGenre(
        rawString: String,
        genreId: GenreId,
    ): AppResult<Unit> = rpcCallUnit { rpcFactory.genreService().mapUnmappedToGenre(rawString, genreId) }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /**
     * Run an RPC call that returns a value, converting [WireAppResult] →
     * [AppResult]. Re-throws [CancellationException]; all other throwables
     * become [AppResult.Failure] via [ErrorMapper].
     */
    private suspend fun <T> rpcCall(block: suspend () -> WireAppResult<T>): AppResult<T> =
        try {
            when (val result = block()) {
                is WireAppResult.Success -> AppResult.Success(result.data)
                is WireAppResult.Failure -> AppResult.Failure(result.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Genre RPC failed" }
            AppResult.Failure(ErrorMapper.map(e))
        }

    private suspend fun rpcCallUnit(block: suspend () -> WireAppResult<Unit>): AppResult<Unit> = rpcCall(block)
}

private fun GenreEntity.toDomain(bookCount: Int): Genre =
    Genre(
        id = id,
        name = name,
        slug = slug,
        path = path,
        bookCount = bookCount,
    )

private fun GenreWithBookCount.toDomain(): Genre =
    Genre(
        id = genre.id,
        name = genre.name,
        slug = genre.slug,
        path = genre.path,
        bookCount = bookCount,
    )
