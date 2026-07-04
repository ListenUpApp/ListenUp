package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ContributorError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction as sqlTransaction
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.CancellationException

private val logger = loggerFor<ContributorServiceImpl>()

/**
 * Thin [ContributorService] implementation.
 *
 * Translates read requests and user-edit mutations for contributor entities from
 * the wire contract to repository calls.
 *
 * [updateContributor] reads the current row, applies the patch field-by-field
 * (`null` means "don't touch"), and writes it back through the syncable substrate
 * so the revision bumps and the change-bus fires uniformly. The read-then-write
 * runs inside one [suspendTransaction] so concurrent edits can't interleave.
 * When `name` or `sortName` changes, the post-commit pass kicks the FTS reindex
 * for every linked book — reindex failure is logged and swallowed so a flaky FTS
 * write can't roll back a successful DB update.
 *
 * [deleteContributor] hard-deletes the junction rows linking the contributor to
 * every book (the canonical mass-removal idiom for many-to-many catalogues), then
 * re-upserts each affected book with the contributor stripped — which bumps each
 * book's revision and publishes the resulting `Updated` events so clients see
 * the contributor disappear from book detail views — and finally soft-deletes
 * the contributor itself. Post-commit, every affected book has its `book_search`
 * FTS row reindexed (best-effort; logged on failure).
 *
 * [mergeContributors] folds the [source] contributor into [target]: re-links
 * every `book_contributors` row from source to target while capturing the
 * source's display name into the previously-NULL `credited_as` column (books
 * that already had an explicit override keep it), re-upserts each affected book
 * to bump its revision and publish a `book.Updated` event, adds source's name
 * and aliases to target's alias set (case-insensitive dedup, target's own name
 * excluded), and finally soft-deletes the source. Post-commit, the target's
 * affected books reindex `book_search.contributor_names` and the target's
 * `contributor_search.aliases` is refreshed; reindex failures are logged and swallowed.
 *
 * [unmergeContributor] is the inverse of [mergeContributors]: splits a single
 * alias back into its own fresh contributor row. Resolves-or-creates a contributor
 * whose canonical name IS the alias (dedup-aware via [ContributorRepository.resolveOrCreate]
 * — a pre-existing live row for that normalized name is reused rather than blind-inserted,
 * which would violate the `normalized_name` unique index), re-links only the
 * `book_contributors` rows whose `credited_as` matches the alias and clears
 * that column (the new contributor's canonical name covers it now), re-upserts
 * each affected book to bump revision and publish `book.Updated`, then removes
 * the alias from the target. Returns the new contributor's id so callers can
 * navigate to it. Post-commit, both contributors' books reindex
 * `book_search.contributor_names` and the target's `contributor_search.aliases`
 * is refreshed; reindex failures are logged and swallowed.
 *
 * **Transaction model (SQLDelight cutover).** Each flow runs its read-consistency
 * checks under an outer Exposed read transaction, but every WRITE — the junction
 * relink/delete and the contributor/book upserts — goes through the single
 * SQLDelight connection (junction queries via [ContributorRepository] / the generated
 * `bookContributorsQueries`, aggregate writes via the syncable substrate). Keeping the
 * junction writes off the Exposed connection is what eliminates the cross-engine
 * `SQLITE_BUSY`: the outer Exposed transaction never takes the SQLite write lock, so
 * the SQLDelight writes on the lone SQLDelight connection serialize without contention.
 * Whole-flow atomicity is not guaranteed across the independent SQLDelight writes —
 * matching the established `BookServiceImpl` cutover shape — but each individual
 * aggregate write is atomic, and the dedup-aware unmerge creation removes the only
 * data-corrupting failure mode the prior split exhibited.
 *
 * [getContributor] (contributor metadata) is open to any authenticated user, but
 * [listBooksByContributor] is access-filtered: a non-admin caller receives only the books
 * they can reach (via [BookAccessPolicy]), so a quarantined or private-collection-only book
 * by the contributor never leaks its metadata; ROOT/ADMIN see every book.
 * Contributor-metadata mutations ([updateContributor],
 * [deleteContributor], [mergeContributors], [unmergeContributor]) are gated on the
 * per-user `canEdit` flag via [permissionPolicy]: ROOT/ADMIN pass implicitly, a MEMBER
 * passes iff their flag is set (fresh DB lookup per call). The authenticated caller is
 * resolved from [principal] — route handlers call [copyWith] to bind it per-request; the
 * Koin singleton carries an unscoped placeholder that yields no principal, so an absent
 * principal on a mutation is a wiring bug and is denied.
 */
