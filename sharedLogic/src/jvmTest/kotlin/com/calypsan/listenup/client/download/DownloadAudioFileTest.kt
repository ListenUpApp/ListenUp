@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.dto.PreparedAudioFile
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.images.StoragePaths
import com.calypsan.listenup.client.data.repository.FakeDownloadRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import kotlinx.io.files.Path
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
    })

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
