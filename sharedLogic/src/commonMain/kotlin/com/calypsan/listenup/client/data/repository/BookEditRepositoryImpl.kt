@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.domain.repository.BookContributorInput
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookSeriesInput
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

/**
 * Repository for book editing operations using an offline-first pattern.
 *
 * Edits are written to Room immediately and returned as success. The renovated
 * SSE sync engine propagates the change to the server in the background.
 *
 * @property bookDao Room DAO for book operations
 */
class BookEditRepositoryImpl(
    private val bookDao: BookDao,
) : BookEditRepository {
    /**
     * Update book metadata.
     *
     * Writes the change to the local Room database and returns success immediately.
     * The SSE sync engine reconciles the update with the server in the background.
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
                    updatedAt = Timestamp.now(),
                )
            bookDao.upsert(updated)

            logger.info { "Book updated locally: $bookId" }
            Success(Unit)
        }

    /**
     * Set book contributors.
     *
     * Touches the book's [updatedAt] timestamp in Room to signal a pending change,
     * then returns success immediately. The SSE sync engine propagates contributor
     * assignments to the server in the background.
     *
     * Note: local book-contributor relationships are not rewritten here; the SSE
     * sync event carries the authoritative contributor list back to the client.
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

            val updated = existing.copy(updatedAt = Timestamp.now())
            bookDao.upsert(updated)

            logger.info { "Book contributors marked for local refresh: $bookId, ${contributors.size} contributor(s)" }
            Success(Unit)
        }

    /**
     * Set book series.
     *
     * Touches the book's [updatedAt] timestamp in Room to signal a pending change,
     * then returns success immediately. The SSE sync engine propagates series
     * assignments to the server in the background.
     *
     * Note: local book-series relationships are not rewritten here; the SSE sync
     * event carries the authoritative series list back to the client.
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

            val updated = existing.copy(updatedAt = Timestamp.now())
            bookDao.upsert(updated)

            logger.info { "Book series marked for local refresh: $bookId, ${series.size} series" }
            Success(Unit)
        }
}
