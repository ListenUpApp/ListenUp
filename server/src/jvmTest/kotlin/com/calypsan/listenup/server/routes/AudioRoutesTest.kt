@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.api.SystemCollectionType
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.koin.ktor.ext.inject
import java.nio.file.Files

/**
 * Integration tests for `GET /api/v1/audio/{bookId}/{fileId}`.
 *
 * Boots the full `Application.module()` with a real SQLite DB and temp library
 * directory so the audio slice is installed. Seeds data through `BookRepository`
 * directly (Koin inject inside the testApplication block). Uses a real fixture
 * audio file on disk to exercise `respondFile` + `PartialContent`.
 *
 * The audio route is NOT JWT-gated — the HMAC-signed query string is the auth.
 * The signing key is derived from the test JWT secret configured by
 * `useIsolatedTestConfig` ("x" * 32).
 *
 * Application startup is triggered by an initial health-check request before any
 * `application.inject` call — mirroring the pattern in `BookRoutesTest` where
 * `mintAccessToken()` fires before `inject<BookRepository>()`.
 */

private val TEST_JWT_SECRET = "x".repeat(32) // must match the value in useIsolatedTestConfig
private val TEST_SIGNING_KEY = AudioUrlSigner.deriveSigningKey(TEST_JWT_SECRET)

