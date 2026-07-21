@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.valueOrNull
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow

private val documentRepositoryLogger = KotlinLogging.logger {}

/**
 * Repository for accessing supplementary book documents (PDFs, ebooks, etc.).
 *
 * Documents are synced alongside their parent book — the metadata rows arrive via
 * the standard sync flow. Bytes are NOT pre-fetched; they are downloaded on
 * demand and cached on disk by [ensureLocal].
 *
 * The cache model mirrors cover images: file presence on disk = "downloaded". There
 * is no download-state DB table.
 */
interface DocumentRepository {
    /**
     * Reactive stream of documents for a book, ordered by index ascending.
     *
     * Reads from the local Room store only — this reflects whatever the last sync
     * persisted. Returns an empty list until the book's documents are synced.
     *
     * @param bookId Unique identifier for the book.
     * @return Flow emitting the current document list, updated whenever the local DB changes.
     */
    fun observeDocuments(bookId: BookId): Flow<List<BookDocument>>

    /**
     * Returns `true` if the document's bytes are already present in the on-disk cache.
     *
     * This is a synchronous filesystem check — it does not contact the server.
     *
     * @param bookId Unique identifier for the book.
     * @param docId Server document UUID (matches [BookDocument.id]).
     * @return `true` if the document file exists in the cache; `false` otherwise.
     */
    suspend fun isCached(
        bookId: BookId,
        docId: String,
    ): Boolean

    /**
     * Returns the absolute path to the local cached document, downloading it first if absent.
     *
     * If the document is already on disk the path is returned immediately without a network
     * call. If it is absent the document is fetched from
     * `GET /api/v1/books/{bookId}/documents/{docId}` (authenticated), written to the cache,
     * and the path is returned on success.
     *
     * @param bookId Unique identifier for the book.
     * @param docId Server document UUID (matches [BookDocument.id]).
     * @return [AppResult.Success] with the absolute file path, or [AppResult.Failure] if the
     *   download failed or the document metadata is not found in the local store.
     */
    suspend fun ensureLocal(
        bookId: BookId,
        docId: String,
    ): AppResult<String>

    /**
     * iOS-safe accessor: the local document path or `null` on failure (folded in Kotlin). Use from
     * Swift — never `await` the `AppResult`-returning [ensureLocal] (Swift Export bridge trap).
     */
    suspend fun ensureLocalPathOrNull(
        bookId: BookId,
        docId: String,
    ): String? =
        ensureLocal(
            bookId,
            docId,
        ).valueOrNull { documentRepositoryLogger.warn { "ensureLocalPathOrNull: ${it.debugInfo ?: it.message}" } }
}
