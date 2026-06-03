@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.koin.ktor.ext.inject
import java.nio.file.Files

/**
 * Integration tests for Task 5: persist-at-scan and sticky-upload-merge.
 *
 * Three tests:
 *  1. `resolveOrInsert` with an embedded [PendingCover] writes a managed file to
 *     `$homeDir/covers/` and records the cover hash on the book row.
 *  2. `GET /api/v1/covers/{id}` serves embedded artwork bytes after
 *     `resolveOrInsert` with a [PendingCover] (round-trip integration test).
 *  3. Sticky-merge: an UPLOADED cover is preserved when `resolveOrInsert` runs
 *     again (simulated re-scan with a different EMBEDDED [PendingCover]).
 *  4. Control case: a non-UPLOADED (EMBEDDED) existing cover IS replaced on re-scan.
 */
class BookPersistCoverTest :
    FunSpec({

        // ── 1. resolveOrInsert stores the managed file ──────────────────────

        test("resolveOrInsert with EMBEDDED pendingCover stores a managed file and records the cover hash") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val homeDir = Files.createTempDirectory("listenup-cover-persist-")
                    try {
                        val coverStore =
                            CoverImageStore(ImageStore(homeDir.resolve("covers"), MAX_COVER_BYTES))
                        val bus = ChangeBus()
                        val syncRegistry = SyncRegistry()
                        val repo =
                            BookRepository(
                                db = db,
                                bus = bus,
                                registry = syncRegistry,
                                contributorRepository = ContributorRepository(db, bus, syncRegistry),
                                seriesRepository = SeriesRepository(db, bus, syncRegistry),
                                coverImageStore = coverStore,
                                homeDir = homeDir,
                            )
                        val registry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib"))
                        val libId = registry.currentLibrary()

                        val artworkBytes = fakeJpeg()
                        val pending =
                            PendingCover(bytes = artworkBytes, mime = "image/jpeg", source = CoverSource.EMBEDDED)
                        val analyzed = minimalBook("Author/Title")

                        val result = repo.resolveOrInsert(libId, FolderId("test-folder"), analyzed, pending)
                        val bookId =
                            result.shouldBeInstanceOf<AppResult.Success<IngestOutcome>>().data.bookId

                        // cover hash and source are recorded on the book row.
                        val saved = repo.findById(bookId).shouldNotBeNull()
                        val cover = saved.cover.shouldNotBeNull()
                        cover.source shouldBe CoverSource.EMBEDDED
                        cover.hash.shouldNotBeNull()

                        // Managed file was written to $homeDir/covers/<bookId>.jpg.
                        val coversDir = homeDir.resolve("covers")
                        val managedFiles = Files.list(coversDir).use { it.toList() }
                        managedFiles.size shouldBe 1
                        managedFiles.single().fileName.toString() shouldBe "${bookId.value}.jpg"
                    } finally {
                        homeDir.toFile().deleteRecursively()
                    }
                }
            }
        }

        // ── 2. Round-trip: GET /api/v1/covers/{id} serves embedded artwork ──

        test("GET /api/v1/covers/{id} serves embedded artwork after resolveOrInsert with pendingCover") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-get-")
            val homeRoot = Files.createTempDirectory("listenup-home-get-")
            try {
                val artworkBytes = fakeJpeg()
                val mp3 =
                    buildMp3File {
                        id3v2(version = 4) {
                            textFrame("TIT2", "Persist Test Book")
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
                    useIsolatedTestConfig(
                        libraryPath = libraryRoot.toString(),
                        homeDir = homeRoot.toString(),
                    )
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    // Write an MP3 with embedded artwork under the library root.
                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/ep1"))
                    Files.write(bookDir.resolve("01.mp3"), mp3)

                    val repo by application.inject<BookRepository>()
                    val registry by application.inject<LibraryRegistry>()
                    val libId = registry.currentLibrary()

                    val pending =
                        PendingCover(bytes = artworkBytes, mime = "image/jpeg", source = CoverSource.EMBEDDED)
                    val analyzed = minimalBookWithMp3(rootRelPath = "books/ep1", mp3Name = "01.mp3")

                    val result = repo.resolveOrInsert(libId, FolderId("test-folder"), analyzed, pending)
                    val bookId =
                        result.shouldBeInstanceOf<AppResult.Success<IngestOutcome>>().data.bookId

                    // Verify cover was recorded.
                    repo
                        .findById(bookId)
                        .shouldNotBeNull()
                        .cover
                        .shouldNotBeNull()
                        .source shouldBe
                        CoverSource.EMBEDDED

                    // GET /api/v1/covers/{id} serves the artwork bytes via the EMBEDDED path
                    // (coverInfo() extracts them from the MP3 at serve time).
                    val response = client.get("/api/v1/covers/${bookId.value}") { bearerAuth(token) }

                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsBytes().toList() shouldBe artworkBytes.toList()
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
                homeRoot.toFile().deleteRecursively()
            }
        }

        // ── 3. Sticky-upload merge: UPLOADED cover survives re-scan ─────────

        test("re-scan with EMBEDDED pendingCover does NOT overwrite an existing UPLOADED cover") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val homeDir = Files.createTempDirectory("listenup-sticky-upload-")
                    try {
                        val coverStore =
                            CoverImageStore(ImageStore(homeDir.resolve("covers"), MAX_COVER_BYTES))
                        val bus = ChangeBus()
                        val syncRegistry = SyncRegistry()
                        val repo =
                            BookRepository(
                                db = db,
                                bus = bus,
                                registry = syncRegistry,
                                contributorRepository = ContributorRepository(db, bus, syncRegistry),
                                seriesRepository = SeriesRepository(db, bus, syncRegistry),
                                coverImageStore = coverStore,
                                homeDir = homeDir,
                            )
                        val registry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib"))
                        val libId = registry.currentLibrary()
                        val folderId = FolderId("test-folder")
                        val analyzed = minimalBook("Author/Sticky")

                        // (1) Initial insert — no cover.
                        val insertResult = repo.resolveOrInsert(libId, folderId, analyzed)
                        val bookId =
                            insertResult.shouldBeInstanceOf<AppResult.Success<IngestOutcome>>().data.bookId

                        // (2) User uploads a cover — source becomes UPLOADED.
                        val uploadedBytes = fakeJpeg()
                        val stored = coverStore.store.store(bookId.value, uploadedBytes, "image/jpeg")
                        repo.setManagedCover(
                            id = bookId,
                            relPath = "covers/${stored.path.fileName}",
                            hash = stored.sha256,
                            source = CoverSource.UPLOADED,
                        )
                        val uploadedHash =
                            repo
                                .findById(bookId)
                                .shouldNotBeNull()
                                .cover
                                .shouldNotBeNull()
                                .hash

                        // (3) Re-scan passes a DIFFERENT embedded cover as PendingCover.
                        val embeddedBytes = fakeJpeg2()
                        val pending =
                            PendingCover(bytes = embeddedBytes, mime = "image/jpeg", source = CoverSource.EMBEDDED)
                        repo.resolveOrInsert(libId, folderId, analyzed, pending)

                        // (4) The UPLOADED cover must be unchanged.
                        val afterRescan = repo.findById(bookId).shouldNotBeNull()
                        afterRescan.cover.shouldNotBeNull().source shouldBe CoverSource.UPLOADED
                        afterRescan.cover!!.hash shouldBe uploadedHash
                    } finally {
                        homeDir.toFile().deleteRecursively()
                    }
                }
            }
        }

        // ── 4. Control: non-UPLOADED existing cover IS updated on re-scan ───

        test("re-scan with EMBEDDED pendingCover DOES update a non-UPLOADED (EMBEDDED) existing cover") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val homeDir = Files.createTempDirectory("listenup-sticky-control-")
                    try {
                        val coverStore =
                            CoverImageStore(ImageStore(homeDir.resolve("covers"), MAX_COVER_BYTES))
                        val bus = ChangeBus()
                        val syncRegistry = SyncRegistry()
                        val repo =
                            BookRepository(
                                db = db,
                                bus = bus,
                                registry = syncRegistry,
                                contributorRepository = ContributorRepository(db, bus, syncRegistry),
                                seriesRepository = SeriesRepository(db, bus, syncRegistry),
                                coverImageStore = coverStore,
                                homeDir = homeDir,
                            )
                        val registry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib"))
                        val libId = registry.currentLibrary()
                        val folderId = FolderId("test-folder")
                        val analyzed = minimalBook("Author/Control")

                        // (1) First scan — EMBEDDED cover stored.
                        val firstBytes = fakeJpeg()
                        val firstPending =
                            PendingCover(bytes = firstBytes, mime = "image/jpeg", source = CoverSource.EMBEDDED)
                        val insertResult = repo.resolveOrInsert(libId, folderId, analyzed, firstPending)
                        val bookId =
                            insertResult.shouldBeInstanceOf<AppResult.Success<IngestOutcome>>().data.bookId

                        val firstHash =
                            repo
                                .findById(bookId)
                                .shouldNotBeNull()
                                .cover
                                .shouldNotBeNull()
                                .hash

                        // (2) Re-scan — DIFFERENT embedded artwork bytes.
                        val secondBytes = fakeJpeg2()
                        val secondPending =
                            PendingCover(bytes = secondBytes, mime = "image/jpeg", source = CoverSource.EMBEDDED)
                        repo.resolveOrInsert(libId, folderId, analyzed, secondPending)

                        // (3) The hash must have changed — new cover was written.
                        val afterRescan = repo.findById(bookId).shouldNotBeNull()
                        afterRescan.cover.shouldNotBeNull().source shouldBe CoverSource.EMBEDDED
                        afterRescan.cover!!.hash shouldNotBe firstHash
                    } finally {
                        homeDir.toFile().deleteRecursively()
                    }
                }
            }
        }
    })

