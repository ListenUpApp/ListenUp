package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.dto.GenreSummary
import com.calypsan.listenup.api.dto.GenreUpdate
import com.calypsan.listenup.api.dto.UnmappedStringSummary
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrElse
import com.calypsan.listenup.api.sync.GenreSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.sqldelight.Genres
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.GenreSlug
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.util.runCatchingCancellable
import com.calypsan.listenup.server.logging.loggerFor
import kotlin.uuid.Uuid

private val logger = loggerFor<GenreServiceImpl>()

private const val MIN_BROWSE_LIMIT = 1
private const val MAX_BROWSE_LIMIT = 1000

/**
 * [GenreService] implementation. Genres are a curator-controlled hierarchical
 * taxonomy with materialized-path storage; this class implements the read,
 * admin, and unmapped-curation surfaces against [GenreRepository] +
 * [BookRepository] + [BookSearchReindexer], all over SQLDelight ([sqlDb]).
 *
 * Genre reads + writes go through [genreRepository] (the SQLDelight syncable genre
 * substrate) and the generated junction/alias/pending queries on [sqlDb]. The genre
 * substrate is single-engine with [BookRepository] now, so the cross-aggregate flows
 * ([deleteGenre], [mergeGenres], [mapUnmappedToGenre]) keep their **sequential** shape:
 * the synchronous junction/alias writes commit first (inside a short
 * [suspendTransaction]), the genre soft-delete runs through the substrate (its own
 * transaction, bumping revision + publishing), and the affected books are re-upserted
 * through [BookRepository] afterwards. Sequential SQLDelight writes never contend for
 * the single SQLite write lock, so there is no `SQLITE_BUSY`; the structure is retained
 * because the substrate `upsert` / `softDelete` are suspend functions that open their own
 * transaction and so cannot nest inside the non-suspend SQLDelight transaction body.
 *
 * The materialized-path move/merge/subtree-delete orchestration is preserved exactly:
 * descendant snapshot, bulk `rewritePathPrefix`, parent re-point, then a per-affected-row
 * re-upsert so the substrate publishes one `genre.Updated` per row. Post-commit
 * `BookSearchReindexer.reindexAllBooksForGenre` / `reindexAllBooksForSubtree` keeps
 * `book_search.genres` consistent with the live junction state.
 *
 * Genre reads ([listGenres], [getGenre], [getGenreChildren], [listUnmappedStrings]) are
 * open to any authenticated user; [browseBooks] is access-filtered so a non-admin caller
 * receives only the ids of books they can reach (via [BookAccessPolicy]) — a browse can't
 * enumerate a quarantined or private-collection-only book; ROOT/ADMIN see every book.
 * Genre-taxonomy mutations
 * ([createGenre], [updateGenre], [deleteGenre], [moveGenre], [mergeGenres],
 * [mapUnmappedToGenre]) are gated on the per-user `canEdit` flag via [permissionPolicy]:
 * ROOT/ADMIN pass implicitly, a MEMBER passes iff their flag is set (fresh DB lookup per
 * call). The authenticated caller is resolved from [principal] — route handlers call
 * [copyWith] to bind it per-request; the Koin singleton carries an unscoped placeholder
 * that yields no principal, so an absent principal on a mutation is a wiring bug and is
 * denied.
 */