internal class ContributorServiceImpl(
    private val contributorRepo: ContributorRepository,
    private val bookRepo: BookRepository,
    private val reindexer: BookSearchReindexer,
    private val sqlDb: ListenUpDatabase,
    private val accessPolicy: BookAccessPolicy,
    private val permissionPolicy: UserPermissionPolicy = UserPermissionPolicy(sqlDb),
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : ContributorService {
    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): ContributorServiceImpl =
        ContributorServiceImpl(contributorRepo, bookRepo, reindexer, sqlDb, accessPolicy, permissionPolicy, principal)

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

    override suspend fun getContributor(id: ContributorId): AppResult<ContributorSyncPayload?> =
        AppResult.Success(contributorRepo.findById(id.value))

    override suspend fun listBooksByContributor(id: ContributorId): AppResult<List<BookSyncPayload>> {
        val accessible = accessibleBookIdFilter()
        return AppResult.Success(
            bookRepo.findByContributor(id).filter { accessible == null || it.id in accessible },
        )
    }

    /**
     * The caller's reachable book-id set, or null when the caller is ROOT/ADMIN (unfiltered —
     * every book). Resolved from [principal] (bound per-request via [copyWith]) through the same
     * [BookAccessPolicy] seam [BookServiceImpl] uses, so a contributor listing can never leak a
     * book the caller can't reach. An absent principal — a wiring bug, since every RPC/REST caller
     * is scoped — collapses to the empty set (deny all) rather than leaking.
     */
    private suspend fun accessibleBookIdFilter(): Set<String>? {
        val p = principal.current() ?: return emptySet()
        return accessPolicy.accessibleBookIds(p.userId.value, p.role)
    }

    override suspend fun updateContributor(
        id: ContributorId,
        patch: ContributorUpdate,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        val current =
            contributorRepo.findById(id.value)
                ?: return contributorNotFound(id)
        val patched = current.applyPatch(patch)
        val nameChanged = patched.name != current.name || patched.sortName != current.sortName
        val outcome: UpdateOutcome =
            when (val upsertResult = contributorRepo.upsert(patched)) {
                is AppResult.Success -> UpdateOutcome(nameChanged, AppResult.Success(Unit))
                is AppResult.Failure -> UpdateOutcome(false, AppResult.Failure(upsertResult.error))
            }
        if (outcome.result is AppResult.Success && outcome.reindexNeeded) {
            try {
                reindexer.reindexAllBooksForContributor(id.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "FTS reindex failed for contributor ${id.value}" }
            }
        }
        return outcome.result
    }

    override suspend fun mergeContributors(
        source: ContributorId,
        target: ContributorId,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        if (source.value == target.value) {
            return AppResult.Failure(ContributorError.MergeSelfTarget())
        }
        val result = mergeCore(source, target)
        if (result is AppResult.Success) {
            try {
                reindexer.reindexAllBooksForContributor(target.value)
                reindexer.reindexContributorAliases(target.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "FTS reindex failed after merge of ${source.value} into ${target.value}" }
            }
        }
        return result
    }

    /**
     * The merge write sequence (no FTS reindex). Read-consistency checks and the aggregate
     * writes run sequentially over the single SQLDelight connection; the snapshot + junction
     * relink are the one pair that must commit together, kept in a single SQLDelight
     * [sqlTransaction]. Whole-flow atomicity is not guaranteed across the independent
     * aggregate writes — matching the established cutover shape.
     */
    private suspend fun mergeCore(
        source: ContributorId,
        target: ContributorId,
    ): AppResult<Unit> {
        val sourcePayload =
            contributorRepo.findById(source.value)
                ?: return AppResult.Failure(ContributorError.NotFound(debugInfo = "source=${source.value}"))
        val targetPayload =
            contributorRepo.findById(target.value)
                ?: return AppResult.Failure(ContributorError.NotFound(debugInfo = "target=${target.value}"))
        if (sourcePayload.deletedAt != null) {
            return AppResult.Failure(
                ContributorError.NotFound(debugInfo = "source=${source.value} already tombstoned"),
            )
        }

        // Snapshot the affected books, then re-link junction rows source → target — both over
        // the single SQLDelight connection, atomically in one mini-transaction. Capturing
        // source.name into credited_as where it was NULL; books with an explicit override keep
        // it (COALESCE).
        val affectedBookIds =
            sqlTransaction(sqlDb) {
                val ids = sqlDb.bookContributorsQueries.bookIdsForContributor(source.value).executeAsList()
                sqlDb.bookContributorsQueries.relinkContributorPreservingCredit(
                    source_name = sourcePayload.name,
                    to_id = target.value,
                    from_id = source.value,
                )
                ids
            }

        // Re-upsert every affected book — bumps revision, publishes book.Updated.
        // One batched read replaces the per-book N+1 lookup.
        for (payload in bookRepo.findAllByIds(affectedBookIds)) {
            when (val upsertResult = bookRepo.upsert(payload)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> return AppResult.Failure(upsertResult.error)
            }
        }

        // Build target's new alias set — source.name + source.aliases merged into
        // target.aliases, case-insensitive dedup, target's own name excluded.
        val mergedAliases =
            mergeAliasesFor(
                targetAliases = targetPayload.aliases,
                sourceName = sourcePayload.name,
                sourceAliases = sourcePayload.aliases,
                targetName = targetPayload.name,
            )

        // Re-upsert target with the new aliases — publishes contributor.Updated(target).
        when (val upsertResult = contributorRepo.upsert(targetPayload.copy(aliases = mergedAliases))) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return AppResult.Failure(upsertResult.error)
        }

        // Soft-delete source — publishes contributor.Deleted(source).
        return when (val softDeleteResult = contributorRepo.softDelete(source)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> AppResult.Failure(softDeleteResult.error)
        }
    }

    override suspend fun unmergeContributor(
        contributorId: ContributorId,
        aliasName: String,
    ): AppResult<ContributorId> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        val result = unmergeCore(contributorId, aliasName)
        if (result is AppResult.Success) {
            try {
                reindexer.reindexAllBooksForContributor(contributorId.value)
                reindexer.reindexAllBooksForContributor(result.data.value)
                reindexer.reindexContributorAliases(contributorId.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) {
                    "FTS reindex failed after unmerge of alias=$aliasName from ${contributorId.value}"
                }
            }
        }
        return result
    }

    /**
     * The unmerge write sequence (no FTS reindex). The snapshot + junction relink are the one
     * pair kept in a single SQLDelight [sqlTransaction]; the rest are sequential aggregate
     * writes over the single SQLDelight connection — matching the established cutover shape.
     */
    private suspend fun unmergeCore(
        contributorId: ContributorId,
        aliasName: String,
    ): AppResult<ContributorId> {
        val targetPayload =
            contributorRepo.findById(contributorId.value)
                ?: return AppResult.Failure(ContributorError.NotFound(debugInfo = "target=${contributorId.value}"))
        if (aliasName !in targetPayload.aliases) {
            return AppResult.Failure(
                ContributorError.AliasNotFound(debugInfo = "alias=$aliasName target=${contributorId.value}"),
            )
        }

        // 1. Resolve-or-create the contributor whose canonical name IS the alias. Dedup-aware:
        //    a pre-existing live row with the same normalized name is reused rather than
        //    blind-inserted, which would violate the `normalized_name` unique index. Passing
        //    aliasName as both name and sortName keeps the canonical name free of `Last, First`
        //    reordering, matching the prior blind-insert shape.
        val newId = contributorRepo.resolveOrCreate(name = aliasName, sortName = aliasName)

        // 2. Snapshot affected books, then re-link the matching junction rows to newId and clear
        //    credited_as — both over the single SQLDelight connection in one mini-transaction.
        //    Snapshotting BEFORE the relink because afterwards the matching rows no longer carry
        //    credited_as = aliasName.
        val affectedBookIds =
            sqlTransaction(sqlDb) {
                val ids =
                    sqlDb.bookContributorsQueries
                        .bookIdsForContributorWithCreditedAs(
                            contributor_id = contributorId.value,
                            credited_as = aliasName,
                        ).executeAsList()
                sqlDb.bookContributorsQueries.relinkByCreditedAs(
                    to_id = newId.value,
                    from_id = contributorId.value,
                    alias_name = aliasName,
                )
                ids
            }

        // 3. Re-upsert each affected book — bumps revision, publishes book.Updated.
        // One batched read replaces the per-book N+1 lookup.
        for (payload in bookRepo.findAllByIds(affectedBookIds)) {
            when (val upsertResult = bookRepo.upsert(payload)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> return AppResult.Failure(upsertResult.error)
            }
        }

        // 4. Remove the alias from target, re-upsert — publishes contributor.Updated(target).
        return when (
            val upsertResult =
                contributorRepo.upsert(targetPayload.copy(aliases = targetPayload.aliases - aliasName))
        ) {
            is AppResult.Success -> AppResult.Success(newId)
            is AppResult.Failure -> AppResult.Failure(upsertResult.error)
        }
    }

    override suspend fun deleteContributor(id: ContributorId): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        val result = deleteCore(id)
        if (result is AppResult.Success) {
            try {
                reindexer.reindexAllBooksForContributor(id.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "FTS reindex failed during delete of contributor ${id.value}" }
            }
        }
        return result
    }

    /**
     * The delete write sequence (no FTS reindex). The snapshot + junction hard-delete are the
     * one pair kept in a single SQLDelight [sqlTransaction]; the per-book re-upserts and the
     * contributor soft-delete are sequential aggregate writes over the single SQLDelight
     * connection — matching the established cutover shape.
     */
    private suspend fun deleteCore(id: ContributorId): AppResult<Unit> {
        contributorRepo.findById(id.value)
            ?: return contributorNotFound(id)
        // Snapshot the affected books, then hard-delete every junction row for the contributor —
        // both over the single SQLDelight connection in one mini-transaction.
        val affectedBookIds =
            sqlTransaction(sqlDb) {
                val ids = sqlDb.bookContributorsQueries.bookIdsForContributor(id.value).executeAsList()
                sqlDb.bookContributorsQueries.deleteAllForContributor(id.value)
                ids
            }
        for (payload in bookRepo.findAllByIds(affectedBookIds)) {
            val stripped = payload.copy(contributors = payload.contributors.filter { it.id != id.value })
            when (val upsertResult = bookRepo.upsert(stripped)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> return AppResult.Failure(upsertResult.error)
            }
        }
        return when (val softDeleteResult = contributorRepo.softDelete(id)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> AppResult.Failure(softDeleteResult.error)
        }
    }
}

