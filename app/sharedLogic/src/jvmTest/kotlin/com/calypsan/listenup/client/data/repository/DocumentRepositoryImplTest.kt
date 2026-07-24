package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookDocumentEntity
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.documents.DocumentStorage
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest

/**
 * Tests for [DocumentRepositoryImpl]:
 * 1. [DocumentRepository.observeDocuments] maps DAO rows to [BookDocument] domain objects.
 * 2. [DocumentRepository.isCached] delegates to [DocumentStorage.exists].
 * 3. [DocumentRepository.ensureLocal] returns the cached path without a network call when
 *    already on disk; downloads and returns the path when absent.
 */
class DocumentRepositoryImplTest :
    FunSpec({

        // ---- helpers -------------------------------------------------------

        /** Minimal [BookEntity] required to satisfy the FK constraint from [BookDocumentEntity]. */
        fun seedBookEntity(bookId: String = "book1") =
            BookEntity(
                id = BookId(bookId),
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = "Test $bookId",
                sortTitle = "Test $bookId",
                subtitle = null,
                coverHash = null,
                totalDuration = 0L,
                description = null,
                publishYear = null,
                publisher = null,
                language = null,
                isbn = null,
                asin = null,
                abridged = false,
                createdAt = Timestamp(1L),
                updatedAt = Timestamp(1L),
            )

        fun fakeEntity(
            bookId: String = "book1",
            index: Int = 0,
            id: String = "doc1",
            filename: String = "map.pdf",
            format: String = "pdf",
            size: Long = 1_024L,
            hash: String = "abc123",
        ) = BookDocumentEntity(
            bookId = BookId(bookId),
            index = index,
            id = id,
            filename = filename,
            format = format,
            size = size,
            hash = hash,
        )

        // ---- 1. observeDocuments maps entity rows to BookDocument -----------

        test("observeDocuments emits empty list when no rows") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val storage = FakeDocumentStorage()
                    val clientFactory = mock<ApiClientFactory>()
                    val sut =
                        DocumentRepositoryImpl(
                            documentDao = db.bookDocumentDao(),
                            storage = storage,
                            clientFactory = clientFactory,
                        )

                    sut.observeDocuments(BookId("book1")).test {
                        awaitItem() shouldBe emptyList()
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("observeDocuments maps all fields and preserves index order") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    db.bookDao().upsert(seedBookEntity("book1"))
                    val dao = db.bookDocumentDao()
                    dao.upsertAll(
                        listOf(
                            fakeEntity(index = 1, id = "doc2", filename = "appendix.epub", format = "epub"),
                            fakeEntity(index = 0, id = "doc1", filename = "map.pdf", format = "pdf"),
                        ),
                    )

                    val storage = FakeDocumentStorage()
                    val clientFactory = mock<ApiClientFactory>()
                    val sut =
                        DocumentRepositoryImpl(
                            documentDao = dao,
                            storage = storage,
                            clientFactory = clientFactory,
                        )

                    sut.observeDocuments(BookId("book1")).test {
                        val docs = awaitItem()
                        docs.size shouldBe 2
                        // DAO orders by index ASC
                        docs[0].id shouldBe "doc1"
                        docs[0].index shouldBe 0
                        docs[0].filename shouldBe "map.pdf"
                        docs[0].format shouldBe "pdf"
                        docs[0].size shouldBe 1_024L
                        docs[0].hash shouldBe "abc123"
                        docs[1].id shouldBe "doc2"
                        docs[1].index shouldBe 1
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        // ---- 2. isCached reflects DocumentStorage.exists -------------------

        test("isCached returns false when document is not in DB") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val storage = FakeDocumentStorage()
                    val clientFactory = mock<ApiClientFactory>()
                    val sut =
                        DocumentRepositoryImpl(
                            documentDao = db.bookDocumentDao(),
                            storage = storage,
                            clientFactory = clientFactory,
                        )

                    sut.isCached(BookId("book1"), "doc1") shouldBe false
                }
            } finally {
                db.close()
            }
        }

        test("isCached returns false when file absent from storage") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    db.bookDao().upsert(seedBookEntity("book1"))
                    val dao = db.bookDocumentDao()
                    dao.upsertAll(listOf(fakeEntity()))

                    val storage = FakeDocumentStorage(existingPaths = emptySet())
                    val clientFactory = mock<ApiClientFactory>()
                    val sut =
                        DocumentRepositoryImpl(
                            documentDao = dao,
                            storage = storage,
                            clientFactory = clientFactory,
                        )

                    sut.isCached(BookId("book1"), "doc1") shouldBe false
                }
            } finally {
                db.close()
            }
        }

        test("isCached returns true when file present in storage") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    db.bookDao().upsert(seedBookEntity("book1"))
                    val dao = db.bookDocumentDao()
                    dao.upsertAll(listOf(fakeEntity()))

                    val path = FakeDocumentStorage.fakePathFor("book1", "doc1", "pdf")
                    val storage = FakeDocumentStorage(existingPaths = setOf(path))
                    val clientFactory = mock<ApiClientFactory>()
                    val sut =
                        DocumentRepositoryImpl(
                            documentDao = dao,
                            storage = storage,
                            clientFactory = clientFactory,
                        )

                    sut.isCached(BookId("book1"), "doc1") shouldBe true
                }
            } finally {
                db.close()
            }
        }

        // ---- 3. ensureLocal path-already-cached (no network) ---------------

        test("ensureLocal returns cached path without a network call when file is present") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    db.bookDao().upsert(seedBookEntity("book1"))
                    val dao = db.bookDocumentDao()
                    dao.upsertAll(listOf(fakeEntity()))

                    val expectedPath = FakeDocumentStorage.fakePathFor("book1", "doc1", "pdf")
                    val storage = FakeDocumentStorage(existingPaths = setOf(expectedPath))
                    // clientFactory.getClient() must NOT be called
                    val clientFactory = mock<ApiClientFactory>()
                    val sut =
                        DocumentRepositoryImpl(
                            documentDao = dao,
                            storage = storage,
                            clientFactory = clientFactory,
                        )

                    val result = sut.ensureLocal(BookId("book1"), "doc1")
                    result.shouldBeInstanceOf<AppResult.Success<String>>()
                    result.data shouldBe expectedPath
                    storage.writeCallCount shouldBe 0
                }
            } finally {
                db.close()
            }
        }

        // ---- 4. ensureLocal download path ----------------------------------

        test("ensureLocal downloads when absent, writes to cache, returns path") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    db.bookDao().upsert(seedBookEntity("book1"))
                    val dao = db.bookDocumentDao()
                    dao.upsertAll(listOf(fakeEntity(bookId = "book1", id = "doc1", format = "pdf")))

                    val storage = FakeDocumentStorage(existingPaths = emptySet())
                    val documentBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF

                    var capturedPath: String? = null
                    val engine =
                        MockEngine { request ->
                            capturedPath = request.url.encodedPath
                            respond(content = documentBytes, status = HttpStatusCode.OK)
                        }
                    val httpClient =
                        HttpClient(engine) {
                            install(ContentNegotiation) { json() }
                        }
                    val clientFactory =
                        mock<ApiClientFactory> {
                            everySuspend { getClient() } returns httpClient
                        }

                    val sut =
                        DocumentRepositoryImpl(
                            documentDao = dao,
                            storage = storage,
                            clientFactory = clientFactory,
                        )

                    val result = sut.ensureLocal(BookId("book1"), "doc1")

                    // Assert success
                    result.shouldBeInstanceOf<AppResult.Success<String>>()
                    val returnedPath = result.data
                    returnedPath shouldBe FakeDocumentStorage.fakePathFor("book1", "doc1", "pdf")

                    // Assert the correct URL was called
                    capturedPath shouldBe "/api/v1/books/book1/documents/doc1"

                    // Assert bytes were written to storage
                    storage.writeCallCount shouldBe 1
                    storage.lastWrittenPath shouldBe returnedPath
                    storage.lastWrittenBytes shouldBe documentBytes
                }
            } finally {
                db.close()
            }
        }

        test("ensureLocal returns failure when document not found in local DB") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val storage = FakeDocumentStorage()
                    val clientFactory = mock<ApiClientFactory>()
                    val sut =
                        DocumentRepositoryImpl(
                            documentDao = db.bookDocumentDao(),
                            storage = storage,
                            clientFactory = clientFactory,
                        )

                    val result = sut.ensureLocal(BookId("book1"), "missing-doc")
                    result.shouldBeInstanceOf<AppResult.Failure>()
                }
            } finally {
                db.close()
            }
        }
    })

