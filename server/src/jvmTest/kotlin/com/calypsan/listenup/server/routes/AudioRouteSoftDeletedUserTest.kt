@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.audio.AudioUrlSigner
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
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.koin.ktor.ext.inject

private val TEST_JWT_SECRET = "x".repeat(32) // must match the value in useIsolatedTestConfig
private val TEST_SIGNING_KEY = AudioUrlSigner.deriveSigningKey(TEST_JWT_SECRET)

/**
 * A signed audio URL proves *who* the caller is and lives for hours, but the route resolves the
 * caller's role live on every request. That lookup must see a soft-deleted account as gone —
 * otherwise a removed member keeps streaming on their already-minted URLs until they expire.
 *
 * Same harness as [AudioRoutesTest]: the full `Application.module()` over a real SQLite database
 * and a temp library directory, with the fixture file on disk.
 */
class AudioRouteSoftDeletedUserTest :
    FunSpec({

        test("a validly signed URL answers 404 once its user is soft-deleted") {
            val libraryRoot = Files.createTempDirectory("listenup-audio-routes-soft-deleted-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), rescanOnStartup = false)
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    client.get("/healthz")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    Files.write(bookDir.resolve("01.m4b"), ByteArray(256) { it.toByte() })

                    val repo by application.inject<BookRepository>()
                    repo.upsert(audioFixture(bookId = "b1", fileId = "af1", filename = "01.m4b"))

                    // The member owns the collection b1 lives in, so it is reachable while they are live.
                    val sql by application.inject<ListenUpDatabase>()
                    sql.seedTestUser("member")
                    val collectionRepo by application.inject<CollectionRepository>()
                    val collectionBookRepo by application.inject<CollectionBookRepository>()
                    collectionRepo.upsert(ownedCollection("owned-col", owner = "member"))
                    collectionBookRepo.upsert(membership("owned-col", "b1"))

                    val signer = AudioUrlSigner(signingKey = TEST_SIGNING_KEY)
                    val query = signer.signedQuery("member", "b1", "af1")

                    // Control: the URL serves while the account is live.
                    client.get("/api/v1/audio/b1/af1?$query").status shouldBe HttpStatusCode.OK

                    // Remove the account. The URL is still validly signed and far from expiry.
                    sql.usersQueries.markDeletedAt(deleted_at = 1_700_000_000_000L, id = "member")

                    client.get("/api/v1/audio/b1/af1?$query").status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

private fun ownedCollection(
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
        chapters = emptyList(),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