/**
 * Constructs a [ContributorService] backed by [ContributorServiceImpl]. Public so
 * cross-module test harnesses (e.g. `:sharedLogic:jvmTest`'s
 * `WithClientSyncEngineAgainstServer`) can build the service without depending on
 * the Koin graph or piercing the `internal` access on [ContributorServiceImpl].
 * Production wiring continues to construct the impl directly inside the books
 * Koin module.
 */
fun createContributorService(
    contributorRepo: ContributorRepository,
    bookRepo: BookRepository,
    reindexer: BookSearchReindexer,
    sqlDb: ListenUpDatabase,
    driver: app.cash.sqldelight.db.SqlDriver,
): ContributorService =
    ContributorServiceImpl(contributorRepo, bookRepo, reindexer, sqlDb, BookAccessPolicy(sqlDb, driver))

/**
 * Scopes a [ContributorService] built by [createContributorService] to [principal] for one
 * request. Public so cross-module test harnesses can bind the authenticated caller without
 * piercing the `internal` access on [ContributorServiceImpl.copyWith]. Production wiring calls
 * [ContributorServiceImpl.copyWith] directly in the RPC route.
 */
fun contributorServiceScopedTo(
    service: ContributorService,
    principal: PrincipalProvider,
): ContributorService = (service as ContributorServiceImpl).copyWith(principal)

