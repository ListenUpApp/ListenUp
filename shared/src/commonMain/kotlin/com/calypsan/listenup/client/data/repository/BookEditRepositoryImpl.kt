@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.IODispatcher
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.SyncState
import com.calypsan.listenup.client.domain.repository.BookContributorInput
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookSeriesInput
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

/**
 * Repository for book editing operations using offline-first pattern.
 *
 * Handles the edit flow:
 * 1. Apply optimistic update to local database (syncState = PENDING)
 * 2. Queue operation for server sync via PendingOperationRepository
 * 3. Return success immediately
 *
 * Server propagation returns when the book sync domain migrates to the renovated engine.
 *
 * @property bookDao Room DAO for book operations
 * @property pendingOperationRepository Repository for queuing sync operations
 * @property bookUpdateHandler Handler for book update operations
 * @property setBookContributorsHandler Handler for set contributors operations
 * @property setBookSeriesHandler Handler for set series operations
 */
class BookEditRepositoryImpl(
    private val bookDao: BookDao,
) : BookEditRepository {
    /**
     * Update book metadata.
     *
     * Flow:
     * 1. Get existing book from local database
     * 2. Apply optimistic update with syncState = PENDING
     * 3. Queue operation (coalesces with any pending update)
     * 4. Return success immediately
     */
    override suspend fun updateBook(
        bookId: String,
        title: String?,
        sortTitle: String?,
        subtitle: String?,
        description: String?,
        publisher: String?,
        publishYear: String?,
        language: String?,
        isbn: String?,
        asin: String?,
        abridged: Boolean?,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            logger.debug { "Updating book (offline-first): $bookId" }

            // Get existing book
            val existing = bookDao.getById(BookId(bookId))
            if (existing == null) {
                logger.error { "Book not found: $bookId" }
                return@withContext Failure(Exception("Book not found: $bookId"))
            }

            // Apply optimistic update
            val updated =
                existing.copy(
                    title = title ?: existing.title,
                    sortTitle = sortTitle ?: existing.sortTitle,
                    subtitle = subtitle ?: existing.subtitle,
                    description = description ?: existing.description,
                    publisher = publisher ?: existing.publisher,
                    publishYear = publishYear?.toIntOrNull() ?: existing.publishYear,
                    language = language ?: existing.language,
                    isbn = isbn ?: existing.isbn,
                    asin = asin ?: existing.asin,
                    abridged = abridged ?: existing.abridged,
                    syncState = SyncState.NOT_SYNCED,
                    lastModified = Timestamp.now(),
                )
            bookDao.upsert(updated)

            logger.info { "Book updated locally: $bookId" }
            Success(Unit)
        }

    /**
     * Set book contributors.
     *
     * Flow:
     * 1. Mark book as pending sync
     * 2. Queue operation with full contributor list
     * 3. Return success immediately
     *
     * Note: Local book-contributor relationships are not updated here.
     * The pull sync after successful push will bring in the correct relationships.
     */
    override suspend fun setBookContributors(
        bookId: String,
        contributors: List<BookContributorInput>,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            logger.debug { "Setting contributors for book (offline-first): $bookId, count: ${contributors.size}" }

            // Mark book as pending sync
            val existing = bookDao.getById(BookId(bookId))
            if (existing == null) {
                logger.error { "Book not found: $bookId" }
                return@withContext Failure(Exception("Book not found: $bookId"))
            }

            val updated =
                existing.copy(
                    syncState = SyncState.NOT_SYNCED,
                    lastModified = Timestamp.now(),
                )
            bookDao.upsert(updated)

            logger.info { "Book contributors marked for local refresh: $bookId, ${contributors.size} contributor(s)" }
            Success(Unit)
        }

    /**
     * Set book series.
     *
     * Flow:
     * 1. Mark book as pending sync
     * 2. Queue operation with full series list
     * 3. Return success immediately
     *
     * Note: Local book-series relationships are not updated here.
     * The pull sync after successful push will bring in the correct relationships.
     */
    override suspend fun setBookSeries(
        bookId: String,
        series: List<BookSeriesInput>,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            logger.debug { "Setting series for book (offline-first): $bookId, count: ${series.size}" }

            // Mark book as pending sync
            val existing = bookDao.getById(BookId(bookId))
            if (existing == null) {
                logger.error { "Book not found: $bookId" }
                return@withContext Failure(Exception("Book not found: $bookId"))
            }

            val updated =
                existing.copy(
                    syncState = SyncState.NOT_SYNCED,
                    lastModified = Timestamp.now(),
                )
            bookDao.upsert(updated)

            logger.info { "Book series marked for local refresh: $bookId, ${series.size} series" }
            Success(Unit)
        }
}
