package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import org.koin.ktor.ext.inject
import java.nio.file.Files

/**
 * End-to-end reachability test for [BookService] over the authed kotlinx.rpc surface.
 *
 * Boots the full `Application.module()` via [testApplication], seeds a book through
 * the real [BookRepository], mints a JWT via the auth REST surface, connects a real
 * kotlinx.rpc [BookService] proxy to `/api/rpc/authed`, and asserts [BookService.getBook]
 * returns the expected aggregate. This proves the server-side registration is wired:
 * the path was a dead end before [registerService]<[BookService]> was added to the
 * authed RPC block.
 */
class BookServiceRpcTest :
    FunSpec({

        /**
         * Seeds the root user and returns the access token by walking the real auth REST
         * surface — mirrors the pattern in [BookRoutesTest].
         */
        suspend fun HttpClient.mintAccessToken(): String {
            post("/api/v1/auth/setup") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("root@rpc-test.example", "x".repeat(8), "Root"))
            }
            return post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("root@rpc-test.example", "x".repeat(8)))
            }.body<AppResult<AuthSession>>()
                .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                .data
                .accessToken
                .value
        }

        test("BookService.getBook is reachable over the authed RPC surface and returns the seeded book") {
            val libraryRoot = Files.createTempDirectory("listenup-book-rpc-test-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    // Mint JWT via REST — same pattern as BookRoutesTest.
                    val restClient = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = restClient.mintAccessToken()

                    // Seed the book directly through the Koin-resolved repository.
                    val repo by application.inject<BookRepository>()
                    repo.upsert(bookRpcFixture(id = "rpc-b1", title = "The Final Empire"))

                    // Connect an RPC client to the authed surface with the bearer token.
                    val rpcClient =
                        createClient {
                            install(WebSockets)
                            installKrpc()
                        }

                    val service =
                        rpcClient
                            .rpc("ws://localhost/api/rpc/authed") {
                                rpcConfig { serialization { json(contractJson) } }
                                bearerAuth(token)
                            }.withService<BookService>()

                    val result = service.getBook(BookId("rpc-b1"))

                    val success = result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    success.data.id shouldBe "rpc-b1"
                    success.data.title shouldBe "The Final Empire"
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("BookService.searchBooks is reachable over the authed RPC surface and returns matching ids") {
            val libraryRoot = Files.createTempDirectory("listenup-book-rpc-search-test-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    val restClient = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = restClient.mintAccessToken()

                    val repo by application.inject<BookRepository>()
                    repo.upsert(bookRpcFixture(id = "rpc-s1", title = "The Well of Ascension"))
                    repo.upsert(bookRpcFixture(id = "rpc-s2", title = "The Hero of Ages", rootRelPath = "books/rpc-s2"))

                    val rpcClient =
                        createClient {
                            install(WebSockets)
                            installKrpc()
                        }

                    val service =
                        rpcClient
                            .rpc("ws://localhost/api/rpc/authed") {
                                rpcConfig { serialization { json(contractJson) } }
                                bearerAuth(token)
                            }.withService<BookService>()

                    val result = service.searchBooks("Ascension", limit = 10)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookId>>>()
                    success.data shouldBe listOf(BookId("rpc-s1"))
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("BookService RPC call without a bearer token is rejected") {
            val libraryRoot = Files.createTempDirectory("listenup-book-rpc-unauth-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    val rpcClient =
                        createClient {
                            install(WebSockets)
                            installKrpc()
                        }

                    // The authed RPC mount is behind JWT_PROVIDER; the Ktor auth gate rejects
                    // the WebSocket upgrade with HTTP 401 before the RPC handshake completes,
                    // so kotlinx.rpc propagates the failure as a thrown exception rather than
                    // returning an AppResult. shouldThrow asserts the exception genuinely
                    // escapes — if the auth gate were bypassed and getBook returned
                    // AppResult.Success the test would fail because no exception is thrown.
                    shouldThrow<Exception> {
                        rpcClient
                            .rpc("ws://localhost/api/rpc/authed") {
                                rpcConfig { serialization { json(contractJson) } }
                                // No bearerAuth — intentionally unauthenticated.
                            }.withService<BookService>()
                            .getBook(BookId("rpc-b1"))
                    }
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

private fun bookRpcFixture(
    id: String,
    title: String,
    rootRelPath: String = "books/$id",
): BookSyncPayload =
    BookSyncPayload(
        id = id,
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
        contributors =
            listOf(
                BookContributorPayload(
                    id = "c-$id",
                    name = "Brandon Sanderson",
                    sortName = "Sanderson, Brandon",
                    role = "author",
                    creditedAs = null,
                ),
            ),
        series = listOf(BookSeriesPayload(id = "s-$id", name = "Mistborn", sequence = "1")),
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
