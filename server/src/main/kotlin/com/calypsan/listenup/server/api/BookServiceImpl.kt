package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.error.CoverError
import com.calypsan.listenup.server.cover.CoverInfo
import com.calypsan.listenup.server.cover.CoverStorage
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private const val MAX_SEARCH_LIMIT = 200
private const val MAX_CONTRIBUTORS_PER_BOOK = 200
private const val MAX_SERIES_PER_BOOK = 200

/**
 * Thin [BookService] implementation. The work lives in [BookRepository]; this
 * class translates between the wire contract and the repository's public API.
 *
 * [updateBook] reads the current aggregate, applies the [BookUpdate] patch
 * field-by-field (null means "don't touch"), and writes the patched payload
 * through the syncable substrate so the revision bumps and the change-bus
 * fires uniformly. The read-then-write straddles two repository calls; both
 * run inside the same [suspendTransaction] so the substrate's own transaction
 * nests cleanly.
 *
 * [setBookContributors] and [setBookSeries] follow the same shape — read the
 * aggregate, resolve each input row to a stable child id (passing through the
 * corresponding `resolveOrCreate` when the input's id is null), write the
 * patched aggregate back through `repo.upsert`. The whole flow runs in one
 * [suspendTransaction] so any auto-created child rolls back if the book upsert
 * fails. List position carries on the list index — `BookRepository.replaceSeries`
 * stamps `ordinal` from the index, so callers sort by [BookSeriesInput.position]
 * before mapping to the wire payload.
 *
 * [deleteBookCover] is the one mutation that touches the filesystem.
 * It resolves the cover file path via [BookRepository.coverInfo] up front,
 * nullifies the book's cover columns inside a [suspendTransaction] (the
 * revision-bump and change-bus fire atomically with the row update), then
 * delegates the file delete to [CoverStorage] **after** the transaction
 * commits — the DB row is the source of truth, and a flaky filesystem can't
 * roll back a successful nullify. Embedded covers carry no file of their own
 * (the artwork lives inside the audio file), so the post-commit delete is a
 * no-op for them.
 */
