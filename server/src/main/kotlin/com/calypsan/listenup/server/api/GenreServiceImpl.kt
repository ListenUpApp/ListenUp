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
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.GenreSlug
import com.calypsan.listenup.server.sync.BookSearchReindexer
import java.util.UUID
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

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
 * Task 13 has landed the read + create surface ([listGenres], [getGenre],
 * [getGenreChildren], [createGenre]); the remaining methods still stub
 * [GenreError.NotFound] and land in Tasks 14–19.
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
    override suspend fun listGenres(): AppResult<List<GenreSummary>> =
        suspendTransaction(db) {
            val rows =
                GenreTable
                    .selectAll()
                    .where { GenreTable.deletedAt.isNull() }
                    .orderBy(GenreTable.path)
                    .toList()
            val summaries =
                rows.map { row ->
                    val genreIdStr = row[GenreTable.id]
                    GenreSummary(
                        id = GenreId(genreIdStr),
                        name = row[GenreTable.name],
                        slug = row[GenreTable.slug],
                        path = row[GenreTable.path],
                        parentId = row[GenreTable.parentId]?.let(::GenreId),
                        depth = row[GenreTable.depth],
                        sortOrder = row[GenreTable.sortOrder],
                        bookCount =
                            BookGenreTable
                                .selectAll()
                                .where { BookGenreTable.genreId eq genreIdStr }
                                .count()
                                .toInt(),
                    )
                }
            AppResult.Success(summaries)
        }

    override suspend fun getGenre(id: GenreId): AppResult<GenreSyncPayload?> {
        val payload = genreRepository.findById(id.value)
        return AppResult.Success(payload?.takeIf { it.deletedAt == null })
    }

    override suspend fun getGenreChildren(parentId: GenreId): AppResult<List<GenreSyncPayload>> =
        suspendTransaction(db) {
            // Parent existence + liveness check inside the same transaction as the children read.
            val parentRow =
                GenreTable
                    .selectAll()
                    .where { GenreTable.id eq parentId.value }
                    .firstOrNull()
            if (parentRow == null || parentRow[GenreTable.deletedAt] != null) {
                return@suspendTransaction AppResult.Failure(
                    GenreError.NotFound(debugInfo = "parentId=${parentId.value}"),
                )
            }
            val children =
                GenreTable
                    .selectAll()
                    .where { (GenreTable.parentId eq parentId.value) and GenreTable.deletedAt.isNull() }
                    .orderBy(GenreTable.sortOrder)
                    .orderBy(GenreTable.path)
                    .toList()
            AppResult.Success(children.map { it.toGenrePayload() })
        }

    override suspend fun browseBooks(
        genreId: GenreId,
        includeDescendants: Boolean,
        limit: Int,
    ): AppResult<List<BookId>> = notYetImplemented()

    override suspend fun createGenre(
        parentId: GenreId?,
        name: String,
        sortOrder: Int,
    ): AppResult<GenreId> {
        // 1. Slug normalization owns blank/empty-after-normalize validation.
        val slug =
            when (val slugResult = GenreSlug.normalize(name)) {
                is AppResult.Success -> slugResult.data
                is AppResult.Failure -> return AppResult.Failure(slugResult.error)
            }

        // 2. Lookup parent (if provided); reject when missing or tombstoned.
        val parent: GenreSyncPayload? =
            parentId?.let { pid ->
                val p = genreRepository.findById(pid.value)
                if (p == null || p.deletedAt != null) {
                    return AppResult.Failure(GenreError.NotFound(debugInfo = "parentId=${pid.value}"))
                }
                p
            }

        // 3. Slug uniqueness — friendly-error path. The partial unique index on `slug` where
        //    `deleted_at IS NULL` is the race-condition backstop.
        if (genreRepository.findBySlug(slug) != null) {
            return AppResult.Failure(GenreError.SlugConflict(debugInfo = "slug=$slug"))
        }

        // 4. Compute materialized path + depth from the parent.
        val parentPath = parent?.path.orEmpty()
        val newPath = "$parentPath/$slug"
        val newDepth = (parent?.depth ?: -1) + 1

        // 5. Persist via the substrate (revision bump + SSE event are owned by SyncableRepository).
        val newId = UUID.randomUUID().toString()
        val payload =
            GenreSyncPayload(
                id = newId,
                name = name.trim(),
                slug = slug,
                path = newPath,
                parentId = parentId?.value,
                depth = newDepth,
                sortOrder = sortOrder,
            )
        return when (val result = genreRepository.upsert(payload)) {
            is AppResult.Success -> AppResult.Success(GenreId(newId))
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
    }

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

    private fun ResultRow.toGenrePayload(): GenreSyncPayload =
        GenreSyncPayload(
            id = this[GenreTable.id],
            name = this[GenreTable.name],
            slug = this[GenreTable.slug],
            path = this[GenreTable.path],
            parentId = this[GenreTable.parentId],
            depth = this[GenreTable.depth],
            sortOrder = this[GenreTable.sortOrder],
            color = this[GenreTable.color],
            description = this[GenreTable.description],
            revision = this[GenreTable.revision],
            updatedAt = this[GenreTable.updatedAt],
            createdAt = this[GenreTable.createdAt],
            deletedAt = this[GenreTable.deletedAt],
        )
}
