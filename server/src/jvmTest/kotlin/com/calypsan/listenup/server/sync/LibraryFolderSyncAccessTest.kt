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
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.LibraryFolderSyncPayload
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.api.SystemCollectionType
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.LibraryFolderRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.testing.domainFrames
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.rpcFirehose
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import org.koin.ktor.ext.inject

/**
 * Proves the `library_folders` sync domain is admin-only across every firehose surface —
 * catch-up, digest, and the live RPC tail ([rpcFirehose] over the app's real [ChangeBus]).
 * Its rows carry absolute server filesystem paths (operator disk topology), so a plain member
 * must never receive them; an admin sees them all.
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
                    client.get("/api/v1/sync/library_folders?since=0") { bearerAuth(admin.token) }.body()
                adminPage.items.shouldNotBeEmpty()

                val memberPage: Page<LibraryFolderSyncPayload> =
                    client.get("/api/v1/sync/library_folders?since=0") { bearerAuth(member.token) }.body()
                memberPage.items.shouldBeEmpty()
            }
        }

        test("library_folders digest folds the domain for an admin but is empty for a member") {
            withFolderSyncApp { client, admin, member ->
                seedTestLibraryAndFolder()
                val cursor = 1_000_000L

                val adminDigest: DomainDigest =
                    client.get("/api/v1/sync/library_folders/digest?cursor=$cursor") { bearerAuth(admin.token) }.body()
                val memberDigest: DomainDigest =
                    client.get("/api/v1/sync/library_folders/digest?cursor=$cursor") { bearerAuth(member.token) }.body()

                (adminDigest.count >= 1) shouldBe true
                memberDigest.count shouldBe 0
            }
        }

        test("live firehose delivers a library_folders event to an admin") {
            withFolderSyncApp { _, admin, _ ->
                seedTestLibraryAndFolder()
                val folders by application.inject<LibraryFolderRepository>()
                val bus by application.inject<ChangeBus>()

                // Mutate-first-then-collect: the bus's replay buffer serves the published event
                // to the subscriber deterministically (see BooksSyncFirehoseTest).
                folders.upsert(folderFixture("live-folder"))

                val frame =
                    rpcFirehose(bus, rootPrincipal(admin.userId))
                        .domainFrames()
                        .filter { it.domain == "library_folders" }
                        .first { it.json.contains("live-folder") }

                frame.domain shouldBe "library_folders"
            }
        }

        test("live firehose withholds library_folders events from a member") {
            withFolderSyncApp { _, _, member ->
                seedTestLibraryAndFolder()
                val folders by application.inject<LibraryFolderRepository>()
                val books by application.inject<BookRepository>()
                val bus by application.inject<ChangeBus>()
                val policy by application.inject<BookAccessPolicy>()

                // Pre-stage the control book PUBLIC (pure-union: an uncollected book is invisible,
                // so the sentinel must live in the bootstrap library's ALL_BOOKS, which the member
                // reaches through their registration-time grant). Mirrors SeamLeakE2ETest SEAM-6.
                books.upsert(publicBookFixture("sentinel-book"))
                makeBookPublic("sentinel-book")

                // Publish the hidden folder, then a content-changed book re-upsert that fires a
                // live `books` event the member CAN access — it bounds the collection window.
                folders.upsert(folderFixture("hidden-folder"))
                books.upsert(publicBookFixture("sentinel-book", title = "Sentinel Updated"))

                // Everything the gate let through up to the sentinel books frame must be free of
                // library_folders — the hidden folder event is withheld for members.
                val delivered = mutableListOf<SyncFrame>()
                rpcFirehose(bus, memberPrincipal(member.userId), bookAccessPolicy = { policy })
                    .domainFrames()
                    .filter { it.domain == "library_folders" || it.domain == "books" }
                    .onEach { delivered += it }
                    .first { it.json.contains("Sentinel Updated") }

                delivered.none { it.domain == "library_folders" } shouldBe true
            }
        }
    })

/** A test user's REST bearer token plus the user id its principal maps to on the RPC firehose. */
private data class TestUser(
    val token: String,
    val userId: String,
)

/**
 * Boots the full server, mints a ROOT user and registers a MEMBER, then runs [block] with both
 * users inside the `testApplication` receiver (so `application`, `seedTestLibraryAndFolder`,
 * and the HTTP client are all in scope).
 */
private fun withFolderSyncApp(
    block: suspend ApplicationTestBuilder.(client: HttpClient, admin: TestUser, member: TestUser) -> Unit,
) {
    val libraryRoot = Files.createTempDirectory("listenup-library-folder-access-")
    try {
        testApplication {
            useIsolatedTestConfig(libraryPath = libraryRoot.toString())
            application { module() }
            val client =
                createClient {
                    install(ContentNegotiation) { json(contractJson) }
                }
            val admin = client.mintRoot()
            val member = client.registerMember()
            block(client, admin, member)
        }
    } finally {
        libraryRoot.toFile().deleteRecursively()
    }
}

private suspend fun HttpClient.mintRoot(): TestUser =
    post("/api/v1/auth/setup") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
    }.body<AppResult<AuthSession>>()
        .let { it as AppResult.Success<AuthSession> }
        .data
        .let { TestUser(token = it.accessToken.value, userId = it.user.id.value) }

private suspend fun HttpClient.registerMember(): TestUser =
    post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("member@x", "y".repeat(8), "Member"))
    }.body<AppResult<RegisterResult>>()
        .let { it as AppResult.Success<RegisterResult> }
        .data
        .let { it as RegisterResult.Authenticated }
        .session
        .let { TestUser(token = it.accessToken.value, userId = it.user.id.value) }

/**
 * Makes [bookId] visible to every registered member the pure-union way: drops it into the bootstrap
 * library's `ALL_BOOKS` substrate, which every member reaches through the default `ALL_BOOKS` grant
 * issued at registration. No explicit grant is issued (members already hold one; a duplicate would
 * violate the active-grant unique index). Mirrors `SeamLeakE2ETest.makeBookPublic`.
 */
private suspend fun ApplicationTestBuilder.makeBookPublic(bookId: String) {
    val collectionService by application.inject<CollectionServiceImpl>()
    val registry by application.inject<LibraryRegistry>()
    val collectionBookRepo by application.inject<CollectionBookRepository>()
    val libraryId = registry.currentLibrary().value
    val allBooks =
        collectionService.getOrCreateSystemCollection(libraryId, SystemCollectionType.ALL_BOOKS) as AppResult.Success
    require(
        collectionBookRepo.upsert(
            CollectionBookSyncPayload(
                id = "${allBooks.data.id.value}:$bookId",
                collectionId = allBooks.data.id.value,
                bookId = bookId,
                createdAt = 0L,
                revision = 0L,
            ),
        ) is AppResult.Success,
    ) { "failed to add $bookId to ALL_BOOKS" }
}

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

private fun publicBookFixture(
    id: String,
    title: String = id,
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
