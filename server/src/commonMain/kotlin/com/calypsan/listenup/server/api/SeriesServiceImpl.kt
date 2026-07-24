package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction as sqlTransaction
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.util.runCatchingCancellable
import com.calypsan.listenup.server.logging.loggerFor

private val logger = loggerFor<SeriesServiceImpl>()

/**
 * Thin [SeriesService] implementation.
 *
 * Translates read requests and user-edit mutations for series entities from
 * the wire contract to repository calls.
 *
 * [updateSeries] reads the current row, applies the patch field-by-field
 * (`null` means "don't touch"), and writes it back through the syncable substrate
 * so the revision bumps and the change-bus fires uniformly. The read-then-write
 * runs inside one [suspendTransaction] so concurrent edits can't interleave.
 * When `name` or `sortName` changes, the post-commit pass kicks the FTS reindex
 * for every linked book — reindex failure is logged and swallowed so a flaky FTS
 * write can't roll back a successful DB update.
 *
 * [deleteSeries] hard-deletes the junction rows linking the series to
 * every book (the canonical mass-removal idiom for many-to-many catalogues), then
 * re-upserts each affected book with the series stripped — which bumps each
 * book's revision and publishes the resulting `Updated` events so clients see
 * the series disappear from book detail views — and finally soft-deletes
 * the series itself. Post-commit, every affected book has its `book_search` FTS
 * row reindexed (best-effort; logged on failure).
 *
 * **Transaction model (SQLDelight cutover).** Like [com.calypsan.listenup.server.api.ContributorServiceImpl],
 * each flow runs its read-consistency checks under an outer Exposed read transaction, but every
 * WRITE — the `book_series_memberships` relink/delete and the series/book upserts — goes through
 * the single SQLDelight connection. The outer Exposed transaction never takes the SQLite write
 * lock, so the SQLDelight writes serialize on the lone SQLDelight connection without the
 * cross-engine `SQLITE_BUSY` the prior Exposed-junction-write split exhibited.
 *
 * [getSeries] (series metadata) is open to any authenticated user, but [listBooksBySeries]
 * is access-filtered: a non-admin caller receives only the sibling books they can reach
 * (via [BookAccessPolicy]), so a quarantined or private-collection-only book in the series
 * never leaks its metadata; ROOT/ADMIN see every book.
 * Series-metadata mutations ([updateSeries], [deleteSeries], [mergeSeries]) are gated on
 * the per-user `canEdit` flag via [permissionPolicy]: ROOT/ADMIN pass implicitly, a MEMBER
 * passes iff their flag is set (fresh DB lookup per call). The authenticated caller is
 * resolved from [principal] — route handlers call [copyWith] to bind it per-request; the
 * Koin singleton carries an unscoped placeholder that yields no principal, so an absent
 * principal on a mutation is a wiring bug and is denied.
 */
