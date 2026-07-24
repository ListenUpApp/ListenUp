package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.local.db.BookDocumentDao
import com.calypsan.listenup.client.data.local.db.BookDocumentEntity
import com.calypsan.listenup.client.data.local.documents.DocumentStorage
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.NonRpcReason
import com.calypsan.listenup.client.data.remote.NonRpcTransport
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.core.BookId
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DOCUMENT_REQUEST_TIMEOUT_MS = 120_000L

/**
 * Production [DocumentRepository].
 *
 * - [observeDocuments] maps DAO rows to the entity-free [BookDocument] domain model.
 * - [isCached] delegates to [DocumentStorage.exists] using the path computed from the doc's
 *   metadata row.
 * - [ensureLocal] returns the cached path immediately if present; otherwise fetches
 *   `GET /api/v1/books/{bookId}/documents/{docId}` via the authenticated Ktor client,
 *   writes the bytes to the cache, and returns the path.
 */
@NonRpcTransport(
    NonRpcReason.BINARY_TRANSFER,
    justification = "Document fetch streams raw file bytes into the local cache — no JSON-RPC frame.",
)
internal class DocumentRepositoryImpl(
    private val documentDao: BookDocumentDao,
    private val storage: DocumentStorage,
    private val clientFactory: ApiClientFactory,
) : DocumentRepository {
    override fun observeDocuments(bookId: BookId): Flow<List<BookDocument>> =
        documentDao
            .observeForBook(bookId.value)
            .map { entities -> entities.map(BookDocumentEntity::toDomain) }

    override suspend fun isCached(
        bookId: BookId,
        docId: String,
    ): Boolean {
        val entity =
            documentDao.getForBook(bookId.value).firstOrNull { it.id == docId }
                ?: return false
        return storage.exists(storage.pathFor(bookId.value, docId, entity.format))
    }

    override suspend fun ensureLocal(
        bookId: BookId,
        docId: String,
    ): AppResult<String> {
        val entity =
            documentDao.getForBook(bookId.value).firstOrNull { it.id == docId }
                ?: return AppResult.Failure(
                    SyncError.NotFound(
                        domain = "document",
                        entityId = docId,
                        debugInfo = "Document $docId not found in local store for book ${bookId.value}",
                    ),
                )

        val path = storage.pathFor(bookId.value, docId, entity.format)
        if (storage.exists(path)) return AppResult.Success(path)

        return suspendRunCatching {
            val client = clientFactory.getClient()
            val bytes =
                client
                    .get("/api/v1/books/${bookId.value}/documents/$docId") {
                        timeout { requestTimeoutMillis = DOCUMENT_REQUEST_TIMEOUT_MS }
                    }.body<ByteArray>()
            storage.write(path, bytes)
            path
        }
    }
}

/** Maps a [BookDocumentEntity] row to the entity-free [BookDocument] domain model. */
internal fun BookDocumentEntity.toDomain(): BookDocument =
    BookDocument(
        id = id,
        index = index,
        filename = filename,
        format = format,
        size = size,
        hash = hash,
    )
