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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * **Why a real embedded CIO server, and why a generous per-test timeout.** The
 * member path is the only one that drives a real `BookAccessPolicy.canAccess` DB
 * transaction *inside* the firehose collector (ROOT/ADMIN bypass the probe). That
 * transaction is blocking JDBC — uncancellable mid-query — so when the SSE
 * connection tears down at end-of-test it must drain, which on slow/loaded CI
 * exceeds Kotest's 1-minute default and surfaces as an `UncompletedCoroutinesError`
 * from the test-scope completeness check. The member tests therefore carry
 * `.config(timeout = 2.minutes)` — the same mitigation [com.calypsan.listenup.server.api.SeamLeakE2ETest]
 * uses for the identical member-firehose-drop assertion (proven green on CI). The
 * admin test bypasses `canAccess`, so it needs no bump.
 *
 * Uses the `coroutineScope { async { incoming... }; <mutate>; await() }`
 * pattern from [BooksSyncFirehoseTest] — no busy-wait, no Kotest `eventually`.
 */
class FirehoseBookAccessTest :
    FunSpec({

        test("firehose drops a private book event for a member").config(timeout = 2.minutes) {
            firehoseFixture().use { fx ->
                fx.client.mintRootToken(fx.baseUrl)
                val memberToken = fx.client.registerMember(fx.baseUrl)
                fx.seedLibraryAndFolder()
                val books = fx.inject<BookRepository>()
                val collections = fx.inject<CollectionRepository>()
                val memberships = fx.inject<CollectionBookRepository>()

                // private-book lives only in a stranger-owned private collection → denied to the member.
                // Seed the parent book row directly (no bus event) to satisfy the
                // collection_books FK before the SSE-observed upsert produces the gated event.
                fx.database().seedTestBook("private-book")
                collections.upsert(collectionFixture("private-col", owner = "stranger"))
                memberships.upsert(membership("private-col", "private-book"))

                fx.client.sse(
                    urlString = "${fx.baseUrl}/api/v1/sync/events",
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
        }

        test("firehose delivers uncollected/accessible book events to a member").config(timeout = 2.minutes) {
            firehoseFixture().use { fx ->
                fx.client.mintRootToken(fx.baseUrl)
                val memberToken = fx.client.registerMember(fx.baseUrl)
                fx.seedLibraryAndFolder()
                val books = fx.inject<BookRepository>()

                fx.client.sse(
                    urlString = "${fx.baseUrl}/api/v1/sync/events",
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
        }

        test("firehose delivers all book events to an admin") {
            firehoseFixture().use { fx ->
                // The root token from setup is an admin → bypasses the gate.
                val rootToken = fx.client.mintRootToken(fx.baseUrl)
                fx.seedLibraryAndFolder()
                val books = fx.inject<BookRepository>()
                val collections = fx.inject<CollectionRepository>()
                val memberships = fx.inject<CollectionBookRepository>()

                // A book a member could never see still reaches the admin.
                fx.database().seedTestBook("private-book")
                collections.upsert(collectionFixture("private-col", owner = "stranger"))
                memberships.upsert(membership("private-col", "private-book"))

                fx.client.sse(
                    urlString = "${fx.baseUrl}/api/v1/sync/events",
                    request = { bearerAuth(rootToken) },
                ) {
                    coroutineScope {
                        val deferred =
                            async { incoming.first { it.event == "books" && it.data!!.contains(""""id":"private-book"""") } }
                        books.upsert(bookSyncFixture(id = "private-book", title = "Secret"))
                        val event = deferred.await()

                        event.event shouldBe "books"
                        event.data!!.contains(""""id":"private-book"""") shouldBe true
                    }
                }
            }
        }
    })

/**
 * A real embedded CIO server on an OS-chosen port plus a real CIO [HttpClient].
 * Boots `module()` against a fresh tmp SQLite file — no test-side overrides on
 * the production graph. Runs entirely off `testApplication`'s virtual clock, so
 * the member firehose path's real `canAccess` DB I/O can't race a `runTest`
 * completeness check.
 */
private class FirehoseFixture(
    val server: EmbeddedServer<*, *>,
    val client: HttpClient,
    val baseUrl: String,
    val libraryRoot: java.nio.file.Path,
) : AutoCloseable {
    val application: Application get() = server.application

    inline fun <reified T : Any> inject(): T = application.inject<T>().value

    fun database(): Database = inject<Database>()

    fun seedLibraryAndFolder() {
        database().seedTestLibraryAndFolder(libraryId = "test-library", folderId = "test-folder")
    }

    override fun close() {
        client.close()
        @Suppress("MagicNumber")
        server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        libraryRoot.toFile().deleteRecursively()
    }
}

private fun firehoseFixture(): FirehoseFixture {
    val tmpDb = Files.createTempFile("listenup-fh-access-", ".db").toFile().apply { deleteOnExit() }
    val libraryRoot = Files.createTempDirectory("listenup-fh-access-")

    val env =
        applicationEnvironment {
            config =
                MapApplicationConfig(
                    "database.jdbcUrl" to "jdbc:sqlite:${tmpDb.absolutePath}",
                    "auth.refreshPepper" to "x".repeat(32),
                    "jwt.secret" to "x".repeat(32),
                    "jwt.issuer" to "listenup",
                    "jwt.audience" to "listenup-client",
                    "registration.policy" to "OPEN",
                    "scanner.libraryPath" to libraryRoot.toString(),
                )
        }

    val server =
        embeddedServer(
            factory = ServerCIO,
            environment = env,
            configure = {
                connectors.add(
                    EngineConnectorBuilder().apply {
                        host = "127.0.0.1"
                        port = 0
                    },
                )
            },
        ) {
            module()
        }
    server.start(wait = false)

    val resolvedPort = runBlocking { server.engine.resolvedConnectors() }.first().port
    val baseUrl = "http://127.0.0.1:$resolvedPort"

    val client =
        HttpClient(CIO) {
            install(ContentNegotiation) { json(contractJson) }
            install(SSE)
        }

    return FirehoseFixture(server, client, baseUrl, libraryRoot)
}

/** Runs first-user setup and returns the ROOT access token. */
private suspend fun HttpClient.mintRootToken(baseUrl: String): String {
    val response =
        post("$baseUrl/api/v1/auth/setup") {
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
private suspend fun HttpClient.registerMember(baseUrl: String): String {
    val response =
        post("$baseUrl/api/v1/auth/register") {
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
