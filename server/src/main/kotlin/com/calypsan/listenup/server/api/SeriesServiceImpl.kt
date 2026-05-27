package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.BookSeriesMembershipTable
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookSearchReindexer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private val logger = KotlinLogging.logger {}

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
 * the series itself. The whole cascade runs inside one [suspendTransaction]
 * so any failure rolls back the lot. Post-commit, every affected book has its
 * `book_search` FTS row reindexed (best-effort; logged on failure).
 *
 * This service is not user-scoped — it carries no [com.calypsan.listenup.server.auth.PrincipalProvider]
 * because series reads and edits are not per-user. Auth is enforced at the route
 * layer (JWT gate in Application.kt).
 */
internal class SeriesServiceImpl(
    private val seriesRepo: SeriesRepository,
    private val bookRepo: BookRepository,
    private val reindexer: BookSearchReindexer,
    private val db: Database,
) : SeriesService {
    override suspend fun getSeries(id: SeriesId): AppResult<SeriesSyncPayload?> =
        AppResult.Success(seriesRepo.findById(id.value))

    override suspend fun listBooksBySeries(id: SeriesId): AppResult<List<BookSyncPayload>> =
        AppResult.Success(bookRepo.findBySeries(id))

    override suspend fun updateSeries(
        id: SeriesId,
        patch: SeriesUpdate,
    ): AppResult<Unit> {
        val outcome: SeriesUpdateOutcome =
            suspendTransaction(db) {
                val current =
                    seriesRepo.findById(id.value)
                        ?: return@suspendTransaction SeriesUpdateOutcome(false, seriesNotFound(id))
                val patched = current.applyPatch(patch)
                val nameChanged = patched.name != current.name || patched.sortName != current.sortName
                when (val upsertResult = seriesRepo.upsert(patched)) {
                    is AppResult.Success -> SeriesUpdateOutcome(nameChanged, AppResult.Success(Unit))
                    is AppResult.Failure -> SeriesUpdateOutcome(false, AppResult.Failure(upsertResult.error))
                }
            }
        if (outcome.result is AppResult.Success && outcome.reindexNeeded) {
            try {
                reindexer.reindexAllBooksForSeries(id.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "FTS reindex failed for series ${id.value}" }
            }
        }
        return outcome.result
    }

    override suspend fun deleteSeries(id: SeriesId): AppResult<Unit> {
        val result: AppResult<Unit> =
            suspendTransaction(db) {
                seriesRepo.findById(id.value)
                    ?: return@suspendTransaction seriesNotFound(id)
                val affectedBookIds = BookSeriesMembershipTable.bookIdsForSeries(id.value)
                BookSeriesMembershipTable.deleteAllForSeries(id.value)
                for (bookId in affectedBookIds) {
                    val payload = bookRepo.findById(BookId(bookId)) ?: continue
                    val stripped = payload.copy(series = payload.series.filter { it.id != id.value })
                    when (val upsertResult = bookRepo.upsert(stripped)) {
                        is AppResult.Success -> Unit
                        is AppResult.Failure -> return@suspendTransaction AppResult.Failure(upsertResult.error)
                    }
                }
                when (val softDeleteResult = seriesRepo.softDelete(id)) {
                    is AppResult.Success -> AppResult.Success(Unit)
                    is AppResult.Failure -> AppResult.Failure(softDeleteResult.error)
                }
            }
        if (result is AppResult.Success) {
            try {
                reindexer.reindexAllBooksForSeries(id.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "FTS reindex failed during delete of series ${id.value}" }
            }
        }
        return result
    }
}

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
