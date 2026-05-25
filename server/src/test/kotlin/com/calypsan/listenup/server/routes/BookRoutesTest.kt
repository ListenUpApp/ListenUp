package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.koin.ktor.ext.inject
import java.nio.file.Files

/**
 * Integration tests for `GET /api/v1/books/{id}` and `GET /api/v1/books?q=&limit=`.
 *
 * Boots the full `Application.module()` with a real SQLite DB and a configured
 * library path so the books slice is installed. Seeds data through the repository
 * directly (Koin inject inside the testApplication block). JWT is minted by
 * walking the real auth REST surface.
 */
class BookRoutesTest :
    FunSpec({

        /**
         * Sets up the root account via the REST surface and returns its access token.
         * Mirrors the pattern established in AuthRoutesLoginTest and BooksModuleStartupTest.
         */
        suspend fun HttpClient.mintAccessToken(): String {
            post("/api/v1/auth/setup") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
            }
            val response =
                post("/api/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("root@x", "x".repeat(8)))
                }
            return response
                .body<AppResult<AuthSession>>()
                .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                .data
                .accessToken
                .value
        }

        test("GET /api/v1/books/{id} returns 200 with the book payload for a seeded book") {
            val libraryRoot = Files.createTempDirectory("listenup-book-routes-test-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val token = client.mintAccessToken()
                    seedTestLibraryAndFolder()

                    val repo by application.inject<BookRepository>()
                    repo.upsert(bookRouteFixture(id = "b1", title = "The Way of Kings"))

                    val response =
                        client.get("/api/v1/books/b1") {
                            bearerAuth(token)
                        }

                    response.status shouldBe HttpStatusCode.OK
                    response.body<BookSyncPayload>().title shouldBe "The Way of Kings"
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/books/{id} returns 404 for a missing book") {
            val libraryRoot = Files.createTempDirectory("listenup-book-routes-missing-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val token = client.mintAccessToken()

                    val response =
                        client.get("/api/v1/books/nonexistent") {
                            bearerAuth(token)
                        }

                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/books returns 401 without a bearer token") {
            val libraryRoot = Files.createTempDirectory("listenup-book-routes-unauth-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val response = client.get("/api/v1/books/b1")

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/books?q=&limit= returns 200 with matching book ids") {
            val libraryRoot = Files.createTempDirectory("listenup-book-search-test-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val token = client.mintAccessToken()
                    seedTestLibraryAndFolder()

                    val repo by application.inject<BookRepository>()
                    repo.upsert(bookRouteFixture(id = "b1", title = "The Way of Kings"))
                    repo.upsert(bookRouteFixture(id = "b2", title = "Words of Radiance", rootRelPath = "books/b2"))
                    repo.upsert(bookRouteFixture(id = "b3", title = "Mistborn", rootRelPath = "books/b3"))

                    val response =
                        client.get("/api/v1/books?q=Kings&limit=10") {
                            bearerAuth(token)
                        }

                    response.status shouldBe HttpStatusCode.OK
                    val ids = response.body<List<BookId>>()
                    ids shouldContain BookId("b1")
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/books?q= hammered past 60 requests returns 429 TooManyRequests") {
            val libraryRoot = Files.createTempDirectory("listenup-book-ratelimit-test-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val token = client.mintAccessToken()

                    repeat(BOOKS_SEARCH_BUCKET_LIMIT) {
                        client.get("/api/v1/books?q=x") {
                            bearerAuth(token)
                        }
                    }

                    val response =
                        client.get("/api/v1/books?q=x") {
                            bearerAuth(token)
                        }

                    response.status shouldBe HttpStatusCode.TooManyRequests
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

private const val BOOKS_SEARCH_BUCKET_LIMIT = 60

private fun bookRouteFixture(
    id: String,
    title: String,
    rootRelPath: String = "books/$id",
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        sortTitle = title,
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
        rootRelPath = rootRelPath,
        inode = null,
        scannedAt = 1_730_000_000_000L,
        // Contributors/series left empty: these tests assert only on book
        // identity and FTS search, not the aggregate's child rows. Junction-row
        // writes require pre-resolved catalogue ids (see
        // BookRepository.replaceContributors).
        contributors = emptyList(),
        series = emptyList(),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af-$id",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 3_600_000L,
                    size = 500_000_000L,
                ),
            ),
        chapters =
            listOf(
                BookChapterPayload(id = "ch-$id", title = "Prologue", duration = 1_000_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