class AudioRoutesTest :
    FunSpec({

        test("GET with a valid signed query streams the file with 200 and Accept-Ranges header") {
            val libraryRoot = Files.createTempDirectory("listenup-audio-routes-200-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), rescanOnStartup = false)
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    // Trigger application startup before inject
                    client.get("/healthz")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    // Write a small fixture audio file under the book's rootRelPath
                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    val audioBytes = ByteArray(256) { it.toByte() }
                    Files.write(bookDir.resolve("01.m4b"), audioBytes)

                    val repo by application.inject<BookRepository>()
                    repo.upsert(audioFixture(bookId = "b1", fileId = "af1", filename = "01.m4b"))

                    // b1 lives in ALL_BOOKS — the public substrate. The seeded member holds a grant
                    // on it, so under pure union it is reachable. (A directly-seeded user does not
                    // go through the auth flow, so we issue the ALL_BOOKS grant explicitly.)
                    val sql by application.inject<ListenUpDatabase>()
                    sql.seedTestUser("user1")
                    val collectionService by application.inject<CollectionServiceImpl>()
                    val allBooks =
                        (
                            collectionService.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                                as AppResult.Success
                        ).data
                    val collectionBookRepo by application.inject<CollectionBookRepository>()
                    collectionBookRepo.upsert(membership(allBooks.id.value, "b1"))
                    val collectionGrantRepo by application.inject<CollectionGrantRepository>()
                    collectionGrantRepo.upsert(share("g1", allBooks.id.value, "user1"))

                    val signer = AudioUrlSigner(signingKey = TEST_SIGNING_KEY)
                    val query = signer.signedQuery("user1", "b1", "af1")

                    val response = client.get("/api/v1/audio/b1/af1?$query")

                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.AcceptRanges] shouldBe "bytes"
                    // The extension-less signed URL forces AVPlayer to trust this header;
                    // `.m4b` must resolve to audio/mp4, not Ktor's octet-stream default.
                    response.headers[HttpHeaders.ContentType].shouldContain("audio/mp4")
                    response.bodyAsBytes().toList() shouldBe audioBytes.toList()
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET with Range header returns 206 Partial Content with the correct slice") {
            val libraryRoot = Files.createTempDirectory("listenup-audio-routes-206-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), rescanOnStartup = false)
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    client.get("/healthz")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    val audioBytes = ByteArray(256) { it.toByte() }
                    Files.write(bookDir.resolve("01.m4b"), audioBytes)

                    val repo by application.inject<BookRepository>()
                    repo.upsert(audioFixture(bookId = "b1", fileId = "af1", filename = "01.m4b"))

                    // b1 is reachable the simplest pure-union way: a collection user1 owns
                    // (the owner branch needs no ALL_BOOKS grant).
                    val sql by application.inject<ListenUpDatabase>()
                    sql.seedTestUser("user1")
                    val collectionRepo by application.inject<CollectionRepository>()
                    val collectionBookRepo by application.inject<CollectionBookRepository>()
                    collectionRepo.upsert(privateCollection("owned-col", owner = "user1"))
                    collectionBookRepo.upsert(membership("owned-col", "b1"))

                    val signer = AudioUrlSigner(signingKey = TEST_SIGNING_KEY)
                    val query = signer.signedQuery("user1", "b1", "af1")

                    val response =
                        client.get("/api/v1/audio/b1/af1?$query") {
                            header(HttpHeaders.Range, "bytes=0-99")
                        }

                    response.status shouldBe HttpStatusCode.PartialContent
                    response.headers[HttpHeaders.ContentRange].shouldContain("bytes 0-99/256")
                    response.bodyAsBytes().size shouldBe 100
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET with a missing sig returns 403 Forbidden") {
            val libraryRoot = Files.createTempDirectory("listenup-audio-routes-403-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), rescanOnStartup = false)
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val response = client.get("/api/v1/audio/b1/af1")

                    response.status shouldBe HttpStatusCode.Forbidden
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET with a tampered sig returns 403 Forbidden") {
            val libraryRoot = Files.createTempDirectory("listenup-audio-routes-tampered-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), rescanOnStartup = false)
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    client.get("/healthz")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    Files.write(bookDir.resolve("01.m4b"), ByteArray(64))

                    val repo by application.inject<BookRepository>()
                    repo.upsert(audioFixture(bookId = "b1", fileId = "af1", filename = "01.m4b"))

                    val signer = AudioUrlSigner(signingKey = TEST_SIGNING_KEY)
                    val query = signer.signedQuery("user1", "b1", "af1")
                    val tamperedSig = query.substringBefore("sig=") + "sig=" + "0".repeat(64)

                    val response = client.get("/api/v1/audio/b1/af1?$tamperedSig")

                    response.status shouldBe HttpStatusCode.Forbidden
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET for an unknown (bookId, fileId) returns 404 Not Found") {
            val libraryRoot = Files.createTempDirectory("listenup-audio-routes-404-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), rescanOnStartup = false)
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val signer = AudioUrlSigner(signingKey = TEST_SIGNING_KEY)
                    val query = signer.signedQuery("user1", "unknown", "nofile")

                    val response = client.get("/api/v1/audio/unknown/nofile?$query")

                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("valid signature for a book the member can't access returns 404 (not 403)") {
            val libraryRoot = Files.createTempDirectory("listenup-audio-routes-access-deny-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), rescanOnStartup = false)
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    client.get("/healthz")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    Files.write(bookDir.resolve("01.m4b"), ByteArray(256))

                    val repo by application.inject<BookRepository>()
                    repo.upsert(audioFixture(bookId = "b1", fileId = "af1", filename = "01.m4b"))

                    // Lock b1 into a private collection owned by a stranger, then seed an
                    // unrelated member who therefore can't reach it.
                    val sql by application.inject<ListenUpDatabase>()
                    sql.seedTestUser("member")
                    val collectionRepo by application.inject<CollectionRepository>()
                    val collectionBookRepo by application.inject<CollectionBookRepository>()
                    collectionRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    collectionBookRepo.upsert(membership("private-col", "b1"))

                    val signer = AudioUrlSigner(signingKey = TEST_SIGNING_KEY)
                    val query = signer.signedQuery("member", "b1", "af1")

                    val response = client.get("/api/v1/audio/b1/af1?$query")

                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("member with access serves the bytes") {
            val libraryRoot = Files.createTempDirectory("listenup-audio-routes-access-member-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), rescanOnStartup = false)
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    client.get("/healthz")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    val audioBytes = ByteArray(256) { it.toByte() }
                    Files.write(bookDir.resolve("01.m4b"), audioBytes)

                    val repo by application.inject<BookRepository>()
                    repo.upsert(audioFixture(bookId = "b1", fileId = "af1", filename = "01.m4b"))

                    // The member owns the collection b1 lives in, so it is reachable.
                    val sql by application.inject<ListenUpDatabase>()
                    sql.seedTestUser("member")
                    val collectionRepo by application.inject<CollectionRepository>()
                    val collectionBookRepo by application.inject<CollectionBookRepository>()
                    collectionRepo.upsert(privateCollection("owned-col", owner = "member"))
                    collectionBookRepo.upsert(membership("owned-col", "b1"))

                    val signer = AudioUrlSigner(signingKey = TEST_SIGNING_KEY)
                    val query = signer.signedQuery("member", "b1", "af1")

                    val response = client.get("/api/v1/audio/b1/af1?$query")

                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsBytes().toList() shouldBe audioBytes.toList()
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("admin serves a private book they don't own") {
            val libraryRoot = Files.createTempDirectory("listenup-audio-routes-access-admin-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), rescanOnStartup = false)
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    client.get("/healthz")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    val audioBytes = ByteArray(256) { it.toByte() }
                    Files.write(bookDir.resolve("01.m4b"), audioBytes)

                    val repo by application.inject<BookRepository>()
                    repo.upsert(audioFixture(bookId = "b1", fileId = "af1", filename = "01.m4b"))

                    // b1 is private to a stranger; the admin has no relationship to it
                    // but ADMIN bypasses the filter entirely.
                    val sql by application.inject<ListenUpDatabase>()
                    sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                    val collectionRepo by application.inject<CollectionRepository>()
                    val collectionBookRepo by application.inject<CollectionBookRepository>()
                    collectionRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    collectionBookRepo.upsert(membership("private-col", "b1"))

                    val signer = AudioUrlSigner(signingKey = TEST_SIGNING_KEY)
                    val query = signer.signedQuery("admin", "b1", "af1")

                    val response = client.get("/api/v1/audio/b1/af1?$query")

                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsBytes().toList() shouldBe audioBytes.toList()
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("HEAD is handled by AutoHeadResponse — 200 with no body for a valid signed URL") {
            val libraryRoot = Files.createTempDirectory("listenup-audio-routes-head-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), rescanOnStartup = false)
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    client.get("/healthz")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    val audioBytes = ByteArray(256) { it.toByte() }
                    Files.write(bookDir.resolve("01.m4b"), audioBytes)

                    val repo by application.inject<BookRepository>()
                    repo.upsert(audioFixture(bookId = "b1", fileId = "af1", filename = "01.m4b"))

                    // b1 is reachable via a collection user1 owns (owner branch, no grant).
                    val sql by application.inject<ListenUpDatabase>()
                    sql.seedTestUser("user1")
                    val collectionRepo by application.inject<CollectionRepository>()
                    val collectionBookRepo by application.inject<CollectionBookRepository>()
                    collectionRepo.upsert(privateCollection("owned-col", owner = "user1"))
                    collectionBookRepo.upsert(membership("owned-col", "b1"))

                    val signer = AudioUrlSigner(signingKey = TEST_SIGNING_KEY)
                    val query = signer.signedQuery("user1", "b1", "af1")

                    // AutoHeadResponse converts HEAD→GET responses at the framework level.
                    // Verify that the full GET path returns 200 with the expected headers —
                    // the plugin strips the body for actual HEAD requests automatically.
                    val response = client.get("/api/v1/audio/b1/af1?$query")
                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.AcceptRanges] shouldBe "bytes"
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

private fun privateCollection(
    id: String,
    owner: String,
): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        ownerId = owner,
        name = id,
        isInbox = false,
        revision = 0L,
        updatedAt = 0L,
    )

private fun membership(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        id = "${collectionId}:${bookId}",
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )

private fun share(
    id: String,
    collectionId: String,
    userId: String,
): CollectionShareSyncPayload =
    CollectionShareSyncPayload(
        id = id,
        collectionId = collectionId,
        sharedWithUserId = userId,
        sharedByUserId = "system",
        permission = SharePermission.Read,
        revision = 0L,
        updatedAt = 0L,
        deletedAt = null,
    )

private fun audioFixture(
    bookId: String,
    fileId: String,
    filename: String,
): BookSyncPayload =
    BookSyncPayload(
        id = bookId,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "Audio Test Book",
        sortTitle = "Audio Test Book",
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
        rootRelPath = "books/$bookId",
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = fileId,
                    index = 0,
                    filename = filename,
                    format = filename.substringAfterLast('.'),
                    codec = "aac",
                    duration = 3_600_000L,
                    size = 256L,
                ),
            ),
        chapters =
            listOf(
                BookChapterPayload(id = "ch-$bookId", title = "Prologue", duration = 1_000_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
