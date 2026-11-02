package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart

/**
 * In-memory [DocumentRepository]: every book shares [documents]; [ensureLocalResult] is what [ensureLocal] answers; no
 * document is cached. [beforeObserve] runs as each book's documents start flowing, so a test can hold a book back.
 */
internal class FakeDocumentRepository(
    val documents: MutableStateFlow<List<BookDocument>> = MutableStateFlow(emptyList()),
    var ensureLocalResult: AppResult<String> = AppResult.Success("/cache/doc.pdf"),
    var beforeObserve: suspend (BookId) -> Unit = {},
) : DocumentRepository {
    override fun observeDocuments(bookId: BookId): Flow<List<BookDocument>> =
        documents.onStart { beforeObserve(bookId) }

    override suspend fun isCached(
        bookId: BookId,
        docId: String,
    ): Boolean = false

    override suspend fun ensureLocal(
        bookId: BookId,
        docId: String,
    ): AppResult<String> = ensureLocalResult
}
