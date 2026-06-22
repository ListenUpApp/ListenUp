package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookDocumentPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.koin.ktor.ext.inject
import java.nio.file.Files

/**
 * HTTP integration tests for `GET /api/v1/books/{id}/documents/{docId}`.
 *
 * Boots the full `Application.module()` so the route, [DocumentFileLocator], repository,
 * and JWT auth all participate as in production. A real `.pdf` is written under the book's
 * `rootRelPath` and a `book_documents` row (with an explicit `hash`) is persisted via
 * [BookRepository.upsert], so the route has a stable ETag value and real bytes to stream.
 */
class DocumentRoutesTest :
    FunSpec({

        suspend fun HttpClient.mintAccessToken(): String {
            post("/api/v1/auth/setup") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
            }
            return post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("root@x", "x".repeat(8)))
            }.body<AppResult<AuthSession>>()
                .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                .data
                .accessToken
                .value
        }

        test("GET documents/{docId} returns 200 with the bytes, a pdf content-type, and the hash ETag") {
            val libraryRoot = Files.createTempDirectory("listenup-doc-route-ok-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    val pdfBytes = fakePdf()
                    Files.write(bookDir.resolve("guide.pdf"), pdfBytes)

                    val repo by application.inject<BookRepository>()
                    repo.upsert(
                        documentFixture(
                            id = "b1",
                            docId = "doc1",
                            filename = "guide.pdf",
                            hash = "abc123",
                            size = pdfBytes.size.toLong(),
                        ),
                    )

                    val response = client.get("/api/v1/books/b1/documents/doc1") { bearerAuth(token) }

                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.ETag] shouldBe "\"abc123\""
                    response.headers[HttpHeaders.ContentType]?.lowercase() shouldContain "pdf"
                    response.bodyAsBytes().toList() shouldBe pdfBytes.toList()
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET documents/{docId} returns 304 when If-None-Match matches the hash") {
            val libraryRoot = Files.createTempDirectory("listenup-doc-route-304-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b2"))
                    Files.write(bookDir.resolve("guide.pdf"), fakePdf())

                    val repo by application.inject<BookRepository>()
                    repo.upsert(documentFixture(id = "b2", docId = "doc2", filename = "guide.pdf", hash = "abc123", size = 8L))

                    val response =
                        client.get("/api/v1/books/b2/documents/doc2") {
                            bearerAuth(token)
                            header(HttpHeaders.IfNoneMatch, "\"abc123\"")
                        }

                    response.status shouldBe HttpStatusCode.NotModified
                    response.bodyAsBytes().size shouldBe 0
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET documents/{docId} returns 404 for an unknown document id") {
            val libraryRoot = Files.createTempDirectory("listenup-doc-route-404-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b3"))
                    Files.write(bookDir.resolve("guide.pdf"), fakePdf())
                    val repo by application.inject<BookRepository>()
                    repo.upsert(documentFixture(id = "b3", docId = "doc3", filename = "guide.pdf", hash = "abc123", size = 8L))

                    val response = client.get("/api/v1/books/b3/documents/does-not-exist") { bearerAuth(token) }

                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

/** Minimal but valid-enough PDF magic-number prefix + a little body. */
private fun fakePdf(): ByteArray =
    byteArrayOf(
        '%'.code.toByte(),
        'P'.code.toByte(),
        'D'.code.toByte(),
        'F'.code.toByte(),
        '-'.code.toByte(),
        '1'.code.toByte(),
        '.'.code.toByte(),
        '4'.code.toByte(),
    )

/** Book payload with one supplementary document (and one audio file so the book is valid). */
private fun documentFixture(
    id: String,
    docId: String,
    filename: String,
    hash: String,
    size: Long,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "Book $id",
        sortTitle = "Book $id",
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = 3_600_000L,
        cover = null,
        rootRelPath = "books/$id",
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af-$id",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "",
                    duration = 3_600_000L,
                    size = 500_000_000L,
                ),
            ),
        chapters = emptyList(),
        documents =
            listOf(
                BookDocumentPayload(id = docId, index = 0, filename = filename, format = "pdf", size = size, hash = hash),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
