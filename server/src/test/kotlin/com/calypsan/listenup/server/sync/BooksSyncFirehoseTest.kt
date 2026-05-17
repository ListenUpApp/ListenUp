package com.calypsan.listenup.server.sync

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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.koin.ktor.ext.inject
import java.nio.file.Files

/**
 * Tier-2 integration tests: Books SSE firehose ordering via
 * `GET /api/v1/sync/events`.
 *
 * Verifies that the Books domain emits the correct sequence of
 * `SyncEvent.Created`, `SyncEvent.Updated`, and `SyncEvent.Deleted`
 * events on the firehose, in revision order, with `event: books` framing.
 *
 * Boots the full [module] (same approach as
 * [com.calypsan.listenup.server.routes.BookRoutesTest]) because
 * [BookRepository] requires `booksModule` + `scannerModule`. Sync routes
 * are auth-gated, so SSE connections carry a JWT minted via the auth surface.
 * Uses the `coroutineScope { async { incoming... }; <mutate>; await() }` pattern
 * from [SyncFirehoseTest] — no Kotest `eventually`, no busy-wait.
 */
class BooksSyncFirehoseTest :
    FunSpec({

        test("firehose emits Created then Updated then Deleted for the books domain in revision order") {
            val libraryRoot = Files.createTempDirectory("listenup-books-firehose-test-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client =
                        createClient {
                            install(ContentNegotiation) { json(contractJson) }
                            install(io.ktor.client.plugins.sse.SSE)
                        }

                    val token = client.mintAccessToken()
                    val repo by application.inject<BookRepository>()

                    val events = mutableListOf<ServerSentEvent>()

                    client.sse(
                        urlString = "/api/v1/sync/events",
                        request = { bearerAuth(token) },
                    ) {
                        coroutineScope {
                            // Collect exactly 3 events: Created, Updated, Deleted
                            val deferred = async { incoming.take(3).toList() }

                            // Create
                            repo.upsert(bookSyncFixture(id = "fh-book", title = "Original Title"))
                            // Update (same id → Updated)
                            repo.upsert(bookSyncFixture(id = "fh-book", title = "Updated Title"))
                            // Delete
                            repo.softDelete(BookId("fh-book"))

                            events += deferred.await()
                        }
                    }

                    // All three events must be on the "books" domain
                    events.forEach { event -> event.event shouldBe "books" }

                    // Revision ids must increase monotonically
                    val revisions = events.map { it.id!!.toLong() }
                    revisions shouldBe revisions.sorted()

                    // Event type ordering: Created → Updated → Deleted
                    events[0].data!! shouldContain """"type":"SyncEvent.Created""""
                    events[1].data!! shouldContain """"type":"SyncEvent.Updated""""
                    events[2].data!! shouldContain """"type":"SyncEvent.Deleted""""

                    // Created and Updated carry the book id
                    events[0].data!! shouldContain """"id":"fh-book""""
                    events[1].data!! shouldContain """"id":"fh-book""""
                    events[2].data!! shouldContain """"id":"fh-book""""
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("firehose emits only books domain events with correct event field") {
            val libraryRoot = Files.createTempDirectory("listenup-books-firehose-domain-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client =
                        createClient {
                            install(ContentNegotiation) { json(contractJson) }
                            install(io.ktor.client.plugins.sse.SSE)
                        }

                    val token = client.mintAccessToken()
                    val repo by application.inject<BookRepository>()

                    client.sse(
                        urlString = "/api/v1/sync/events",
                        request = { bearerAuth(token) },
                    ) {
                        coroutineScope {
                            val deferred = async { incoming.first() }
                            repo.upsert(bookSyncFixture(id = "domain-book", title = "Domain Test"))
                            val event = deferred.await()

                            event.event shouldBe "books"
                            event.id shouldBe "1" // first revision across all domains
                            event.data!! shouldContain """"type":"SyncEvent.Created""""
                            event.data!! shouldContain """"id":"domain-book""""
                        }
                    }
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

private suspend fun HttpClient.mintAccessToken(): String {
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
        .let { it as AppResult.Success<AuthSession> }
        .data
        .accessToken
        .value
}

private fun bookSyncFixture(
    id: String,
    title: String,
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
        rootRelPath = "books/$id",
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
        series = listOf(BookSeriesPayload(id = "s-$id", name = "Stormlight Archive", sequence = "1")),
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