private data class UpdateOutcome(
    val reindexNeeded: Boolean,
    val result: AppResult<Unit>,
)

private fun contributorNotFound(id: ContributorId): AppResult.Failure =
    AppResult.Failure(ContributorError.NotFound(debugInfo = "contributorId=${id.value}"))

/**
 * Builds the target contributor's new alias set after a merge.
 *
 * Concatenates `targetAliases + sourceName + sourceAliases`, then trims each
 * candidate, lowercases it for the dedup key, drops empties and any candidate
 * that case-insensitively equals [targetName] (so the target never aliases
 * itself), and keeps the first occurrence's original case.
 *
 * Order: target's existing aliases come first (preserving their positions),
 * then source.name, then source's aliases.
 */
private fun mergeAliasesFor(
    targetAliases: List<String>,
    sourceName: String,
    sourceAliases: List<String>,
    targetName: String,
): List<String> {
    val seen = mutableSetOf<String>()
    val out = mutableListOf<String>()
    val excludedKey = targetName.trim().lowercase()
    for (candidate in targetAliases + sourceName + sourceAliases) {
        val trimmed = candidate.trim()
        val key = trimmed.lowercase()
        if (key.isEmpty() || key == excludedKey) continue
        if (seen.add(key)) out.add(trimmed)
    }
    return out
}

private fun ContributorSyncPayload.applyPatch(patch: ContributorUpdate): ContributorSyncPayload =
    copy(
        name = patch.name ?: name,
        sortName = patch.sortName ?: sortName,
        asin = patch.asin ?: asin,
        description = patch.description ?: description,
        imagePath = patch.imagePath ?: imagePath,
        birthDate = patch.birthDate ?: birthDate,
        deathDate = patch.deathDate ?: deathDate,
        website = patch.website ?: website,
    )
