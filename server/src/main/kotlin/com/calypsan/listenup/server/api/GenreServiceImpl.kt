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
import com.calypsan.listenup.server.db.GenreAliasTable
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.GenreSlug
import com.calypsan.listenup.server.sync.BookSearchReindexer
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private val logger = KotlinLogging.logger {}

private const val MIN_BROWSE_LIMIT = 1
private const val MAX_BROWSE_LIMIT = 1000

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
    ): AppResult<List<BookId>> {
        val safeLimit = limit.coerceIn(MIN_BROWSE_LIMIT, MAX_BROWSE_LIMIT)
        return suspendTransaction(db) {
            val genreRow =
                GenreTable
                    .selectAll()
                    .where { GenreTable.id eq genreId.value }
                    .firstOrNull()
            if (genreRow == null || genreRow[GenreTable.deletedAt] != null) {
                return@suspendTransaction AppResult.Failure(genreNotFound(genreId))
            }
            val bookIdStrings =
                if (includeDescendants) {
                    BookGenreTable.booksForGenrePrefix(genreRow[GenreTable.path], safeLimit)
                } else {
                    BookGenreTable.booksForGenre(genreId.value, safeLimit)
                }
            AppResult.Success(bookIdStrings.map(::BookId))
        }
    }

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
    ): AppResult<Unit> {
        val outcome: GenreUpdateOutcome =
            suspendTransaction(db) {
                val current = genreRepository.findById(id.value)
                if (current == null || current.deletedAt != null) {
                    return@suspendTransaction GenreUpdateOutcome(
                        nameChanged = false,
                        result = AppResult.Failure(genreNotFound(id)),
                    )
                }
                val patched = current.applyPatch(patch)
                val nameChanged = patched.name != current.name
                when (val upsertResult = genreRepository.upsert(patched)) {
                    is AppResult.Success -> GenreUpdateOutcome(nameChanged, AppResult.Success(Unit))
                    is AppResult.Failure -> GenreUpdateOutcome(false, AppResult.Failure(upsertResult.error))
                }
            }
        if (outcome.result is AppResult.Success && outcome.nameChanged) {
            try {
                reindexer.reindexAllBooksForGenre(id.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "FTS reindex failed after rename of genre ${id.value}" }
            }
        }
        return outcome.result
    }

    override suspend fun deleteGenre(id: GenreId): AppResult<Unit> {
        val result: AppResult<Unit> =
            suspendTransaction(db) {
                val current = genreRepository.findById(id.value)
                if (current == null || current.deletedAt != null) {
                    return@suspendTransaction AppResult.Failure(genreNotFound(id))
                }
                if (GenreTable.directChildren(id.value).isNotEmpty()) {
                    return@suspendTransaction AppResult.Failure(
                        GenreError.HasDescendants(debugInfo = "id=${id.value}"),
                    )
                }

                // Snapshot affected books BEFORE the cascade — afterwards the junction rows are gone.
                val affectedBookIds = BookGenreTable.bookIdsForGenre(id.value)

                BookGenreTable.deleteAllForGenre(id.value)
                GenreAliasTable.removeAllForGenre(id.value)

                // Re-upsert affected books — bumps revision, publishes book.Updated. The book's
                // BookSyncPayload.genres re-derives from the live junction (now missing this id).
                for (bookId in affectedBookIds) {
                    val payload = bookRepository.findById(BookId(bookId)) ?: continue
                    when (val upsertResult = bookRepository.upsert(payload)) {
                        is AppResult.Success -> Unit
                        is AppResult.Failure -> return@suspendTransaction AppResult.Failure(upsertResult.error)
                    }
                }

                when (val softDeleteResult = genreRepository.softDelete(id)) {
                    is AppResult.Success -> AppResult.Success(Unit)
                    is AppResult.Failure -> AppResult.Failure(softDeleteResult.error)
                }
            }
        if (result is AppResult.Success) {
            try {
                reindexer.reindexAllBooksForGenre(id.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "FTS reindex failed during delete of genre ${id.value}" }
            }
        }
        return result
    }

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

    private fun genreNotFound(id: GenreId): GenreError.NotFound = GenreError.NotFound(debugInfo = "id=${id.value}")

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

/**
 * Carries the patched payload alongside whether the rename triggered a name change,
 * so the post-commit FTS reindex only fires when the genre's display name actually
 * changed (mirrors `ContributorServiceImpl.GenreUpdateOutcome`).
 */
private data class GenreUpdateOutcome(
    val nameChanged: Boolean,
    val result: AppResult<Unit>,
)

private fun GenreSyncPayload.applyPatch(patch: GenreUpdate): GenreSyncPayload =
    copy(
        name = patch.name ?: name,
        description = patch.description ?: description,
        color = patch.color ?: color,
        sortOrder = patch.sortOrder ?: sortOrder,
    )
