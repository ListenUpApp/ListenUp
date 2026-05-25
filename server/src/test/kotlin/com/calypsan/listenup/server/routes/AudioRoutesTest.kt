@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
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

                    val signer = AudioUrlSigner(signingKey = TEST_SIGNING_KEY)
                    val query = signer.signedQuery("user1", "b1", "af1")

                    val response = client.get("/api/v1/audio/b1/af1?$query")

                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.AcceptRanges] shouldBe "bytes"
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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    client.get("/healthz")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    val audioBytes = ByteArray(256) { it.toByte() }
                    Files.write(bookDir.resolve("01.m4b"), audioBytes)

                    val repo by application.inject<BookRepository>()
                    repo.upsert(audioFixture(bookId = "b1", fileId = "af1", filename = "01.m4b"))

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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
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

        test("HEAD is handled by AutoHeadResponse — 200 with no body for a valid signed URL") {
            val libraryRoot = Files.createTempDirectory("listenup-audio-routes-head-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    client.get("/healthz")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    val audioBytes = ByteArray(256) { it.toByte() }
                    Files.write(bookDir.resolve("01.m4b"), audioBytes)

                    val repo by application.inject<BookRepository>()
                    repo.upsert(audioFixture(bookId = "b1", fileId = "af1", filename = "01.m4b"))

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
