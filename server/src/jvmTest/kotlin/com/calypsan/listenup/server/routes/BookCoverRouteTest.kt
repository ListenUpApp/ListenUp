package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
 * Integration tests for `GET /api/v1/books/{id}/cover`.
 *
 * Boots the full `Application.module()` with a real SQLite DB and a temp
 * library directory so the books slice is installed. Filesystem covers are
 * seeded by writing a real image file under the book's `rootRelPath`; embedded
 * covers by writing a real MP3 with an `APIC` artwork frame. JWT is minted by
 * walking the real auth REST surface.
 */
class BookCoverRouteTest :
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

        // Registers a second user under the OPEN registration policy — which yields
        // an ACTIVE MEMBER, authenticated immediately — and returns that member's
        // (accessToken, userId). The member-role JWT is what makes the route-level
        // [BookAccessPolicy] gate run for real instead of the all-bypassing ROOT
        // that [mintAccessToken]'s first-user-is-ROOT setup produces.
        suspend fun HttpClient.registerMember(email: String): Pair<String, String> {
            val session =
                post("/api/v1/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest(email, "x".repeat(8), "Member"))
                }.body<AppResult<RegisterResult>>()
                    .shouldBeInstanceOf<AppResult.Success<RegisterResult>>()
                    .data
                    .shouldBeInstanceOf<RegisterResult.Authenticated>()
                    .session
            return session.accessToken.value to session.user.id.value
        }

        test("GET /api/v1/books/{id}/cover serves a filesystem cover image with the right content type") {
            // The cover file is written *after* module() so the scanner's
            // startup scan (which runs on the then-empty library) does not
            // ingest a competing book row; the watcher's 2 s settle window
            // is far longer than this test's runtime, so it stays inert.
            val libraryRoot = Files.createTempDirectory("listenup-cover-fs-")
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
                    repo.upsert(coverFixture(id = "b1", source = CoverSource.FILESYSTEM))

                    val response = client.get("/api/v1/books/b1/cover") { bearerAuth(token) }

                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.ContentType].shouldStartWith("image/jpeg")
                    response.bodyAsBytes().toList() shouldBe jpegBytes.toList()
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/books/{id}/cover serves embedded artwork extracted from the audio file") {
            // Audio file written after module() — see the filesystem test's note.
            val libraryRoot = Files.createTempDirectory("listenup-cover-embedded-")
            try {
                val artworkBytes = fakeJpeg()
                val mp3 =
                    buildMp3File {
                        id3v2(version = 4) {
                            textFrame("TIT2", "Embedded Cover Book")
                            apicFrame(
                                mime = "image/jpeg",
                                pictureType = 3,
                                description = "Cover",
                                imageBytes = artworkBytes,
                            )
                        }
                        mpegFrames(durationSeconds = 1)
                    }

                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b2"))
                    Files.write(bookDir.resolve("01.mp3"), mp3)

                    val repo by application.inject<BookRepository>()
                    repo.upsert(
                        coverFixture(
                            id = "b2",
                            source = CoverSource.EMBEDDED,
                            audioFilename = "01.mp3",
                        ),
                    )

                    val response = client.get("/api/v1/books/b2/cover") { bearerAuth(token) }

                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.ContentType].shouldStartWith("image/jpeg")
                    response.bodyAsBytes().toList() shouldBe artworkBytes.toList()
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/books/{id}/cover returns 404 for a missing book") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-missing-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()

                    val response = client.get("/api/v1/books/nonexistent/cover") { bearerAuth(token) }

                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/books/{id}/cover returns 404 for a book with no cover") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-none-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()
                    seedTestLibraryAndFolder()

                    val repo by application.inject<BookRepository>()
                    repo.upsert(coverFixture(id = "b3", source = null))

                    val response = client.get("/api/v1/books/b3/cover") { bearerAuth(token) }

                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/books/{id}/cover returns 401 without a bearer token") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-unauth-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val response = client.get("/api/v1/books/b1/cover")

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        // ── Route-level BookAccessPolicy gate (member-deny / control / admin-bypass) ──
        // The cover handler answers 404 — not 403 — for a book the caller can't reach,
        // so the response is indistinguishable from an absent or cover-less book and
        // never leaks the existence of a private book (BookRoutes.kt:~82).

        test("GET /api/v1/books/{id}/cover returns 404 when a member can't reach a private book") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-member-deny-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    client.mintAccessToken() // first user → ROOT (seeds the instance)
                    val (memberToken, _) = client.registerMember("member@x")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    // b1 has a real, servable filesystem cover — so a 404 can only come
                    // from the access gate, never from a missing/cover-less book.
                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    Files.write(bookDir.resolve("cover.jpg"), fakeJpeg())
                    val repo by application.inject<BookRepository>()
                    repo.upsert(coverFixture(id = "b1", source = CoverSource.FILESYSTEM))

                    // b1 is locked in a private collection owned by a stranger; the member
                    // has no relationship to it, so the gate must answer 404.
                    val collectionRepo by application.inject<CollectionRepository>()
                    val collectionBookRepo by application.inject<CollectionBookRepository>()
                    collectionRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    collectionBookRepo.upsert(membership("private-col", "b1"))

                    val response = client.get("/api/v1/books/b1/cover") { bearerAuth(memberToken) }

                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/books/{id}/cover returns 200 for a member when the book is accessible") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-member-allow-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    client.mintAccessToken()
                    val (memberToken, memberId) = client.registerMember("member@x")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    val jpegBytes = fakeJpeg()
                    Files.write(bookDir.resolve("cover.jpg"), jpegBytes)
                    val repo by application.inject<BookRepository>()
                    repo.upsert(coverFixture(id = "b1", source = CoverSource.FILESYSTEM))

                    // b1 lives in a collection the member owns, so it is reachable.
                    val collectionRepo by application.inject<CollectionRepository>()
                    val collectionBookRepo by application.inject<CollectionBookRepository>()
                    collectionRepo.upsert(privateCollection("owned-col", owner = memberId))
                    collectionBookRepo.upsert(membership("owned-col", "b1"))

                    val response = client.get("/api/v1/books/b1/cover") { bearerAuth(memberToken) }

                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.ContentType].shouldStartWith("image/jpeg")
                    response.bodyAsBytes().toList() shouldBe jpegBytes.toList()
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        // ── The mobile-client cover URL: GET /api/v1/covers/{id} ──
        // The KMP client downloads covers from /api/v1/covers/{bookId}; the Kotlin server
        // only had /api/v1/books/{id}/cover, so every client cover request 404'd and covers
        // never rendered. This alias serves the same access-gated bytes at the client's URL.

        test("GET /api/v1/covers/{id} serves the book's cover with the right content type") {
            val libraryRoot = Files.createTempDirectory("listenup-covers-alias-")
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
                    repo.upsert(coverFixture(id = "b1", source = CoverSource.FILESYSTEM))

                    val response = client.get("/api/v1/covers/b1") { bearerAuth(token) }

                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.ContentType].shouldStartWith("image/jpeg")
                    response.bodyAsBytes().toList() shouldBe jpegBytes.toList()
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/covers/{id} returns 404 when a member can't reach a private book") {
            val libraryRoot = Files.createTempDirectory("listenup-covers-alias-deny-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    client.mintAccessToken()
                    val (memberToken, _) = client.registerMember("member@x")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    Files.write(bookDir.resolve("cover.jpg"), fakeJpeg())
                    val repo by application.inject<BookRepository>()
                    repo.upsert(coverFixture(id = "b1", source = CoverSource.FILESYSTEM))

                    val collectionRepo by application.inject<CollectionRepository>()
                    val collectionBookRepo by application.inject<CollectionBookRepository>()
                    collectionRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    collectionBookRepo.upsert(membership("private-col", "b1"))

                    val response = client.get("/api/v1/covers/b1") { bearerAuth(memberToken) }

                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/covers/{id} returns 401 without a bearer token") {
            val libraryRoot = Files.createTempDirectory("listenup-covers-alias-unauth-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    client.get("/api/v1/covers/b1").status shouldBe HttpStatusCode.Unauthorized
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/books/{id}/cover returns 200 for ROOT on a private book they don't own") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-root-bypass-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val rootToken = client.mintAccessToken() // first user → ROOT
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    val jpegBytes = fakeJpeg()
                    Files.write(bookDir.resolve("cover.jpg"), jpegBytes)
                    val repo by application.inject<BookRepository>()
                    repo.upsert(coverFixture(id = "b1", source = CoverSource.FILESYSTEM))

                    // b1 is private to a stranger; ROOT bypasses the access filter entirely.
                    val collectionRepo by application.inject<CollectionRepository>()
                    val collectionBookRepo by application.inject<CollectionBookRepository>()
                    collectionRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    collectionBookRepo.upsert(membership("private-col", "b1"))

                    val response = client.get("/api/v1/books/b1/cover") { bearerAuth(rootToken) }

                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsBytes().toList() shouldBe jpegBytes.toList()
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        // ── Managed covers (UPLOADED/ENRICHED via setManagedCover + hash ETag) ──

        test("GET /api/v1/covers/{id} serves a managed cover and sets an ETag from the hash") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-managed-serve-")
            val homeRoot = Files.createTempDirectory("listenup-home-managed-serve-")
            try {
                testApplication {
                    useIsolatedTestConfig(
                        libraryPath = libraryRoot.toString(),
                        homeDir = homeRoot.toString(),
                    )
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    // Write the managed cover under $homeDir/covers/
                    val coversDir = Files.createDirectories(homeRoot.resolve("covers"))
                    val jpegBytes = fakeJpeg()
                    val coverHash = "deadbeef01"
                    Files.write(coversDir.resolve("b4.jpg"), jpegBytes)

                    val repo by application.inject<BookRepository>()
                    repo.upsert(coverFixture(id = "b4", source = CoverSource.UPLOADED))
                    repo.setManagedCover(
                        id = BookId("b4"),
                        relPath = "covers/b4.jpg",
                        hash = coverHash,
                        source = CoverSource.UPLOADED,
                    )

                    val response = client.get("/api/v1/covers/b4") { bearerAuth(token) }

                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.ContentType].shouldStartWith("image/jpeg")
                    response.headers[HttpHeaders.ETag] shouldBe "\"$coverHash\""
                    response.bodyAsBytes().toList() shouldBe jpegBytes.toList()
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
                homeRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/covers/{id} returns 304 when If-None-Match matches the managed cover hash") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-managed-304-")
            val homeRoot = Files.createTempDirectory("listenup-home-managed-304-")
            try {
                testApplication {
                    useIsolatedTestConfig(
                        libraryPath = libraryRoot.toString(),
                        homeDir = homeRoot.toString(),
                    )
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val coversDir = Files.createDirectories(homeRoot.resolve("covers"))
                    val coverHash = "deadbeef02"
                    Files.write(coversDir.resolve("b5.jpg"), fakeJpeg())

                    val repo by application.inject<BookRepository>()
                    repo.upsert(coverFixture(id = "b5", source = CoverSource.UPLOADED))
                    repo.setManagedCover(
                        id = BookId("b5"),
                        relPath = "covers/b5.jpg",
                        hash = coverHash,
                        source = CoverSource.UPLOADED,
                    )

                    val response =
                        client.get("/api/v1/covers/b5") {
                            bearerAuth(token)
                            headers.append(HttpHeaders.IfNoneMatch, "\"$coverHash\"")
                        }

                    response.status shouldBe HttpStatusCode.NotModified
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
                homeRoot.toFile().deleteRecursively()
            }
        }
    })

/** Builds a private (non-global-access, non-inbox) collection owned by [owner]. */
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

/** Builds a `collection_books` membership row placing [bookId] in [collectionId]. */
private fun membership(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        id = "$collectionId:$bookId",
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )

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

/**
 * Builds a [BookSyncPayload] with an explicit [cover][CoverPayload]. A null
 * [source] yields a cover-less book.
 */
private fun coverFixture(
    id: String,
    source: CoverSource?,
    audioFilename: String = "01.m4b",
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
        cover = source?.let { CoverPayload(source = it, hash = "hash-$id") },
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
                    filename = audioFilename,
                    format = audioFilename.substringAfterLast('.'),
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
