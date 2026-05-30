package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.ktor.ext.inject
import java.nio.file.Files

/**
 * Tier-2 integration tests: the SSE firehose gates each live `books` event
 * through [com.calypsan.listenup.server.api.BookAccessPolicy] for the
 * subscriber's `(userId, role)`.
 *
 * Boots the full [module] so the connection carries a *real* JWT whose `role`
 * claim drives the gate — a ROOT token bypasses the filter, a MEMBER token is
 * constrained by the policy. A private book (in a stranger-owned private
 * collection) must never reach a member's stream; an uncollected book (public
 * by default) must. An admin sees both.
 *
 * **Harness: `testApplication`, matching [com.calypsan.listenup.server.api.SeamLeakE2ETest].**
 * `testApplication`'s in-memory client↔server transport runs coherently under the
 * test coroutine scope — unlike a real embedded socket server, whose real network
 * I/O can't advance the test clock and surfaces as `UncompletedCoroutinesError` on
 * slow/loaded CI. The member tests additionally carry `.config(timeout = 2.minutes)`
 * — the same mitigation `SeamLeakE2ETest` uses for the identical member-firehose-drop
 * assertion (proven green on CI), since the member path drives a real
 * `BookAccessPolicy.canAccess` DB transaction inside the firehose collector
 * (ROOT/ADMIN bypass the probe, so the admin test needs no bump).
 *
 * Uses the `coroutineScope { async { incoming... }; <mutate>; await() }` pattern —
 * no busy-wait, no Kotest `eventually`.
 */
class FirehoseBookAccessTest :
    FunSpec({

        test("firehose drops a private book event for a member").config(timeout = 2.minutes) {
            val libraryRoot = Files.createTempDirectory("listenup-fh-access-private-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = sseClient()

                    client.mintRootToken()
                    val memberToken = client.registerMember()
                    seedTestLibraryAndFolder()
                    val books by application.inject<BookRepository>()
                    val db by application.inject<Database>()
                    val collections by application.inject<CollectionRepository>()
                    val memberships by application.inject<CollectionBookRepository>()

                    // private-book lives only in a stranger-owned private collection → denied to the member.
                    // Seed the parent book row directly (no bus event) to satisfy the
                    // collection_books FK before the SSE-observed upsert produces the gated event.
                    db.seedTestBook("private-book")
                    collections.upsert(collectionFixture("private-col", owner = "stranger"))
                    memberships.upsert(membership("private-col", "private-book"))

                    client.sse(
                        urlString = "/api/v1/sync/events",
                        request = { bearerAuth(memberToken) },
                    ) {
                        coroutineScope {
                            // The first books event the member sees must be the public one — never
                            // the private one. If the gate leaks, "private-book" arrives first and fails.
                            val deferred =
                                async { incoming.first { it.event == "books" } }
                            books.upsert(bookSyncFixture(id = "private-book", title = "Secret"))
                            books.upsert(bookSyncFixture(id = "public-book", title = "Open"))
                            val event = deferred.await()

                            event.event shouldBe "books"
                            event.data!!.contains(""""id":"public-book"""") shouldBe true
                            event.data!!.contains(""""id":"private-book"""") shouldBe false
                        }
                    }
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("firehose delivers uncollected/accessible book events to a member").config(timeout = 2.minutes) {
            val libraryRoot = Files.createTempDirectory("listenup-fh-access-public-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = sseClient()

                    client.mintRootToken()
                    val memberToken = client.registerMember()
                    seedTestLibraryAndFolder()
                    val books by application.inject<BookRepository>()

                    client.sse(
                        urlString = "/api/v1/sync/events",
                        request = { bearerAuth(memberToken) },
                    ) {
                        coroutineScope {
                            val deferred =
                                async { incoming.first { it.event == "books" } }
                            // Uncollected book = public by default → must reach the member.
                            books.upsert(bookSyncFixture(id = "loose-book", title = "Loose"))
                            val event = deferred.await()

                            event.event shouldBe "books"
                            event.data!!.contains(""""id":"loose-book"""") shouldBe true
                        }
                    }
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("firehose delivers all book events to an admin") {
            val libraryRoot = Files.createTempDirectory("listenup-fh-access-admin-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = sseClient()

                    // The root token from setup is an admin → bypasses the gate.
                    val rootToken = client.mintRootToken()
                    seedTestLibraryAndFolder()
                    val books by application.inject<BookRepository>()
                    val db by application.inject<Database>()
                    val collections by application.inject<CollectionRepository>()
                    val memberships by application.inject<CollectionBookRepository>()

                    // A book a member could never see still reaches the admin.
                    db.seedTestBook("private-book")
                    collections.upsert(collectionFixture("private-col", owner = "stranger"))
                    memberships.upsert(membership("private-col", "private-book"))

                    client.sse(
                        urlString = "/api/v1/sync/events",
                        request = { bearerAuth(rootToken) },
                    ) {
                        coroutineScope {
                            val deferred =
                                async {
                                    incoming.first {
                                        it.event == "books" && it.data!!.contains(""""id":"private-book"""")
                                    }
                                }
                            books.upsert(bookSyncFixture(id = "private-book", title = "Secret"))
                            val event = deferred.await()

                            event.event shouldBe "books"
                            event.data!!.contains(""""id":"private-book"""") shouldBe true
                        }
                    }
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

private fun ApplicationTestBuilder.sseClient(): HttpClient =
    createClient {
        install(ContentNegotiation) { json(contractJson) }
        install(SSE)
    }

/** Runs first-user setup and returns the ROOT access token. */
private suspend fun HttpClient.mintRootToken(): String {
    val response =
        post("/api/v1/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
        }
    return response
        .body<AppResult<AuthSession>>()
        .let { it as AppResult.Success<AuthSession> }
        .data
        .accessToken
        .value
}

/** Registers a second user (MEMBER role under OPEN policy) and returns their access token. */
private suspend fun HttpClient.registerMember(): String {
    val response =
        post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("member@x", "y".repeat(8), "Member"))
        }
    val result =
        response
            .body<AppResult<RegisterResult>>()
            .let { it as AppResult.Success<RegisterResult> }
            .data
    return (result as RegisterResult.Authenticated).session.accessToken.value
}

private fun collectionFixture(
    id: String,
    owner: String,
): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        ownerId = owner,
        name = id,
        isInbox = false,
        isGlobalAccess = false,
        revision = 0L,
        updatedAt = 0L,
    )

private fun membership(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )

private fun bookSyncFixture(
    id: String,
    title: String,
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