internal class BookServiceImpl(
    private val repo: BookRepository,
    private val contributorRepo: ContributorRepository,
    private val seriesRepo: SeriesRepository,
    private val coverStorage: CoverStorage,
    private val db: Database,
) : BookService {
    override suspend fun getBook(id: BookId): AppResult<BookSyncPayload> {
        val payload = repo.findById(id)
        return if (payload != null) {
            AppResult.Success(payload)
        } else {
            AppResult.Failure(SyncError.NotFound(domain = "book", entityId = id.value))
        }
    }

    override suspend fun searchBooks(
        query: String,
        limit: Int,
    ): AppResult<List<BookId>> {
        if (query.isBlank()) return AppResult.Success(emptyList())
        return AppResult.Success(repo.searchFts(query, limit.coerceIn(1, MAX_SEARCH_LIMIT)))
    }

    override suspend fun updateBook(
        id: BookId,
        patch: BookUpdate,
    ): AppResult<Unit> =
        suspendTransaction(db) {
            val current =
                repo.findById(id)
                    ?: return@suspendTransaction bookNotFound(id)
            when (val upsertResult = repo.upsert(current.applyPatch(patch))) {
                is AppResult.Success -> AppResult.Success(Unit)
                is AppResult.Failure -> AppResult.Failure(upsertResult.error)
            }
        }

    override suspend fun setBookContributors(
        id: BookId,
        contributors: List<BookContributorInput>,
    ): AppResult<Unit> {
        if (contributors.size > MAX_CONTRIBUTORS_PER_BOOK) {
            return AppResult.Failure(
                BookError.InvalidInput(
                    debugInfo = "contributors: size ${contributors.size} exceeds max $MAX_CONTRIBUTORS_PER_BOOK",
                ),
            )
        }
        return suspendTransaction(db) {
            val current =
                repo.findById(id)
                    ?: return@suspendTransaction bookNotFound(id)
            val resolved =
                contributors
                    .sortedBy { it.position }
                    .map { input ->
                        val resolvedId =
                            input.id?.value
                                ?: contributorRepo.resolveOrCreate(input.name).value
                        BookContributorPayload(
                            id = resolvedId,
                            name = input.name,
                            sortName = null,
                            role = input.role,
                            creditedAs = input.creditedAs,
                        )
                    }
            when (val upsertResult = repo.upsert(current.copy(contributors = resolved))) {
                is AppResult.Success -> AppResult.Success(Unit)
                is AppResult.Failure -> AppResult.Failure(upsertResult.error)
            }
        }
    }

    override suspend fun setBookSeries(
        id: BookId,
        series: List<BookSeriesInput>,
    ): AppResult<Unit> {
        if (series.size > MAX_SERIES_PER_BOOK) {
            return AppResult.Failure(
                BookError.InvalidInput(
                    debugInfo = "series: size ${series.size} exceeds max $MAX_SERIES_PER_BOOK",
                ),
            )
        }
        return suspendTransaction(db) {
            val current =
                repo.findById(id)
                    ?: return@suspendTransaction bookNotFound(id)
            val resolved =
                series
                    .sortedWith(compareBy(nullsLast()) { it.position })
                    .map { input ->
                        val resolvedId =
                            input.id?.value
                                ?: seriesRepo.resolveOrCreate(input.name).value
                        BookSeriesPayload(
                            id = resolvedId,
                            name = input.name,
                            sequence = input.position?.toString(),
                        )
                    }
            when (val upsertResult = repo.upsert(current.copy(series = resolved))) {
                is AppResult.Success -> AppResult.Success(Unit)
                is AppResult.Failure -> AppResult.Failure(upsertResult.error)
            }
        }
    }

    override suspend fun deleteBookCover(id: BookId): AppResult<Unit> {
        // Resolve the on-disk cover file (if any) BEFORE the transaction so
        // we can best-effort delete it post-commit. Embedded covers have no
        // file of their own — the artwork is inside the audio file — so they
        // surface as Embedded here and are deliberately ignored.
        val pathToDelete = (repo.coverInfo(id) as? CoverInfo.Filesystem)?.path
        val result: AppResult<Unit> =
            suspendTransaction(db) {
                val current =
                    repo.findById(id)
                        ?: return@suspendTransaction bookNotFound(id)
                if (current.cover == null) {
                    return@suspendTransaction AppResult.Failure(
                        CoverError.NotPresent(debugInfo = "bookId=${id.value}"),
                    )
                }
                when (val upsertResult = repo.upsert(current.copy(cover = null))) {
                    is AppResult.Success -> AppResult.Success(Unit)
                    is AppResult.Failure -> AppResult.Failure(upsertResult.error)
                }
            }
        if (result is AppResult.Success && pathToDelete != null) {
            coverStorage.delete(pathToDelete)
        }
        return result
    }
}

/**
 * Constructs a [BookService] backed by [BookServiceImpl]. Public so cross-module
 * test harnesses (e.g. `:sharedLogic:jvmTest`'s `WithClientSyncEngineAgainstServer`)
 * can build the service without depending on the Koin graph or piercing the
 * `internal` access on [BookServiceImpl]. Production wiring continues to construct
 * the impl directly inside the books Koin module.
 */
fun createBookService(
    repo: BookRepository,
    contributorRepo: ContributorRepository,
    seriesRepo: SeriesRepository,
    coverStorage: CoverStorage,
    db: org.jetbrains.exposed.v1.jdbc.Database,
): BookService = BookServiceImpl(repo, contributorRepo, seriesRepo, coverStorage, db)

private fun bookNotFound(id: BookId): AppResult.Failure =
    AppResult.Failure(BookError.NotFound(debugInfo = "bookId=${id.value}"))

private fun BookSyncPayload.applyPatch(patch: BookUpdate): BookSyncPayload =
    copy(
        title = patch.title ?: title,
        sortTitle = patch.sortTitle ?: sortTitle,
        subtitle = patch.subtitle ?: subtitle,
        description = patch.description ?: description,
        publisher = patch.publisher ?: publisher,
        publishYear = patch.publishYear ?: publishYear,
        language = patch.language ?: language,
        isbn = patch.isbn ?: isbn,
        asin = patch.asin ?: asin,
        abridged = patch.abridged ?: abridged,
    )
