package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.IngestOutcome
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.PendingCover
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.koin.ktor.ext.inject

/**
 * End-to-end lifecycle test for the cover domain.
 *
 * Chains the full sequence in one flow using real HTTP routes and the Koin-wired
 * [BookRepository] from the running application:
 *  1. Seed a cover-less book.
 *  2. Upload a PNG cover via `PUT /api/v1/books/{id}/cover` as ROOT → 204.
 *  3. `GET /api/v1/covers/{id}` → 200, body bytes == uploaded bytes.
 *  4. Simulate a re-scan by calling `resolveOrInsert` for the same natural key
 *     with a DIFFERENT cover (FILESYSTEM source).
 *  5. `GET /api/v1/covers/{id}` → 200, body bytes are STILL the uploaded bytes
 *     (sticky-upload invariant). `cover_source` is still UPLOADED.
 *  6. `$LISTENUP_HOME/covers/` contains exactly one file — no orphan from re-scan.
 *
 * Approach: full HTTP for upload and serve (real E2E); `resolveOrInsert` driven via
 * the application's Koin-wired [BookRepository] + [LibraryRegistry] so the re-scan
 * shares the same database and homeDir as the HTTP layer.
 */
class CoverLifecycleE2ETest :
    FunSpec({
        // Minimal valid PNG: signature + enough zero bytes to pass the magic-number sniff.
        val uploadedBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)

        // A distinct fake JPEG for the re-scan PendingCover — deliberately different bytes
        // so a SHA-256 mismatch would surface if the upload were ever replaced.
        val rescanBytes =
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

        test("upload → serve → re-scan preserves the uploaded cover (sticky-upload lifecycle)") {
            val libraryRoot = Files.createTempDirectory("listenup-lifecycle-lib-")
            val homeDir = Files.createTempDirectory("listenup-lifecycle-home-")
            try {
                testApplication {
                    useIsolatedTestConfig(
                        libraryPath = libraryRoot.toString(),
                        homeDir = homeDir.toString(),
                    )
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    // ── Step 0: auth ─────────────────────────────────────────────────────────
                    val token =
                        client
                            .post("/api/v1/auth/setup") {
                                contentType(ContentType.Application.Json)
                                setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
                            }.body<AppResult<AuthSession>>()
                            .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                            .data
                            .accessToken
                            .value

                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    // ── Step 1: seed a cover-less book via resolveOrInsert ──────────────────
                    // Using resolveOrInsert (not repo.upsert with a fixed id) ensures the book is
                    // recorded under the real library UUID that the application's LibraryRegistry
                    // created at boot — so the re-scan's resolveOrInsert resolves to the SAME row
                    // via the natural-key (library_id, root_rel_path) lookup.
                    val repo by application.inject<BookRepository>()
                    val registry by application.inject<LibraryRegistry>()
                    val libId = registry.currentLibrary()
                    val analyzed = minimalAnalyzedBook(rootRelPath = "books/lc1")
                    val insertResult = repo.resolveOrInsert(libId, FolderId("test-folder"), analyzed)
                    val bookId =
                        insertResult.shouldBeInstanceOf<AppResult.Success<IngestOutcome>>().data.bookId

                    // ── Step 2: upload a PNG cover as ROOT ───────────────────────────────────
                    val uploadResponse =
                        client.put("/api/v1/books/${bookId.value}/cover") {
                            bearerAuth(token)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            uploadedBytes,
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "image/png")
                                                append(HttpHeaders.ContentDisposition, "filename=\"cover.png\"")
                                            },
                                        )
                                    },
                                ),
                            )
                        }
                    uploadResponse.status shouldBe HttpStatusCode.NoContent

                    // ── Step 3: GET → assert body == uploadedBytes ───────────────────────────
                    val serveAfterUpload = client.get("/api/v1/covers/${bookId.value}") { bearerAuth(token) }
                    serveAfterUpload.status shouldBe HttpStatusCode.OK
                    serveAfterUpload.bodyAsBytes().toList() shouldBe uploadedBytes.toList()

                    // ── Step 4: simulate re-scan with a DIFFERENT cover ───────────────────────
                    // resolveOrInsert resolves the same natural key (library_id, rootRelPath),
                    // finds the existing book row with cover_source == UPLOADED, and must NOT
                    // clobber it with the re-scan's PendingCover — neither the DB row nor the
                    // on-disk file should change.
                    val pending = PendingCover(bytes = rescanBytes, mime = "image/jpeg", source = CoverSource.FILESYSTEM)
                    repo.resolveOrInsert(libId, FolderId("test-folder"), analyzed, pending)

                    // ── Step 5a: GET → assert body is STILL uploadedBytes ────────────────────
                    val serveAfterRescan = client.get("/api/v1/covers/${bookId.value}") { bearerAuth(token) }
                    serveAfterRescan.status shouldBe HttpStatusCode.OK
                    serveAfterRescan.bodyAsBytes().toList() shouldBe uploadedBytes.toList()

                    // ── Step 5b: cover_source still UPLOADED via findById ────────────────────
                    val book = repo.findById(bookId).shouldNotBeNull()
                    book.cover.shouldNotBeNull().source shouldBe CoverSource.UPLOADED

                    // ── Step 6: only ONE file in the covers dir — no orphan from re-scan ─────
                    val coversDir = homeDir.resolve("covers")
                    val files = Files.list(coversDir).use { it.toList() }
                    files.size shouldBe 1
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
                homeDir.toFile().deleteRecursively()
            }
        }
    })

/**
 * Minimal [AnalyzedBook] for the re-scan step. Uses the same [rootRelPath] as the
 * seeded book so [BookRepository.resolveOrInsert] resolves to the existing row rather
 * than inserting a new one.
 */
private fun minimalAnalyzedBook(rootRelPath: String): AnalyzedBook {
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
        title = "Lifecycle Test Book",
        tracks = listOf(TrackEntry(file = file)),
    )
}
