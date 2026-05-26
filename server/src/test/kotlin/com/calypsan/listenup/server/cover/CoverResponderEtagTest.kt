package com.calypsan.listenup.server.cover

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
 * HTTP-cache integration tests for `GET /api/v1/books/{id}/cover`.
 *
 * Boots the full `Application.module()` so the route, responder, repository,
 * and JWT auth all participate exactly as they do in production. The cover
 * bytes are seeded by writing a real image file under the book's `rootRelPath`
 * and an explicit `coverHash` is persisted via [BookRepository.upsert] so the
 * route has a stable value to fold into the `ETag` header.
 */
class CoverResponderEtagTest :
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

        test("GET /api/v1/books/{id}/cover sets ETag and Cache-Control on a successful response") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-etag-ok-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    val jpegBytes = fakeJpeg()
                    Files.write(bookDir.resolve("cover.jpg"), jpegBytes)

                    val repo by application.inject<BookRepository>()
                    repo.upsert(coverFixture(id = "b1", hash = "abc123"))

                    val response = client.get("/api/v1/books/b1/cover") { bearerAuth(token) }

                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.ETag] shouldBe "\"abc123\""
                    response.headers[HttpHeaders.CacheControl] shouldBe
                        "private, max-age=31536000, immutable"
                    response.bodyAsBytes().toList() shouldBe jpegBytes.toList()
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/books/{id}/cover returns 304 NotModified when If-None-Match matches") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-etag-match-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b2"))
                    Files.write(bookDir.resolve("cover.jpg"), fakeJpeg())

                    val repo by application.inject<BookRepository>()
                    repo.upsert(coverFixture(id = "b2", hash = "abc123"))

                    val response =
                        client.get("/api/v1/books/b2/cover") {
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

        test("GET /api/v1/books/{id}/cover serves the full body when If-None-Match does not match") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-etag-stale-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b3"))
                    val jpegBytes = fakeJpeg()
                    Files.write(bookDir.resolve("cover.jpg"), jpegBytes)

                    val repo by application.inject<BookRepository>()
                    repo.upsert(coverFixture(id = "b3", hash = "abc123"))

                    val response =
                        client.get("/api/v1/books/b3/cover") {
                            bearerAuth(token)
                            header(HttpHeaders.IfNoneMatch, "\"different\"")
                        }

                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.ETag] shouldBe "\"abc123\""
                    response.bodyAsBytes().toList() shouldBe jpegBytes.toList()
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

private fun fakeJpeg(): ByteArray =
    byteArrayOf(
        0xFF.toByte(),
        0xD8.toByte(),
        0xFF.toByte(),
        0xE0.toByte(),
        0x00,
        0x10,
        'J'.code.toByte(),
        'F'.code.toByte(),
    )

/** Filesystem-cover book payload with a caller-supplied coverHash. */
private fun coverFixture(
    id: String,
    hash: String,
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
        cover = CoverPayload(source = CoverSource.FILESYSTEM, hash = hash),
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
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
