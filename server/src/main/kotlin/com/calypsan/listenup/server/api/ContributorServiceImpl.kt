package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.error.ContributorError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.db.BookContributorTable
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.sync.BookSearchReindexer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private val logger = KotlinLogging.logger {}

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
 * the contributor itself. The whole cascade runs inside one [suspendTransaction]
 * so any failure rolls back the lot. Post-commit, every affected book has its
 * `book_search` FTS row reindexed (best-effort; logged on failure).
 *
 * This service is not user-scoped — it carries no [com.calypsan.listenup.server.auth.PrincipalProvider]
 * because contributor reads and edits are not per-user. Auth is enforced at the
 * route layer (JWT gate in Application.kt).
 */
internal class ContributorServiceImpl(
    private val contributorRepo: ContributorRepository,
    private val bookRepo: BookRepository,
    private val reindexer: BookSearchReindexer,
    private val db: Database,
) : ContributorService {
    override suspend fun getContributor(id: ContributorId): AppResult<ContributorSyncPayload?> =
        AppResult.Success(contributorRepo.findById(id.value))

    override suspend fun listBooksByContributor(id: ContributorId): AppResult<List<BookSyncPayload>> =
        AppResult.Success(bookRepo.findByContributor(id))

    override suspend fun updateContributor(
        id: ContributorId,
        patch: ContributorUpdate,
    ): AppResult<Unit> {
        val outcome: UpdateOutcome =
            suspendTransaction(db) {
                val current =
                    contributorRepo.findById(id.value)
                        ?: return@suspendTransaction UpdateOutcome(false, contributorNotFound(id))
                val patched = current.applyPatch(patch)
                val nameChanged = patched.name != current.name || patched.sortName != current.sortName
                when (val upsertResult = contributorRepo.upsert(patched)) {
                    is AppResult.Success -> UpdateOutcome(nameChanged, AppResult.Success(Unit))
                    is AppResult.Failure -> UpdateOutcome(false, AppResult.Failure(upsertResult.error))
                }
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
    ): AppResult<Unit> =
        AppResult.Failure(
            ContributorError.NotFound(
                debugInfo = "mergeContributors not yet implemented (Books-C2 Task 14)",
            ),
        )

    override suspend fun unmergeContributor(
        contributorId: ContributorId,
        aliasName: String,
    ): AppResult<ContributorId> =
        AppResult.Failure(
            ContributorError.NotFound(
                debugInfo = "unmergeContributor not yet implemented (Books-C2 Task 15)",
            ),
        )

    override suspend fun deleteContributor(id: ContributorId): AppResult<Unit> {
        val result: AppResult<Unit> =
            suspendTransaction(db) {
                contributorRepo.findById(id.value)
                    ?: return@suspendTransaction contributorNotFound(id)
                val affectedBookIds = BookContributorTable.bookIdsForContributor(id.value)
                BookContributorTable.deleteAllForContributor(id.value)
                for (bookId in affectedBookIds) {
                    val payload = bookRepo.findById(BookId(bookId)) ?: continue
                    val stripped = payload.copy(contributors = payload.contributors.filter { it.id != id.value })
                    when (val upsertResult = bookRepo.upsert(stripped)) {
                        is AppResult.Success -> Unit
                        is AppResult.Failure -> return@suspendTransaction AppResult.Failure(upsertResult.error)
                    }
                }
                when (val softDeleteResult = contributorRepo.softDelete(id)) {
                    is AppResult.Success -> AppResult.Success(Unit)
                    is AppResult.Failure -> AppResult.Failure(softDeleteResult.error)
                }
            }
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
    db: Database,
): ContributorService = ContributorServiceImpl(contributorRepo, bookRepo, reindexer, db)

private data class UpdateOutcome(
    val reindexNeeded: Boolean,
    val result: AppResult<Unit>,
)

private fun contributorNotFound(id: ContributorId): AppResult.Failure =
    AppResult.Failure(ContributorError.NotFound(debugInfo = "contributorId=${id.value}"))

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
