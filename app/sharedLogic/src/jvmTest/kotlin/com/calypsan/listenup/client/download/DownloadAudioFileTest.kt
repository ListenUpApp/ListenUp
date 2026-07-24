@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.dto.PreparedAudioFile
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.local.images.StoragePaths
import com.calypsan.listenup.client.data.repository.FakeDownloadRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import java.io.File

/**
 * Seam-level tests for [resolveDownloadUrl] via [downloadAudioFile].
 *
 * Validates that the download path obtains signed URLs from [PlaybackService.prepare]
 * and that the WaitForServer path is gone.
 */
class DownloadAudioFileTest :
    FunSpec({

        // ---- Scenario 1 ----
        // prepare() succeeds, fileId found → Ready with signed relative URL, no bare /api/v1/books/ path.
        test("resolveDownloadUrl returns signed relative URL from prepare on success") {
            val tmpRoot = tempDir()
            try {
                val bookId = "book-1"
                val audioFileId = "file-1"
                val signedUrl = "/api/v1/audio/$bookId/$audioFileId?u=&exp=&sig=test"

                val fakeRpcFactory =
                    FakePlaybackPrepareRepository(
                        AppResult.Success(
                            PreparedPlayback(
                                bookId = bookId,
                                audioFiles =
                                    listOf(
                                        PreparedAudioFile(
                                            fileId = audioFileId,
                                            index = 0,
                                            url = signedUrl,
                                            format = "mp3",
                                            durationMs = 1000L,
                                            sizeBytes = 1000L,
                                        ),
                                    ),
                                resumePosition = null,
                            ),
                        ),
                    )

                val fakeRepo =
                    FakeDownloadRepository(
                        initial =
                            listOf(
                                downloadEntity(audioFileId = audioFileId, bookId = bookId, totalBytes = 1000L),
                            ),
                    )

                var capturedPath: String? = null
                val engine =
                    MockEngine { request ->
                        capturedPath = request.url.encodedPath + "?" + (request.url.encodedQuery ?: "")
                        respond(
                            content = ByteArray(1000) { 0x42 },
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentLength, "1000"),
                        )
                    }

                downloadAudioFile(
                    audioFileId = audioFileId,
                    bookId = bookId,
                    filename = "file-1.mp3",
                    expectedSize = 1000L,
                    httpClient = minimalClient(engine),
                    repository = fakeRepo,
                    fileManager = fileManagerFor(tmpRoot),
                    prepareRepository = fakeRpcFactory,
                )

                // The download must have hit the signed relative URL — not a bare /api/v1/books/ path.
                capturedPath shouldBe "/api/v1/audio/book-1/file-1?u=&exp=&sig=test"
                capturedPath!!.shouldNotContain("/api/v1/books/")
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

        // ---- Scenario 2 ----
        // prepare() returns Failure → downloadAudioFile returns AppResult.Failure; no bare URL fallback.
        test("resolveDownloadUrl fails when prepare returns Failure — no bare URL fallback") {
            val tmpRoot = tempDir()
            try {
                val audioFileId = "file-2"
                val bookId = "book-2"

                val fakeRpcFactory =
                    FakePlaybackPrepareRepository(
                        AppResult.Failure(
                            TransportError.NetworkUnavailable(debugInfo = "simulated network failure"),
                        ),
                    )

                val fakeRepo =
                    FakeDownloadRepository(
                        initial =
                            listOf(
                                downloadEntity(audioFileId = audioFileId, bookId = bookId, totalBytes = 1000L),
                            ),
                    )

                val engine =
                    MockEngine { request ->
                        // The bare fallback URL would start with /api/v1/books/ — reject it.
                        error("Unexpected HTTP request: ${request.url} — no download should occur after prepare failure")
                    }

                val result =
                    downloadAudioFile(
                        audioFileId = audioFileId,
                        bookId = bookId,
                        filename = "file-2.mp3",
                        expectedSize = 1000L,
                        httpClient = minimalClient(engine),
                        repository = fakeRepo,
                        fileManager = fileManagerFor(tmpRoot),
                        prepareRepository = fakeRpcFactory,
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

        // ---- Scenario 3 ----
        // prepare() succeeds but fileId is absent from audioFiles → AppResult.Failure.
        test("resolveDownloadUrl fails when fileId not found in PreparedPlayback — no bare URL fallback") {
            val tmpRoot = tempDir()
            try {
                val audioFileId = "missing-file"
                val bookId = "book-3"

                val fakeRpcFactory =
                    FakePlaybackPrepareRepository(
                        AppResult.Success(
                            PreparedPlayback(
                                bookId = bookId,
                                // audioFiles contains a DIFFERENT fileId — not the requested one.
                                audioFiles =
                                    listOf(
                                        PreparedAudioFile(
                                            fileId = "different-file",
                                            index = 0,
                                            url = "/api/v1/audio/book-3/different-file?sig=x",
                                            format = "mp3",
                                            durationMs = 1000L,
                                            sizeBytes = 1000L,
                                        ),
                                    ),
                                resumePosition = null,
                            ),
                        ),
                    )

                val fakeRepo =
                    FakeDownloadRepository(
                        initial =
                            listOf(
                                downloadEntity(audioFileId = audioFileId, bookId = bookId, totalBytes = 1000L),
                            ),
                    )

                val engine =
                    MockEngine { request ->
                        error("Unexpected HTTP request: ${request.url} — no download should occur when fileId not found")
                    }

                val result =
                    downloadAudioFile(
                        audioFileId = audioFileId,
                        bookId = bookId,
                        filename = "missing-file.mp3",
                        expectedSize = 1000L,
                        httpClient = minimalClient(engine),
                        repository = fakeRepo,
                        fileManager = fileManagerFor(tmpRoot),
                        prepareRepository = fakeRpcFactory,
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

        // ---- B6: server ignores Range on resume (returns 200, not 206) ----
        // A partial .tmp exists (startByte > 0) so we send a Range header, but the server/proxy
        // returns 200 with the FULL body. Appending would corrupt the file (400 + 1000 = 1400).
        // The fix truncates the temp and restarts from byte 0 → final file is exactly the full body.
        test("resume where server ignores Range (200, not 206) truncates the temp and restarts — no corruption") {
            val tmpRoot = tempDir()
            try {
                val bookId = "book-r6"
                val audioFileId = "file-r6"
                val fileManager = fileManagerFor(tmpRoot)

                // Pre-write 400 stale bytes to the temp path so startByte = 400 and a Range header is sent.
                val tempPath = fileManager.getAudioFilePath(bookId, audioFileId, "file-r6.mp3", isTemp = true)
                SystemFileSystem.sink(tempPath).buffered().use { it.write(ByteArray(400) { 0x41 }) }

                val fakeRepo =
                    FakeDownloadRepository(
                        initial = listOf(downloadEntity(audioFileId = audioFileId, bookId = bookId, totalBytes = 1000L)),
                    )

                // Server IGNORES the Range: 200 OK + the full 1000-byte body (not 206 partial).
                val engine =
                    MockEngine {
                        respond(
                            content = ByteArray(1000) { 0x42 },
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentLength, "1000"),
                        )
                    }

                val result =
                    downloadAudioFile(
                        audioFileId = audioFileId,
                        bookId = bookId,
                        filename = "file-r6.mp3",
                        expectedSize = 1000L,
                        httpClient = minimalClient(engine),
                        repository = fakeRepo,
                        fileManager = fileManager,
                        prepareRepository = readyPrepareRepo(bookId, audioFileId),
                    )

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                // Final file is the full body (1000), NOT the corrupt append (1400).
                val destPath = fileManager.getAudioFilePath(bookId, audioFileId, "file-r6.mp3", isTemp = false)
                fileManager.getFileSize(destPath.toString()) shouldBe 1000L
                fakeRepo.entities.single().state shouldBe DownloadState.COMPLETED
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

        // ---- B6: unknown expectedSize + truncated stream must FAIL (not finalize at bogus 100%) ----
        // expectedSize = 0 (DownloadWorker's default when metadata size is unknown). The server
        // advertises Content-Length 1000 but the stream closes after only 500 bytes. Pre-fix this
        // was markCompleted'd as success; the fix verifies against Content-Length and FAILS.
        test("expectedSize=0 with a truncated body verified against Content-Length FAILS") {
            val tmpRoot = tempDir()
            try {
                val bookId = "book-r6b"
                val audioFileId = "file-r6b"
                val fileManager = fileManagerFor(tmpRoot)
                val fakeRepo =
                    FakeDownloadRepository(
                        initial = listOf(downloadEntity(audioFileId = audioFileId, bookId = bookId, totalBytes = 0L)),
                    )

                // Content-Length says 1000 but only 500 bytes arrive, then the stream closes cleanly.
                val engine =
                    MockEngine {
                        respond(
                            content = ByteArray(500) { 0x42 },
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentLength, "1000"),
                        )
                    }

                val result =
                    downloadAudioFile(
                        audioFileId = audioFileId,
                        bookId = bookId,
                        filename = "file-r6b.mp3",
                        expectedSize = 0L,
                        httpClient = minimalClient(engine),
                        repository = fakeRepo,
                        fileManager = fileManager,
                        prepareRepository = readyPrepareRepo(bookId, audioFileId),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                fakeRepo.entities.single().state shouldNotBe DownloadState.COMPLETED
                // The corrupt partial was deleted, not finalized.
                val destPath = fileManager.getAudioFilePath(bookId, audioFileId, "file-r6b.mp3", isTemp = false)
                SystemFileSystem.exists(destPath) shouldBe false
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

        // ---- B6: genuinely unverifiable download (no expectedSize, no Content-Length) FAILS ----
        test("expectedSize=0 with no Content-Length is unverifiable and FAILS") {
            val tmpRoot = tempDir()
            try {
                val bookId = "book-r6c"
                val audioFileId = "file-r6c"
                val fileManager = fileManagerFor(tmpRoot)
                val fakeRepo =
                    FakeDownloadRepository(
                        initial = listOf(downloadEntity(audioFileId = audioFileId, bookId = bookId, totalBytes = 0L)),
                    )

                // No Content-Length header at all → size is genuinely unverifiable.
                val engine =
                    MockEngine {
                        respond(content = ByteArray(500) { 0x42 }, status = HttpStatusCode.OK)
                    }

                val result =
                    downloadAudioFile(
                        audioFileId = audioFileId,
                        bookId = bookId,
                        filename = "file-r6c.mp3",
                        expectedSize = 0L,
                        httpClient = minimalClient(engine),
                        repository = fakeRepo,
                        fileManager = fileManager,
                        prepareRepository = readyPrepareRepo(bookId, audioFileId),
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

        // ---- B10c: markCompleted failing after the file lands surfaces as a FAILURE, not silent success ----
        test("markCompleted failure after the file lands surfaces as a download failure") {
            val tmpRoot = tempDir()
            try {
                val bookId = "book-r10"
                val audioFileId = "file-r10"
                val fileManager = fileManagerFor(tmpRoot)
                val fakeRepo =
                    FakeDownloadRepository(
                        initial = listOf(downloadEntity(audioFileId = audioFileId, bookId = bookId, totalBytes = 1000L)),
                        markCompletedFailure = DownloadError.DownloadFailed(debugInfo = "DB write failed"),
                    )

                val engine =
                    MockEngine {
                        respond(
                            content = ByteArray(1000) { 0x42 },
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentLength, "1000"),
                        )
                    }

                val result =
                    downloadAudioFile(
                        audioFileId = audioFileId,
                        bookId = bookId,
                        filename = "file-r10.mp3",
                        expectedSize = 1000L,
                        httpClient = minimalClient(engine),
                        repository = fakeRepo,
                        fileManager = fileManager,
                        prepareRepository = readyPrepareRepo(bookId, audioFileId),
                    )

                // The bytes landed but the DB write failed — honest failure, not a lying success.
                result.shouldBeInstanceOf<AppResult.Failure>()
            } finally {
                tmpRoot.deleteRecursively()
            }
        }
    })

// A ready [PlaybackPrepareRepository] that signs a relative download URL for [audioFileId].
private fun readyPrepareRepo(
    bookId: String,
    audioFileId: String,
): FakePlaybackPrepareRepository =
    FakePlaybackPrepareRepository(
        AppResult.Success(
            PreparedPlayback(
                bookId = bookId,
                audioFiles =
                    listOf(
                        PreparedAudioFile(
                            fileId = audioFileId,
                            index = 0,
                            url = "/api/v1/audio/$bookId/$audioFileId?u=&exp=&sig=test",
                            format = "mp3",
                            durationMs = 1000L,
                            sizeBytes = 1000L,
                        ),
                    ),
                resumePosition = null,
            ),
        ),
    )

// FakePlaybackPrepareRepository is defined in DownloadWorkerLogicTest.kt
// (same package, internal visibility) — reused here.

// ---- Helpers ----

private fun downloadEntity(
    audioFileId: String,
    bookId: String = "book-1",
    totalBytes: Long = 1000L,
) = com.calypsan.listenup.client.data.local.db.DownloadEntity(
    audioFileId = audioFileId,
    bookId = bookId,
    filename = "$audioFileId.mp3",
    fileIndex = 0,
    state = com.calypsan.listenup.client.data.local.db.DownloadState.QUEUED,
    localPath = null,
    totalBytes = totalBytes,
    downloadedBytes = 0L,
    queuedAt = 0L,
    startedAt = null,
    completedAt = null,
    errorMessage = null,
    retryCount = 0,
)

private fun minimalClient(engine: io.ktor.client.engine.HttpClientEngine): HttpClient =
    HttpClient(engine) {
        install(ContentNegotiation) {
            json(com.calypsan.listenup.core.appJson)
        }
    }

private fun fileManagerFor(tmpRoot: File): DownloadFileManager {
    val path = Path(tmpRoot.absolutePath)
    return DownloadFileManager(
        storagePaths =
            object : StoragePaths {
                override val filesDir: Path = path
            },
    )
}

private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "daft-${System.nanoTime()}").apply { mkdirs() }