internal class SeriesServiceImpl(
    private val seriesRepo: SeriesRepository,
    private val bookRepo: BookRepository,
    private val reindexer: BookSearchReindexer,
    private val sqlDb: ListenUpDatabase,
    private val accessPolicy: BookAccessPolicy,
    private val permissionPolicy: UserPermissionPolicy = UserPermissionPolicy(sqlDb),
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : SeriesService {
    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): SeriesServiceImpl =
        SeriesServiceImpl(seriesRepo, bookRepo, reindexer, sqlDb, accessPolicy, permissionPolicy, principal)

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

    override suspend fun getSeries(id: SeriesId): AppResult<SeriesSyncPayload?> =
        AppResult.Success(seriesRepo.findById(id.value))

    override suspend fun listBooksBySeries(id: SeriesId): AppResult<List<BookSyncPayload>> {
        val accessible = accessibleBookIdFilter()
        return AppResult.Success(
            bookRepo.findBySeries(id).filter { accessible == null || it.id in accessible },
        )
    }

    /**
     * The caller's reachable book-id set, or null when the caller is ROOT/ADMIN (unfiltered —
     * every book). Resolved from [principal] (bound per-request via [copyWith]) through the same
     * [BookAccessPolicy] seam [BookServiceImpl] uses, so a series listing can never leak a sibling
     * book the caller can't reach. An absent principal — a wiring bug, since every RPC/REST caller
     * is scoped — collapses to the empty set (deny all) rather than leaking.
     */
    private suspend fun accessibleBookIdFilter(): Set<String>? {
        val p = principal.current() ?: return emptySet()
        return accessPolicy.accessibleBookIds(p.userId.value, p.role)
    }

    override suspend fun updateSeries(
        id: SeriesId,
        patch: SeriesUpdate,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        val current =
            seriesRepo.findById(id.value)
                ?: return seriesNotFound(id)
        val patched = current.applyPatch(patch)
        val nameChanged = patched.name != current.name || patched.sortName != current.sortName
        val outcome: SeriesUpdateOutcome =
            when (val upsertResult = seriesRepo.upsert(patched)) {
                is AppResult.Success -> SeriesUpdateOutcome(nameChanged, AppResult.Success(Unit))
                is AppResult.Failure -> SeriesUpdateOutcome(false, AppResult.Failure(upsertResult.error))
            }
        if (outcome.result is AppResult.Success && outcome.reindexNeeded) {
            runCatchingCancellable { reindexer.reindexAllBooksForSeries(id.value) }
                .onFailure { logger.warn(it) { "FTS reindex failed for series ${id.value}" } }
        }
        return outcome.result
    }

    override suspend fun mergeSeries(
        source: SeriesId,
        target: SeriesId,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        if (source.value == target.value) {
            return AppResult.Failure(SeriesError.MergeSelfTarget())
        }
        val result = mergeCore(source, target)
        if (result is AppResult.Success) {
            runCatchingCancellable { reindexer.reindexAllBooksForSeries(target.value) }
                .onFailure {
                    logger.warn(it) { "FTS reindex failed after series merge ${source.value} -> ${target.value}" }
                }
        }
        return result
    }

    /**
     * The merge write sequence (no FTS reindex). The snapshot + membership relink are the one
     * pair kept in a single SQLDelight [sqlTransaction]; the per-book re-upserts and the source
     * soft-delete are sequential aggregate writes over the single SQLDelight connection —
     * matching the established cutover shape.
     */
    private suspend fun mergeCore(
        source: SeriesId,
        target: SeriesId,
    ): AppResult<Unit> {
        val sourcePayload =
            seriesRepo.findById(source.value)
                ?: return AppResult.Failure(SeriesError.NotFound(debugInfo = "source=${source.value}"))
        seriesRepo.findById(target.value)
            ?: return AppResult.Failure(SeriesError.NotFound(debugInfo = "target=${target.value}"))
        if (sourcePayload.deletedAt != null) {
            return AppResult.Failure(
                SeriesError.NotFound(debugInfo = "source=${source.value} already tombstoned"),
            )
        }

        // Snapshot affected book IDs, then re-link all membership rows source → target — both
        // over the single SQLDelight connection in one mini-transaction.
        val affectedBookIds =
            sqlTransaction(sqlDb) {
                val ids = sqlDb.bookSeriesMembershipsQueries.bookIdsForSeries(source.value).executeAsList()
                sqlDb.bookSeriesMembershipsQueries.relinkSeries(to_id = target.value, from_id = source.value)
                ids
            }

        // Re-upsert each affected book — bumps revision + emits book.Updated per book.
        // One batched read replaces the per-book N+1 lookup.
        for (book in bookRepo.findAllByIds(affectedBookIds)) {
            when (val upsertResult = bookRepo.upsert(book)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> return AppResult.Failure(upsertResult.error)
            }
        }

        // Soft-delete source — emits series.Deleted(source).
        return when (val softDeleteResult = seriesRepo.softDelete(source)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> AppResult.Failure(softDeleteResult.error)
        }
    }

    override suspend fun deleteSeries(id: SeriesId): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        val result = deleteCore(id)
        if (result is AppResult.Success) {
            runCatchingCancellable { reindexer.reindexAllBooksForSeries(id.value) }
                .onFailure { logger.warn(it) { "FTS reindex failed during delete of series ${id.value}" } }
        }
        return result
    }

    /**
     * The delete write sequence (no FTS reindex). The snapshot + membership hard-delete are the
     * one pair kept in a single SQLDelight [sqlTransaction]; the per-book re-upserts and the
     * series soft-delete are sequential aggregate writes over the single SQLDelight connection —
     * matching the established cutover shape.
     */
    private suspend fun deleteCore(id: SeriesId): AppResult<Unit> {
        seriesRepo.findById(id.value)
            ?: return seriesNotFound(id)
        // Snapshot affected book IDs, then hard-delete every membership row for the series —
        // both over the single SQLDelight connection in one mini-transaction.
        val affectedBookIds =
            sqlTransaction(sqlDb) {
                val ids = sqlDb.bookSeriesMembershipsQueries.bookIdsForSeries(id.value).executeAsList()
                sqlDb.bookSeriesMembershipsQueries.deleteAllForSeries(id.value)
                ids
            }
        for (payload in bookRepo.findAllByIds(affectedBookIds)) {
            val stripped = payload.copy(series = payload.series.filter { it.id != id.value })
            when (val upsertResult = bookRepo.upsert(stripped)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> return AppResult.Failure(upsertResult.error)
            }
        }
        return when (val softDeleteResult = seriesRepo.softDelete(id)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> AppResult.Failure(softDeleteResult.error)
        }
    }
}

/**
 * Constructs a [SeriesService] backed by [SeriesServiceImpl]. Public so cross-module
 * test harnesses (e.g. `:app:sharedLogic:jvmTest`'s `WithClientSyncEngineAgainstServer`)
 * can build the service without depending on the Koin graph or piercing the
 * `internal` access on [SeriesServiceImpl]. Production wiring continues to construct
 * the impl directly inside the books Koin module.
 */
fun createSeriesService(
    seriesRepo: SeriesRepository,
    bookRepo: BookRepository,
    reindexer: BookSearchReindexer,
    sqlDb: ListenUpDatabase,
    driver: app.cash.sqldelight.db.SqlDriver,
): SeriesService = SeriesServiceImpl(seriesRepo, bookRepo, reindexer, sqlDb, BookAccessPolicy(sqlDb, driver))

/**
 * Scopes a [SeriesService] built by [createSeriesService] to [principal] for one request.
 * Public so cross-module test harnesses can bind the authenticated caller without piercing
 * the `internal` access on [SeriesServiceImpl.copyWith]. Production wiring calls
 * [SeriesServiceImpl.copyWith] directly in the RPC route.
 */
fun seriesServiceScopedTo(
    service: SeriesService,
    principal: PrincipalProvider,
): SeriesService = (service as SeriesServiceImpl).copyWith(principal)

private data class SeriesUpdateOutcome(
    val reindexNeeded: Boolean,
    val result: AppResult<Unit>,
)

private fun seriesNotFound(id: SeriesId): AppResult.Failure =
    AppResult.Failure(SeriesError.NotFound(debugInfo = "seriesId=${id.value}"))

private fun SeriesSyncPayload.applyPatch(patch: SeriesUpdate): SeriesSyncPayload =
    copy(
        name = patch.name ?: name,
        sortName = patch.sortName ?: sortName,
        description = patch.description ?: description,
        coverPath = patch.coverPath ?: coverPath,
        asin = patch.asin ?: asin,
    )