// ---- Fake DocumentStorage --------------------------------------------------------

/**
 * In-memory test double for [DocumentStorage]. Tracks written bytes and allows seeding
 * pre-existing paths to simulate a warm cache.
 */
private class FakeDocumentStorage(
    existingPaths: Set<String> = emptySet(),
) : DocumentStorage {
    private val cachedPaths = existingPaths.toMutableSet()
    var writeCallCount: Int = 0
    var lastWrittenPath: String? = null
    var lastWrittenBytes: ByteArray? = null

    override fun pathFor(
        bookId: String,
        docId: String,
        format: String,
    ): String = fakePathFor(bookId, docId, format)

    override fun exists(path: String): Boolean = cachedPaths.contains(path)

    override suspend fun write(
        path: String,
        bytes: ByteArray,
    ) {
        writeCallCount++
        lastWrittenPath = path
        lastWrittenBytes = bytes
        cachedPaths.add(path)
    }

    override suspend fun deleteCached(
        bookId: String,
        docId: String,
        format: String,
    ) {
        cachedPaths.remove(fakePathFor(bookId, docId, format))
    }

    companion object {
        fun fakePathFor(
            bookId: String,
            docId: String,
            format: String,
        ): String = "/fake/documents/$bookId/$docId.$format"
    }
}