// --- Fixtures ---------------------------------------------------------------

private const val MAX_COVER_BYTES = 10L * 1024 * 1024

/** Minimal valid JPEG magic bytes — passes [ImageStore]'s magic-number sniff. */
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

/** A second distinct fake JPEG (different content so SHA-256 hashes differ). */
private fun fakeJpeg2(): ByteArray =
    byteArrayOf(
        0xFF.toByte(),
        0xD8.toByte(),
        0xFF.toByte(),
        0xE1.toByte(),
        0x00,
        0x10,
        'J'.code.toByte(),
        'F'.code.toByte(),
        0x42,
    )

/** Minimal [AnalyzedBook] with one audio track; no cover. */
private fun minimalBook(rootRelPath: String): AnalyzedBook {
    val file =
        FileEntry(
            relPath = "$rootRelPath/01.m4b",
            name = "01.m4b",
            ext = "m4b",
            size = 1024L,
            mtimeMs = 0L,
            inode = null,
            fileType = FileType.AUDIO,
        )
    return AnalyzedBook(
        candidate = CandidateBook(rootRelPath = rootRelPath, isFile = false, files = listOf(file)),
        title = rootRelPath.substringAfterLast('/'),
        tracks = listOf(TrackEntry(file = file)),
    )
}

/**
 * [AnalyzedBook] whose primary audio file is an MP3. Used by the round-trip GET test
 * where a real MP3 with APIC artwork sits on disk — `coverInfo()` resolves from it.
 */
private fun minimalBookWithMp3(
    rootRelPath: String,
    mp3Name: String,
): AnalyzedBook {
    val file =
        FileEntry(
            relPath = "$rootRelPath/$mp3Name",
            name = mp3Name,
            ext = "mp3",
            size = 10_000L,
            mtimeMs = 0L,
            inode = null,
            fileType = FileType.AUDIO,
        )
    return AnalyzedBook(
        candidate = CandidateBook(rootRelPath = rootRelPath, isFile = false, files = listOf(file)),
        title = "Persist Test Book",
        tracks = listOf(TrackEntry(file = file)),
    )
}

// --- Auth helper (mirrors BookCoverRouteTest) --------------------------------

private suspend fun HttpClient.mintAccessToken(): String {
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