internal class GenreServiceImpl(
    private val genreRepository: GenreRepository,
    private val bookRepository: BookRepository,
    private val reindexer: BookSearchReindexer,
    private val sqlDb: ListenUpDatabase,
    private val accessPolicy: BookAccessPolicy,
    private val permissionPolicy: UserPermissionPolicy = UserPermissionPolicy(sqlDb),
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : GenreService {
    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): GenreServiceImpl =
        GenreServiceImpl(genreRepository, bookRepository, reindexer, sqlDb, accessPolicy, permissionPolicy, principal)

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
        suspendTransaction(sqlDb) {
            val rows = sqlDb.genresQueries.listLiveOrderedByPath().executeAsList()
            // One grouped count over LIVE books (a soft-deleted book keeps its book_genres
            // row — FK cascade is hard-delete only — so the query excludes tombstones).
            val counts: Map<String, Long> =
                sqlDb.bookGenresQueries
                    .bookCountsOverLiveBooks { genreId, bookCount -> genreId to bookCount }
                    .executeAsList()
                    .toMap()
            val summaries =
                rows.map { row ->
                    GenreSummary(
                        id = GenreId(row.id),
                        name = row.name,
                        slug = row.slug,
                        path = row.path,
                        parentId = row.parent_id?.let(::GenreId),
                        depth = row.depth.toInt(),
                        sortOrder = row.sort_order.toInt(),
                        bookCount = (counts[row.id] ?: 0L).toInt(),
                    )
                }
            AppResult.Success(summaries)
        }

    override suspend fun getGenre(id: GenreId): AppResult<GenreSyncPayload?> {
        val payload = genreRepository.findById(id.value)
        return AppResult.Success(payload?.takeIf { it.deletedAt == null })
    }

    override suspend fun getGenreChildren(parentId: GenreId): AppResult<List<GenreSyncPayload>> =
        suspendTransaction(sqlDb) {
            // Parent existence + liveness check inside the same transaction as the children read.
            val parentRow = sqlDb.genresQueries.selectById(parentId.value).executeAsOneOrNull()
            if (parentRow == null || parentRow.deleted_at != null) {
                return@suspendTransaction AppResult.Failure(
                    GenreError.NotFound(debugInfo = "parentId=${parentId.value}"),
                )
            }
            val children =
                sqlDb.genresQueries
                    .liveChildrenOrdered(parentId.value)
                    .executeAsList()
            AppResult.Success(children.map { it.toPayload() })
        }

    override suspend fun browseBooks(
        genreId: GenreId,
        includeDescendants: Boolean,
        limit: Int,
    ): AppResult<List<BookId>> {
        val safeLimit = limit.coerceIn(MIN_BROWSE_LIMIT, MAX_BROWSE_LIMIT)
        // Resolve the caller's reachable set BEFORE opening the browse transaction — the access
        // policy opens its own transaction, so it must not nest inside this one.
        val accessible = accessibleBookIdFilter()
        return suspendTransaction(sqlDb) {
            val genreRow = sqlDb.genresQueries.selectById(genreId.value).executeAsOneOrNull()
            if (genreRow == null || genreRow.deleted_at != null) {
                return@suspendTransaction AppResult.Failure(genreNotFound(genreId))
            }
            val bookIdStrings =
                if (includeDescendants) {
                    sqlDb.bookGenresQueries.booksForGenrePrefix(genreRow.path, safeLimit.toLong()).executeAsList()
                } else {
                    sqlDb.bookGenresQueries.booksForGenre(genreId.value, safeLimit.toLong()).executeAsList()
                }
            // Drop ids the caller can't reach so a browse can't enumerate the existence of a
            // quarantined or private-collection-only book. null = ROOT/ADMIN (unfiltered).
            AppResult.Success(bookIdStrings.filter { accessible == null || it in accessible }.map(::BookId))
        }
    }

    /**
     * The caller's reachable book-id set, or null when the caller is ROOT/ADMIN (unfiltered —
     * every book). Resolved from [principal] (bound per-request via [copyWith]) through the same
     * [BookAccessPolicy] seam [BookServiceImpl] uses, so [browseBooks] can never enumerate a book
     * the caller can't reach. An absent principal — a wiring bug, since every RPC/REST caller is
     * scoped — collapses to the empty set (deny all) rather than leaking.
     */
    private suspend fun accessibleBookIdFilter(): Set<String>? {
        val p = principal.current() ?: return emptySet()
        return accessPolicy.accessibleBookIds(p.userId.value, p.role)
    }

    override suspend fun createGenre(
        parentId: GenreId?,
        name: String,
        sortOrder: Int,
    ): AppResult<GenreId> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        // 1. Slug normalization owns blank/empty-after-normalize validation.
        val slug = GenreSlug.normalize(name).getOrElse { return AppResult.Failure(it) }

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
        val newId = Uuid.random().toString()
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
        val current = genreRepository.findById(id.value)
        if (current == null || current.deletedAt != null) {
            return AppResult.Failure(genreNotFound(id))
        }
        val patched = current.applyPatch(patch)
        val nameChanged = patched.name != current.name
        val result =
            when (val upsertResult = genreRepository.upsert(patched)) {
                is AppResult.Success -> AppResult.Success(Unit)
                is AppResult.Failure -> AppResult.Failure(upsertResult.error)
            }
        if (result is AppResult.Success && nameChanged) {
            runCatchingCancellable { reindexer.reindexAllBooksForGenre(id.value) }
                .onFailure { logger.warn(it) { "FTS reindex failed after rename of genre ${id.value}" } }
        }
        return result
    }

    override suspend fun deleteGenre(id: GenreId): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        // Sequential single-engine cutover: the synchronous junction-delete + alias-removal commit
        // first, then the genre soft-delete runs through the substrate (its own transaction, bumping
        // revision + publishing), then the affected books are re-upserted through BookRepository. All
        // SQLDelight, run sequentially so the single SQLite write lock is never contended.
        val current = genreRepository.findById(id.value)
        if (current == null || current.deletedAt != null) {
            return AppResult.Failure(genreNotFound(id))
        }
        if (genreRepository.directChildren(id.value).isNotEmpty()) {
            return AppResult.Failure(GenreError.HasDescendants(debugInfo = id.value))
        }

        // Snapshot affected books BEFORE the cascade, then drop the junction + alias rows.
        val affectedBookIds =
            suspendTransaction(sqlDb) {
                val ids = sqlDb.bookGenresQueries.bookIdsForGenre(id.value).executeAsList()
                sqlDb.bookGenresQueries.deleteAllForGenre(id.value)
                sqlDb.genreAliasesQueries.removeAllForGenre(id.value)
                ids
            }

        when (val softDeleteResult = genreRepository.softDelete(id)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return AppResult.Failure(softDeleteResult.error)
        }

        // Re-upsert affected books — bumps revision, publishes book.Updated. The book's
        // BookSyncPayload.genres re-derives from the live junction (now missing this id).
        when (val reupsert = reupsertBooks(affectedBookIds)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return AppResult.Failure(reupsert.error)
        }

        runCatchingCancellable { reindexer.reindexAllBooksForGenre(id.value) }
            .onFailure { logger.warn(it) { "FTS reindex failed during delete of genre ${id.value}" } }
        return AppResult.Success(Unit)
    }

    override suspend fun moveGenre(
        id: GenreId,
        newParentId: GenreId?,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        val plan =
            when (val planResult = planMove(id, newParentId)) {
                is MovePlanResult.Reject -> return AppResult.Failure(planResult.error)
                is MovePlanResult.NoOp -> return AppResult.Success(Unit)
                is MovePlanResult.Proceed -> planResult.plan
            }

        when (val moveResult = executeMove(plan)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return AppResult.Failure(moveResult.error)
        }

        runCatchingCancellable { reindexer.reindexAllBooksForSubtree(plan.oldPathPrefix) }
            .onFailure { logger.warn(it) { "FTS reindex failed after moveGenre id=${id.value}" } }
        return AppResult.Success(Unit)
    }

    override suspend fun mergeGenres(
        source: GenreId,
        target: GenreId,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        // Sequential single-engine cutover (see deleteGenre): synchronous relink + alias-repoint
        // commit first, the source genre soft-delete runs through the substrate, then the affected
        // books are re-upserted. All SQLDelight, run sequentially — no SQLITE_BUSY.
        validateMerge(source, target)?.let { return AppResult.Failure(it) }

        // Snapshot affected books BEFORE the relink — afterwards the source's junction rows are gone.
        // INSERT-OR-IGNORE the (book, target) rows for every book linked to source, then drop the
        // source rows (books already linked to both sides end up with one (book, target) row — no
        // duplicates), then re-point the aliases.
        val affectedBookIds =
            suspendTransaction(sqlDb) {
                val ids = sqlDb.bookGenresQueries.bookIdsForGenre(source.value).executeAsList()
                sqlDb.bookGenresQueries.relinkGenreCopy(to_id = target.value, from_id = source.value)
                sqlDb.bookGenresQueries.deleteAllForGenre(source.value)
                sqlDb.genreAliasesQueries.repointAliases(to_genre_id = target.value, from_genre_id = source.value)
                ids
            }

        when (val softDeleteResult = genreRepository.softDelete(source)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return AppResult.Failure(softDeleteResult.error)
        }

        when (val reupsert = reupsertBooks(affectedBookIds)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return AppResult.Failure(reupsert.error)
        }

        runCatchingCancellable { reindexer.reindexAllBooksForGenre(target.value) }
            .onFailure {
                logger.warn(it) { "FTS reindex failed after merge of ${source.value} into ${target.value}" }
            }
        return AppResult.Success(Unit)
    }

    override suspend fun listUnmappedStrings(): AppResult<List<UnmappedStringSummary>> =
        suspendTransaction(sqlDb) {
            val summaries =
                sqlDb.pendingBookGenresQueries.aggregateByString().executeAsList().map { agg ->
                    UnmappedStringSummary(
                        rawString = agg.raw_string,
                        bookCount = agg.book_count.toInt(),
                        firstSeenAt = agg.first_seen ?: 0L,
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
        // Sequential single-engine cutover (see deleteGenre): the alias-add, pending→junction
        // conversion, and pending-row delete commit first; the affected books are re-upserted
        // afterwards. All SQLDelight, run sequentially — no SQLITE_BUSY.
        val genre = genreRepository.findById(genreId.value)
        if (genre == null || genre.deletedAt != null) {
            return AppResult.Failure(genreNotFound(genreId))
        }

        val affectedBookIds = sqlDb.pendingBookGenresQueries.bookIdsByRawString(trimmed).executeAsList()
        if (affectedBookIds.isEmpty()) {
            return AppResult.Failure(GenreError.UnmappedStringNotFound(debugInfo = "rawString=$trimmed"))
        }

        suspendTransaction(sqlDb) {
            // 1. Persist the alias so future scans resolve the string automatically (delete-then-insert).
            sqlDb.genreAliasesQueries.deleteByRawString(trimmed)
            sqlDb.genreAliasesQueries.insert(raw_string = trimmed, genre_id = genreId.value)

            // 2. Convert pending → real junction rows (insert-or-ignore — idempotent if a book
            //    already has the target genre linked).
            for (bookId in affectedBookIds) {
                sqlDb.bookGenresQueries.insertIfAbsent(book_id = bookId, genre_id = genreId.value)
            }

            // 3. Drop the pending rows now that the mapping is canonical.
            sqlDb.pendingBookGenresQueries.deleteAllForRawString(trimmed)
        }

        // 4. Re-upsert each affected book — bumps revision, publishes `book.Updated`.
        when (val reupsert = reupsertBooks(affectedBookIds)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return AppResult.Failure(reupsert.error)
        }

        runCatchingCancellable { reindexer.reindexAllBooksForGenre(genreId.value) }
            .onFailure {
                logger.warn(it) { "FTS reindex failed after mapUnmappedToGenre genreId=${genreId.value}" }
            }
        return AppResult.Success(Unit)
    }

    private fun genreNotFound(id: GenreId): GenreError.NotFound = GenreError.NotFound(debugInfo = "id=${id.value}")

    /**
     * Validates a `mergeGenres(source, target)` request: rejects a self-merge, a missing/tombstoned
     * source or target, and a source that still has direct children (merging a non-leaf would orphan
     * its subtree). Returns the typed denial, or null when the merge may proceed.
     */
    private suspend fun validateMerge(
        source: GenreId,
        target: GenreId,
    ): GenreError? {
        if (source.value == target.value) {
            return GenreError.MergeSelfTarget(debugInfo = source.value)
        }
        val sourcePayload = genreRepository.findById(source.value)
        if (sourcePayload == null || sourcePayload.deletedAt != null) {
            return genreNotFound(source)
        }
        val targetPayload = genreRepository.findById(target.value)
        if (targetPayload == null || targetPayload.deletedAt != null) {
            return genreNotFound(target)
        }
        if (genreRepository.directChildren(source.value).isNotEmpty()) {
            return GenreError.HasDescendants(debugInfo = source.value)
        }
        return null
    }

    /**
     * Re-upserts every live book whose id appears in [bookIds]. Skips books that have
     * vanished between snapshot and re-read. Used by [deleteGenre], [mergeGenres], and
     * [mapUnmappedToGenre] to bump revisions on books whose `BookSyncPayload.genres` must
     * reflect the junction cascade. One batched [BookRepository.findAllByIds] read replaces
     * the prior per-book `findById` N+1.
     */
    private suspend fun reupsertBooks(bookIds: List<String>): AppResult<Unit> {
        for (payload in bookRepository.findAllByIds(bookIds)) {
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
     * produces an executable [MovePlan]. Reads through the SQLDelight genre substrate.
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

        if (genreRepository.findByPath(newPathPrefix) != null) {
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
     * Executes a validated [MovePlan]: snapshots the subtree and rewrites paths + depths in bulk
     * + re-points the moved root's parent (one synchronous SQLDelight transaction), then re-upserts
     * each touched genre so the substrate publishes one `genre.Updated` per row. The re-upserts run
     * sequentially after the path rewrite commits — each substrate `upsert` opens its own
     * transaction, so they cannot nest inside the synchronous rewrite transaction.
     */
    private suspend fun executeMove(plan: MovePlan): AppResult<Unit> {
        val subtreeIds =
            suspendTransaction(sqlDb) {
                val ids = sqlDb.genresQueries.descendantIds(plan.oldPathPrefix).executeAsList()
                sqlDb.genresQueries.rewritePathPrefix(
                    new_prefix = plan.newPathPrefix,
                    // substr_from = oldPrefix.length + 1; CAST to INTEGER at eval time (see Genres.sq).
                    substr_from = (plan.oldPathPrefix.length + 1).toString(),
                    depth_delta = plan.depthDelta.toLong(),
                    old_prefix = plan.oldPathPrefix,
                )
                sqlDb.genresQueries.updateParentId(parent_id = plan.newParentId?.value, id = plan.movedId.value)
                ids
            }
        for (gid in subtreeIds) {
            val payload = genreRepository.findById(gid) ?: continue
            val upsertResult = genreRepository.upsert(payload)
            if (upsertResult is AppResult.Failure) {
                return AppResult.Failure(upsertResult.error)
            }
        }
        return AppResult.Success(Unit)
    }

    /** Maps a generated [Genres] row to the [GenreSyncPayload] domain shape. */
    private fun Genres.toPayload(): GenreSyncPayload =
        GenreSyncPayload(
            id = id,
            name = name,
            slug = slug,
            path = path,
            parentId = parent_id,
            depth = depth.toInt(),
            sortOrder = sort_order.toInt(),
            color = color,
            description = description,
            revision = revision,
            updatedAt = updated_at,
            createdAt = created_at,
            deletedAt = deleted_at,
        )
}

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
    /** Validation failed: surface [error] to the caller. */
    data class Reject(
        val error: GenreError,
    ) : MovePlanResult

    /** Input is valid but the move is a no-op (the genre is already where it would land). */
    data object NoOp : MovePlanResult

    /** Validation passed: [plan] is ready to execute against the live schema. */
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
    sqlDb: ListenUpDatabase,
    driver: app.cash.sqldelight.db.SqlDriver,
): GenreService = GenreServiceImpl(genreRepository, bookRepository, reindexer, sqlDb, BookAccessPolicy(sqlDb, driver))

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
