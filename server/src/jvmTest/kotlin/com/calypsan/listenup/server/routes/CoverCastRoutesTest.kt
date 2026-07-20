package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.audio.CoverUrlSigner
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.koin.ktor.ext.inject
import java.nio.file.Files

/**
 * Integration tests for `GET /api/v1/cover-cast/{bookId}`.
 *
 * The route is NOT JWT-gated — the HMAC-signed query string is the auth,
 * mirroring [AudioRoutes]. A Chromecast receiver fetches the cover with no
 * Authorization header; `playback/prepare` mints the signed query via
 * [CoverUrlSigner] and this route validates it on every request.
 *
 * The signing key is derived from the test JWT secret configured by
 * `useIsolatedTestConfig` ("x" * 32).
 */

private val TEST_JWT_SECRET = "x".repeat(32) // must match the value in useIsolatedTestConfig
private val TEST_SIGNING_KEY = CoverUrlSigner.deriveSigningKey(TEST_JWT_SECRET)

class CoverCastRoutesTest :
    FunSpec({

        test("GET with a valid signed query and a book with a cover returns 200 with image bytes") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-cast-200-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), rescanOnStartup = false)
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    // Trigger application startup before inject
                    client.get("/healthz")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    // Write a real cover file under the book's rootRelPath
                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    val jpegBytes =
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
                    Files.write(bookDir.resolve("cover.jpg"), jpegBytes)

                    val repo by application.inject<BookRepository>()
                    repo.upsert(
                        coverFixture(
                            id = "b1",
                            source = CoverSource.FILESYSTEM,
                        ),
                    )

                    // Seed user1 so roleOf("user1") resolves; then place b1 in a collection
                    // that user1 owns — the owner branch makes the book reachable.
                    val sql by application.inject<ListenUpDatabase>()
                    sql.seedTestUser("user1")
                    val collectionRepo by application.inject<CollectionRepository>()
                    val collectionBookRepo by application.inject<CollectionBookRepository>()
                    collectionRepo.upsert(privateCollection("owned-col", owner = "user1"))
                    collectionBookRepo.upsert(membership("owned-col", "b1"))

                    val signer = CoverUrlSigner(signingKey = TEST_SIGNING_KEY)
                    val query = signer.signedQuery("user1", "b1")

                    val response = client.get("/api/v1/cover-cast/b1?$query")

                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsBytes().toList() shouldBe jpegBytes.toList()
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET with no u/exp/sig parameters returns 403 Forbidden") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-cast-403-missing-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), rescanOnStartup = false)
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val response = client.get("/api/v1/cover-cast/b1")

                    response.status shouldBe HttpStatusCode.Forbidden
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET with a forged sig returns 403 Forbidden") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-cast-403-forged-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), rescanOnStartup = false)
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val signer = CoverUrlSigner(signingKey = TEST_SIGNING_KEY)
                    val query = signer.signedQuery("user1", "b1")
                    val tamperedSig = query.substringBefore("sig=") + "sig=" + "0".repeat(64)

                    val response = client.get("/api/v1/cover-cast/b1?$tamperedSig")

                    response.status shouldBe HttpStatusCode.Forbidden
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("valid signature but user cannot access the book returns 404") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-cast-access-deny-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), rescanOnStartup = false)
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    client.get("/healthz")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    Files.write(bookDir.resolve("cover.jpg"), byteArrayOf(0xFF.toByte(), 0xD8.toByte()))

                    val repo by application.inject<BookRepository>()
                    repo.upsert(
                        coverFixture(
                            id = "b1",
                            source = CoverSource.FILESYSTEM,
                        ),
                    )

                    // Seed member so roleOf("member") resolves; then lock b1 in a private
                    // collection owned by a stranger — member has no relationship → gate 404.
                    val sql by application.inject<ListenUpDatabase>()
                    sql.seedTestUser("member")
                    val collectionRepo by application.inject<CollectionRepository>()
                    val collectionBookRepo by application.inject<CollectionBookRepository>()
                    collectionRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    collectionBookRepo.upsert(membership("private-col", "b1"))

                    val signer = CoverUrlSigner(signingKey = TEST_SIGNING_KEY)
                    val query = signer.signedQuery("member", "b1")

                    val response = client.get("/api/v1/cover-cast/b1?$query")

                    response.status shouldBe HttpStatusCode.NotFound
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
        id = "$collectionId:$bookId",
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )

private fun coverFixture(
    id: String,
    source: CoverSource?,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "Cover Cast Book $id",
        sortTitle = "Cover Cast Book $id",
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
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 3_600_000L,
                    size = 256L,
                ),
            ),
        chapters = emptyList(),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
