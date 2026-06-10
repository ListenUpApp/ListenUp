package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.dto.GenreSummary
import com.calypsan.listenup.api.dto.GenreUpdate
import com.calypsan.listenup.api.dto.UnmappedStringSummary
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.GenreSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.GenreAliasTable
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.db.PendingBookGenreTable
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.GenreSlug
import com.calypsan.listenup.server.sync.BookSearchReindexer
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
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
 * Genre reads ([listGenres], [getGenre], [getGenreChildren], [browseBooks],
 * [listUnmappedStrings]) are open to any authenticated user. Genre-taxonomy mutations
 * ([createGenre], [updateGenre], [deleteGenre], [moveGenre], [mergeGenres],
 * [mapUnmappedToGenre]) are gated on the per-user `canEdit` flag via [permissionPolicy]:
 * ROOT/ADMIN pass implicitly, a MEMBER passes iff their flag is set (fresh DB lookup per
 * call). The authenticated caller is resolved from [principal] — route handlers call
 * [copyWith] to bind it per-request; the Koin singleton carries an unscoped placeholder
 * that yields no principal, so an absent principal on a mutation is a wiring bug and is
 * denied.
 */
@Suppress("UnusedPrivateProperty")
internal class GenreServiceImpl(
    private val genreRepository: GenreRepository,
    private val bookRepository: BookRepository,
    private val reindexer: BookSearchReindexer,
    private val db: Database,
    private val permissionPolicy: UserPermissionPolicy = UserPermissionPolicy(db),
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : GenreService {
    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): GenreServiceImpl =
        GenreServiceImpl(genreRepository, bookRepository, reindexer, db, permissionPolicy, principal)

    /**
     * Content-metadata edits are gated on the per-user `canEdit` flag. ROOT/ADMIN pass
     * implicitly; a MEMBER passes iff their flag is set (fresh DB lookup per call). An
     * absent principal — a wiring bug, since route handlers always [copyWith] the
     * authenticated caller — is denied. Returns null when permitted; the denial otherwise.
     */
    private suspend fun requireCanEdit(): AppError? {
        val p = principal.current() ?: return AuthError.PermissionDenied()
        return permissionPolicy.requireCanEdit(p.userId, p.role)
    }

    override suspend fun listGenres(): AppResult<List<GenreSummary>> =
        suspendTransaction(db) {
            val rows =
                GenreTable
                    .selectAll()
                    .where { GenreTable.deletedAt.isNull() }
                    .orderBy(GenreTable.path)
                    .toList()
            // One grouped count over LIVE books (a soft-deleted book keeps its book_genres
            // row — FK cascade is hard-delete only — so exclude tombstones here).
            val bookCountExpr = BookGenreTable.genreId.count()
            val counts: Map<String, Long> =
                (BookGenreTable innerJoin BookTable)
                    .select(BookGenreTable.genreId, bookCountExpr)
                    .where { BookTable.deletedAt.isNull() }
                    .groupBy(BookGenreTable.genreId)
                    .associate { it[BookGenreTable.genreId] to it[bookCountExpr] }
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
                        bookCount = (counts[genreIdStr] ?: 0L).toInt(),
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
        requireCanEdit()?.let { return AppResult.Failure(it) }
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
        requireCanEdit()?.let { return AppResult.Failure(it) }
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
        requireCanEdit()?.let { return AppResult.Failure(it) }
        val result: AppResult<Unit> =
            suspendTransaction(db) {
                val current = genreRepository.findById(id.value)
                if (current == null || current.deletedAt != null) {
                    return@suspendTransaction AppResult.Failure(genreNotFound(id))
                }
                if (GenreTable.directChildren(id.value).isNotEmpty()) {
                    return@suspendTransaction AppResult.Failure(
                        GenreError.HasDescendants(debugInfo = id.value),
                    )
                }

                // Snapshot affected books BEFORE the cascade — afterwards the junction rows are gone.
                val affectedBookIds = BookGenreTable.bookIdsForGenre(id.value)

                BookGenreTable.deleteAllForGenre(id.value)
                GenreAliasTable.removeAllForGenre(id.value)

                // Re-upsert affected books — bumps revision, publishes book.Updated. The book's
                // BookSyncPayload.genres re-derives from the live junction (now missing this id).
                when (val reupsert = reupsertBooks(affectedBookIds)) {
                    is AppResult.Success -> Unit
                    is AppResult.Failure -> return@suspendTransaction AppResult.Failure(reupsert.error)
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
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        val outcome: MoveOutcome =
            suspendTransaction(db) {
                when (val plan = planMove(id, newParentId)) {
                    is MovePlanResult.Reject -> {
                        MoveOutcome(
                            oldPathPrefix = null,
                            result = AppResult.Failure(plan.error),
                        )
                    }

                    is MovePlanResult.NoOp -> {
                        MoveOutcome(oldPathPrefix = null, result = AppResult.Success(Unit))
                    }

                    is MovePlanResult.Proceed -> {
                        executeMove(plan.plan)
                    }
                }
            }
        if (outcome.result is AppResult.Success && outcome.oldPathPrefix != null) {
            try {
                reindexer.reindexAllBooksForSubtree(outcome.oldPathPrefix)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "FTS reindex failed after moveGenre id=${id.value}" }
            }
        }
        return outcome.result
    }

    override suspend fun mergeGenres(
        source: GenreId,
        target: GenreId,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        if (source.value == target.value) {
            return AppResult.Failure(GenreError.MergeSelfTarget(debugInfo = source.value))
        }
        val result: AppResult<Unit> =
            suspendTransaction(db) {
                val sourcePayload = genreRepository.findById(source.value)
                if (sourcePayload == null || sourcePayload.deletedAt != null) {
                    return@suspendTransaction AppResult.Failure(genreNotFound(source))
                }
                val targetPayload = genreRepository.findById(target.value)
                if (targetPayload == null || targetPayload.deletedAt != null) {
                    return@suspendTransaction AppResult.Failure(genreNotFound(target))
                }
                if (GenreTable.directChildren(source.value).isNotEmpty()) {
                    return@suspendTransaction AppResult.Failure(
                        GenreError.HasDescendants(debugInfo = source.value),
                    )
                }

                // Snapshot affected books BEFORE the relink — afterwards the source's junction rows are gone.
                val affectedBookIds = BookGenreTable.bookIdsForGenre(source.value)

                // INSERT-OR-IGNORE the (book, target) rows for every book linked to source,
                // then drop the source rows. Books already linked to both sides end up with a single
                // (book, target) row — no duplicates.
                BookGenreTable.relinkGenre(fromId = source.value, toId = target.value)
                GenreAliasTable.repointAliases(fromGenreId = source.value, toGenreId = target.value)

                when (val reupsert = reupsertBooks(affectedBookIds)) {
                    is AppResult.Success -> Unit
                    is AppResult.Failure -> return@suspendTransaction AppResult.Failure(reupsert.error)
                }

                when (val softDeleteResult = genreRepository.softDelete(source)) {
                    is AppResult.Success -> AppResult.Success(Unit)
                    is AppResult.Failure -> AppResult.Failure(softDeleteResult.error)
                }
            }
        if (result is AppResult.Success) {
            try {
                reindexer.reindexAllBooksForGenre(target.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "FTS reindex failed after merge of ${source.value} into ${target.value}" }
            }
        }
        return result
    }

    override suspend fun listUnmappedStrings(): AppResult<List<UnmappedStringSummary>> =
        suspendTransaction(db) {
            val summaries =
                PendingBookGenreTable.aggregateByString().map { agg ->
                    UnmappedStringSummary(
                        rawString = agg.rawString,
                        bookCount = agg.bookCount,
                        firstSeenAt = agg.firstSeenAt,
                    )
                }
            AppResult.Success(summaries)
        }

    override suspend fun mapUnmappedToGenre(
        rawString: String,
        genreId: GenreId,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        val trimmed = rawString.trim()
        val result: AppResult<Unit> =
            suspendTransaction(db) {
                val genre = genreRepository.findById(genreId.value)
                if (genre == null || genre.deletedAt != null) {
                    return@suspendTransaction AppResult.Failure(genreNotFound(genreId))
                }

                // Snapshot affected books BEFORE we delete pending rows.
                val affectedBookIds = PendingBookGenreTable.bookIdsByRawString(trimmed)
                if (affectedBookIds.isEmpty()) {
                    return@suspendTransaction AppResult.Failure(
                        GenreError.UnmappedStringNotFound(debugInfo = "rawString=$trimmed"),
                    )
                }

                // 1. Persist the alias so future scans resolve the string automatically.
                GenreAliasTable.addAlias(trimmed, genreId.value)

                // 2. Convert pending → real junction rows (insert-or-ignore — idempotent if a
                //    book already has the target genre linked).
                for (bookId in affectedBookIds) {
                    BookGenreTable.insertIfAbsent(bookId, genreId.value)
                }

                // 3. Drop the pending rows now that the mapping is canonical.
                PendingBookGenreTable.deleteAllForRawString(trimmed)

                // 4. Re-upsert each affected book — bumps revision, publishes `book.Updated`.
                reupsertBooks(affectedBookIds)
            }
        if (result is AppResult.Success) {
            try {
                reindexer.reindexAllBooksForGenre(genreId.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "FTS reindex failed after mapUnmappedToGenre genreId=${genreId.value}" }
            }
        }
        return result
    }

    private fun genreNotFound(id: GenreId): GenreError.NotFound = GenreError.NotFound(debugInfo = "id=${id.value}")

    /**
     * Re-upserts every live book whose id appears in [bookIds]. Skips books that have
     * vanished between snapshot and re-read. Used by [deleteGenre] and [mergeGenres]
     * to bump revisions on books whose `BookSyncPayload.genres` must reflect the
     * junction cascade. Must be called inside the caller's `suspendTransaction { }`.
     */
    private suspend fun reupsertBooks(bookIds: List<String>): AppResult<Unit> {
        for (bookId in bookIds) {
            val payload = bookRepository.findById(BookId(bookId)) ?: continue
            val upsertResult = bookRepository.upsert(payload)
            if (upsertResult is AppResult.Failure) return AppResult.Failure(upsertResult.error)
        }
        return AppResult.Success(Unit)
    }

    /**
     * Returns `true` when [newParent] would create a cycle on `moveGenre(id, newParent)`:
     * either [newParent] is [id] itself, or it sits anywhere inside [genre]'s subtree.
     * The trailing-slash form of the path-prefix check prevents the `/fic` vs `/fiction`
     * LIKE-collision (acceptance criterion #5).
     */
    private fun isSelfOrDescendant(
        genre: GenreSyncPayload,
        newParent: GenreSyncPayload,
        id: GenreId,
    ): Boolean =
        newParent.id == id.value ||
            newParent.path == genre.path ||
            newParent.path.startsWith(genre.path + "/")

    /**
     * Validates a `moveGenre` request and either rejects it, reports a no-op, or
     * produces an executable [MovePlan]. Runs inside the caller's transaction.
     *
     * Splitting this out of [moveGenre] keeps the orchestration body straight-line
     * and well under the cognitive-complexity threshold.
     */
    private suspend fun planMove(
        id: GenreId,
        newParentId: GenreId?,
    ): MovePlanResult {
        val genre = genreRepository.findById(id.value)
        if (genre == null || genre.deletedAt != null) {
            return MovePlanResult.Reject(genreNotFound(id))
        }
        val newParent: GenreSyncPayload? =
            newParentId?.let { nid ->
                val p = genreRepository.findById(nid.value)
                if (p == null || p.deletedAt != null) return MovePlanResult.Reject(genreNotFound(nid))
                p
            }
        if (newParent != null && isSelfOrDescendant(genre, newParent, id)) {
            return MovePlanResult.Reject(GenreError.MoveSelfDescendant(debugInfo = id.value))
        }

        val oldPathPrefix = genre.path
        val newPathPrefix = (newParent?.path ?: "") + "/" + genre.slug
        if (newPathPrefix == oldPathPrefix) return MovePlanResult.NoOp

        if (GenreTable.findByPath(newPathPrefix) != null) {
            return MovePlanResult.Reject(GenreError.SlugConflict(debugInfo = "path=$newPathPrefix"))
        }

        val depthDelta = (newParent?.depth ?: -1) + 1 - genre.depth
        return MovePlanResult.Proceed(
            MovePlan(
                movedId = id,
                newParentId = newParentId,
                oldPathPrefix = oldPathPrefix,
                newPathPrefix = newPathPrefix,
                depthDelta = depthDelta,
            ),
        )
    }

    /**
     * Executes a validated [MovePlan]: snapshots the subtree, rewrites paths +
     * depths in bulk, re-points the moved root's parent, then re-upserts each
     * touched genre so the substrate publishes one `genre.Updated` per row.
     * Runs inside the caller's transaction.
     */
    private suspend fun executeMove(plan: MovePlan): MoveOutcome {
        val subtreeIds = GenreTable.descendantIds(plan.oldPathPrefix)
        GenreTable.rewritePathPrefix(plan.oldPathPrefix, plan.newPathPrefix, plan.depthDelta)
        GenreTable.updateParentId(plan.movedId.value, plan.newParentId?.value)
        for (gid in subtreeIds) {
            val payload = genreRepository.findById(gid) ?: continue
            val upsertResult = genreRepository.upsert(payload)
            if (upsertResult is AppResult.Failure) {
                return MoveOutcome(oldPathPrefix = null, result = AppResult.Failure(upsertResult.error))
            }
        }
        return MoveOutcome(oldPathPrefix = plan.oldPathPrefix, result = AppResult.Success(Unit))
    }

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
 * changed (mirrors `ContributorServiceImpl.UpdateOutcome`).
 */
private data class GenreUpdateOutcome(
    val nameChanged: Boolean,
    val result: AppResult<Unit>,
)

/**
 * Carries the outcome of a move + the old subtree's path prefix so the post-commit
 * `reindexAllBooksForSubtree` can fire against the books whose FTS rows need to
 * reflect the new genre paths. `oldPathPrefix` is null on failure or when the move
 * was a no-op.
 */
private data class MoveOutcome(
    val oldPathPrefix: String?,
    val result: AppResult<Unit>,
)

/** Validated parameters needed to execute a `moveGenre` write. */
private data class MovePlan(
    val movedId: GenreId,
    val newParentId: GenreId?,
    val oldPathPrefix: String,
    val newPathPrefix: String,
    val depthDelta: Int,
)

/**
 * The three outcomes of `moveGenre` validation: a typed rejection, a no-op
 * (input is valid but the move wouldn't change anything), or a validated plan
 * ready to execute against the live schema.
 */
private sealed interface MovePlanResult {
    data class Reject(
        val error: GenreError,
    ) : MovePlanResult

    data object NoOp : MovePlanResult

    data class Proceed(
        val plan: MovePlan,
    ) : MovePlanResult
}

/**
 * Public factory for tests that need a `GenreService` without going through
 * Koin. Mirrors [createBookService] / [createSeriesService] / [createContributorService] —
 * the harness in `WithClientSyncEngineAgainstServer` wires every server domain
 * service this way.
 */
fun createGenreService(
    genreRepository: GenreRepository,
    bookRepository: BookRepository,
    reindexer: BookSearchReindexer,
    db: Database,
): GenreService = GenreServiceImpl(genreRepository, bookRepository, reindexer, db)

/**
 * Scopes a [GenreService] built by [createGenreService] to [principal] for one request.
 * Public so cross-module test harnesses can bind the authenticated caller without piercing
 * the `internal` access on [GenreServiceImpl.copyWith]. Production wiring calls
 * [GenreServiceImpl.copyWith] directly in the RPC route.
 */
fun genreServiceScopedTo(
    service: GenreService,
    principal: PrincipalProvider,
): GenreService = (service as GenreServiceImpl).copyWith(principal)

private fun GenreSyncPayload.applyPatch(patch: GenreUpdate): GenreSyncPayload =
    copy(
        name = patch.name ?: name,
        description = patch.description ?: description,
        color = patch.color ?: color,
        sortOrder = patch.sortOrder ?: sortOrder,
    )
