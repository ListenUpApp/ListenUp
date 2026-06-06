package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.LibraryFolderSyncPayload
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.LibraryFolderRepository
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.sse.ServerSentEvent
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject

/**
 * Proves the `library_folders` sync domain is admin-only across every firehose surface —
 * catch-up, digest, and the live SSE tail. Its rows carry absolute server filesystem paths
 * (operator disk topology), so a plain member must never receive them; an admin sees them all.
 *
 * Sibling to [BookCatchUpAccessTest] / [BooksDigestRouteAccessTest] (per-row book gating) and
 * [BooksSyncFirehoseTest] (live tail). Here the gate is whole-domain by role, so the live tail
 * and REST replay agree on "members see nothing on library_folders".
 */
class LibraryFolderSyncAccessTest :
    FunSpec({

        test("library_folders catch-up returns folders to an admin but nothing to a member") {
            withFolderSyncApp { client, admin, member ->
                seedTestLibraryAndFolder()

                // The bootstrap library folder (revision > 0) is visible to the admin on a since=0 pull.
                val adminPage: Page<LibraryFolderSyncPayload> =
                    client.get("/api/v1/sync/library_folders?since=0") { bearerAuth(admin) }.body()
                adminPage.items.shouldNotBeEmpty()

                val memberPage: Page<LibraryFolderSyncPayload> =
                    client.get("/api/v1/sync/library_folders?since=0") { bearerAuth(member) }.body()
                memberPage.items.shouldBeEmpty()
            }
        }

        test("library_folders digest folds the domain for an admin but is empty for a member") {
            withFolderSyncApp { client, admin, member ->
                seedTestLibraryAndFolder()
                val cursor = 1_000_000L

                val adminDigest: DomainDigest =
                    client.get("/api/v1/sync/library_folders/digest?cursor=$cursor") { bearerAuth(admin) }.body()
                val memberDigest: DomainDigest =
                    client.get("/api/v1/sync/library_folders/digest?cursor=$cursor") { bearerAuth(member) }.body()

                (adminDigest.count >= 1) shouldBe true
                memberDigest.count shouldBe 0
            }
        }

        test("live firehose delivers a library_folders event to an admin") {
            withFolderSyncApp { client, admin, _ ->
                seedTestLibraryAndFolder()
                val folders by application.inject<LibraryFolderRepository>()
                val books by application.inject<BookRepository>()

                client.sse(urlString = "/api/v1/sync/events", request = { bearerAuth(admin) }) {
                    coroutineScope {
                        // `incoming` is cold and reads a non-replayable channel that closes on first
                        // termination, so relay it once into a replaying SharedFlow that both the
                        // readiness warm-up and the assertion observe. The relay is cancelled at the
                        // end so this `coroutineScope` can complete.
                        val frames = MutableSharedFlow<ServerSentEvent>(replay = Int.MAX_VALUE)
                        val relay = launch { incoming.collect { frames.emit(it) } }

                        val deferred = async { frames.first { it.event == "library_folders" } }
                        awaitFirehoseLive(frames, books)
                        folders.upsert(folderFixture("live-folder"))
                        val event = deferred.await()
                        event.event shouldBe "library_folders"

                        relay.cancel()
                    }
                }
            }
        }

        test("live firehose withholds library_folders events from a member") {
            withFolderSyncApp { client, _, member ->
                seedTestLibraryAndFolder()
                val folders by application.inject<LibraryFolderRepository>()
                val books by application.inject<BookRepository>()

                client.sse(urlString = "/api/v1/sync/events", request = { bearerAuth(member) }) {
                    coroutineScope {
                        // `incoming` is cold and reads a non-replayable channel that closes on first
                        // termination, so relay it once into a replaying SharedFlow that both the
                        // readiness warm-up and the assertion observe. The relay is cancelled at the
                        // end so this `coroutineScope` can complete.
                        val frames = MutableSharedFlow<ServerSentEvent>(replay = Int.MAX_VALUE)
                        val relay = launch { incoming.collect { frames.emit(it) } }

                        val warmupId = awaitFirehoseLive(frames, books)
                        // After the warm-up frame proves the stream is live, the next folder-or-book
                        // frame must be the public sentinel: if the gate works, the intervening hidden
                        // folder event is dropped for the member and never appears on the stream.
                        val deferred =
                            async {
                                frames
                                    .filter { it.event == "library_folders" || it.event == "books" }
                                    .first { it.data?.contains(warmupId) != true }
                            }
                        folders.upsert(folderFixture("hidden-folder"))
                        books.upsert(publicBookFixture("sentinel-book"))
                        val event = deferred.await()
                        event.event shouldBe "books"

                        relay.cancel()
                    }
                }
            }
        }
    })

/**
 * Boots the full server, mints a ROOT token and registers a MEMBER, then runs [block] with
 * both tokens inside the `testApplication` receiver (so `application`, `seedTestLibraryAndFolder`,
 * and the SSE/HTTP client are all in scope).
 */
private fun withFolderSyncApp(
    block: suspend ApplicationTestBuilder.(client: HttpClient, admin: String, member: String) -> Unit,
) {
    val libraryRoot = Files.createTempDirectory("listenup-library-folder-access-")
    try {
        testApplication {
            useIsolatedTestConfig(libraryPath = libraryRoot.toString())
            application { module() }
            val client =
                createClient {
                    install(ContentNegotiation) { json(contractJson) }
                    install(SSE)
                }
            val admin = client.mintRootToken()
            val member = client.registerMember()
            block(client, admin, member)
        }
    } finally {
        libraryRoot.toFile().deleteRecursively()
    }
}

private suspend fun HttpClient.mintRootToken(): String =
    post("/api/v1/auth/setup") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
    }.body<AppResult<AuthSession>>()
        .let { it as AppResult.Success<AuthSession> }
        .data.accessToken.value

private suspend fun HttpClient.registerMember(): String =
    post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("member@x", "y".repeat(8), "Member"))
    }.body<AppResult<RegisterResult>>()
        .let { it as AppResult.Success<RegisterResult> }
        .data
        .let { it as RegisterResult.Authenticated }
        .session.accessToken.value

private fun folderFixture(id: String): LibraryFolderSyncPayload =
    LibraryFolderSyncPayload(
        id = id,
        libraryId = "test-library",
        rootPath = "/srv/audiobooks/$id",
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

private fun publicBookFixture(id: String): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = id,
        sortTitle = id,
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

/**
 * Returns the warm-up book's id once the live firehose subscription backing this SSE stream is
 * proven live: publishes a unique public warm-up book and waits until its `books` frame is observed
 * on [frames]. Call this before publishing the events a test asserts on, to close the
 * subscribe-before-publish race. The returned id lets the caller skip the warm-up frame when
 * asserting on the next event.
 */
private suspend fun awaitFirehoseLive(
    frames: Flow<ServerSentEvent>,
    books: BookRepository,
): String {
    // The id propagates into `af-$id` / `ch-$id` child rows; all three columns are varchar(36),
    // so strip the UUID dashes (32 chars) to keep the prefixed children inside the limit.
    val warmupId = UUID.randomUUID().toString().replace("-", "")
    books.upsert(publicBookFixture(warmupId))
    frames.first { it.event == "books" && it.data?.contains(warmupId) == true }
    return warmupId
}
