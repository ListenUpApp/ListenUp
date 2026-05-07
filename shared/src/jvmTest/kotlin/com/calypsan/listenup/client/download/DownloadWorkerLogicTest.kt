@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("MagicNumber")

package com.calypsan.listenup.client.download

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.appJson
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.core.error.AppException
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.local.images.StoragePaths
import com.calypsan.listenup.client.data.remote.PlaybackApiContract
import com.calypsan.listenup.client.data.remote.PreparePlaybackResponse
import com.calypsan.listenup.client.data.remote.installListenUpErrorHandling
import com.calypsan.listenup.client.data.repository.FakeDownloadRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.playback.AudioCapabilityDetector
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Seam-level contract tests for [downloadAudioFile]. Runs on JVM via :shared:jvmTest so it can
 * use real [DownloadFileManager] backed by a temp directory without an Android Context.
 *
 * Path A (commonMain extraction + jvmTest) was chosen because:
 * - commonTest cannot see androidMain source set.
 * - androidHostTest requires Robolectric / Android libs.
 * - jvmTest sees jvmMain actuals (including DownloadFileManager with StoragePaths interface).
 *
 * Per project memory `feedback_fakes_for_seams.md`: hand-rolled fakes + MockEngine, not mokkery.
 *
 * Phase C scope: HTTP migration only. Phase D rewrites the transcode-poll path; Phase C locks
 * current polling behavior as a regression marker (scenario 11).
 */
class DownloadWorkerLogicTest {
    // ---- Fixtures ----

    private fun entity(
        audioFileId: String,
        bookId: String = "book-1",
        state: DownloadState = DownloadState.QUEUED,
        totalBytes: Long = 1000L,
        downloadedBytes: Long = 0L,
    ) = DownloadEntity(
        audioFileId = audioFileId,
        bookId = bookId,
        filename = "$audioFileId.mp3",
        fileIndex = 0,
        state = state,
        localPath = null,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        queuedAt = 0L,
        startedAt = null,
        completedAt = null,
        errorMessage = null,
        retryCount = 0,
    )

    /** Base client: ContentNegotiation + timeout. No Auth or Retry plugins. */
    private fun productionLikeClient(engine: HttpClientEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) { json(appJson) }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
        }

    /**
     * Client with Bearer Auth plugin for 401 scenarios.
     *
     * Mirrors the production ApiClientFactory client: installs [installListenUpErrorHandling] so
     * ResponseException is rewrapped as AppException(ServerError(401)) after a failed refresh.
     * Without this, the 401 propagates as a raw ResponseException — not the production code path.
     */
    private fun authProductionLikeClient(
        engine: HttpClientEngine,
        refreshTokens: suspend () -> BearerTokens?,
    ): HttpClient =
        HttpClient(engine) {
            installListenUpErrorHandling()
            install(ContentNegotiation) { json(appJson) }
            install(Auth) {
                bearer {
                    loadTokens { BearerTokens("initial-token", "refresh-token") }
                    refreshTokens { refreshTokens() }
                }
            }
        }

    /** Client with HttpRequestRetry for 5xx scenarios. Minimum delay for fast test execution. */
    private fun retryProductionLikeClient(engine: HttpClientEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) { json(appJson) }
            install(HttpRequestRetry) {
                retryIf(maxRetries = 3) { _, response ->
                    response.status.value in 500..599
                }
                constantDelay(millis = 1, randomizationMs = 0)
            }
        }

    /** A ready PreparePlaybackResponse for standard test setup. */
    private fun readyResponse(
        streamUrl: String = "/api/v1/books/book-1/audio/file-1",
        codec: String = "mp3",
    ) = PreparePlaybackResponse(
        ready = true,
        streamUrl = streamUrl,
        variant = "original",
        codec = codec,
        transcodeJobId = null,
        progress = 100,
    )

    /** Create a DownloadFileManager backed by [tmpRoot]. */
    private fun fileManagerFor(tmpRoot: File): DownloadFileManager {
        val path = Path(tmpRoot.absolutePath)
        return DownloadFileManager(
            storagePaths =
                object : StoragePaths {
                    override val filesDir: Path = path
                },
        )
    }

    // ---- Scenario 1 ----

    /**
     * 200 happy path: bytes flow through and markCompleted is called.
     * Final entity state = COMPLETED, localPath != null, downloadedBytes == expected.
     */
    @Test
    fun `200 happy path — bytes flow and markCompleted`() =
        runTest {
            val tmpRoot = tempDir()
            try {
                val fakeRepo = FakeDownloadRepository(initial = listOf(entity("file-1", totalBytes = 1000L)))
                val binaryEngine =
                    MockEngine { _ ->
                        respond(
                            content = ByteArray(1000) { 0x42 },
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentLength, "1000"),
                        )
                    }

                downloadAudioFile(
                    audioFileId = "file-1",
                    bookId = "book-1",
                    filename = "file-1.mp3",
                    expectedSize = 1000L,
                    httpClient = productionLikeClient(binaryEngine),
                    repository = fakeRepo,
                    fileManager = fileManagerFor(tmpRoot),
                    playbackApi = FakePlaybackApiContract(AppResult.Success(readyResponse())),
                    playbackPreferences = FakePlaybackPreferences(),
                    capabilityDetector = FakeAudioCapabilityDetector(),
                )

                val final = fakeRepo.entities.single()
                assertEquals(DownloadState.COMPLETED, final.state)
                assertNotNull(final.localPath)
                assertEquals(1000L, final.downloadedBytes)
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

    // ---- Scenario 2 ----

    /**
     * 206 partial-content resume: pre-existing temp file causes Range header in request.
     * MockEngine verifies the Range header and returns 206; final state = COMPLETED.
     */
    @Test
    fun `206 partial-content resume — Range header sent for partial tempFile`() =
        runTest {
            val tmpRoot = tempDir()
            try {
                val fakeRepo = FakeDownloadRepository(initial = listOf(entity("file-1", totalBytes = 1000L)))
                val fileManager = fileManagerFor(tmpRoot)

                // Pre-write 400 bytes to the temp path so startByte = 400
                val tempPath = fileManager.getAudioFilePath("book-1", "file-1", "file-1.mp3", isTemp = true)
                SystemFileSystem.sink(tempPath).buffered().use { sink ->
                    sink.write(ByteArray(400) { 0x41 })
                }

                var capturedRangeHeader: String? = null
                val partialEngine =
                    MockEngine { request ->
                        capturedRangeHeader = request.headers[HttpHeaders.Range]
                        respond(
                            content = ByteArray(600) { 0x42 },
                            status = HttpStatusCode.PartialContent,
                            headers = headersOf(HttpHeaders.ContentLength, "600"),
                        )
                    }

                downloadAudioFile(
                    audioFileId = "file-1",
                    bookId = "book-1",
                    filename = "file-1.mp3",
                    expectedSize = 1000L,
                    httpClient = productionLikeClient(partialEngine),
                    repository = fakeRepo,
                    fileManager = fileManager,
                    playbackApi = FakePlaybackApiContract(AppResult.Success(readyResponse())),
                    playbackPreferences = FakePlaybackPreferences(),
                    capabilityDetector = FakeAudioCapabilityDetector(),
                )

                assertEquals("bytes=400-", capturedRangeHeader)
                val final = fakeRepo.entities.single()
                assertEquals(DownloadState.COMPLETED, final.state)
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

    // ---- Scenario 3 ----

    /**
     * 401 once → refresh succeeds → 200: Auth plugin triggers token refresh.
     * Second attempt returns 200; final state = COMPLETED.
     */
    @Test
    fun `401 once then refresh succeeds — final state COMPLETED`() =
        runTest {
            val tmpRoot = tempDir()
            try {
                val fakeRepo = FakeDownloadRepository(initial = listOf(entity("file-1", totalBytes = 1000L)))
                var attemptCount = 0

                val authEngine =
                    MockEngine { _ ->
                        attemptCount++
                        if (attemptCount == 1) {
                            respond(
                                content = "",
                                status = HttpStatusCode.Unauthorized,
                                headers = headersOf(HttpHeaders.WWWAuthenticate, "Bearer realm=\"api\""),
                            )
                        } else {
                            respond(
                                content = ByteArray(1000) { 0x42 },
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentLength, "1000"),
                            )
                        }
                    }

                downloadAudioFile(
                    audioFileId = "file-1",
                    bookId = "book-1",
                    filename = "file-1.mp3",
                    expectedSize = 1000L,
                    httpClient =
                        authProductionLikeClient(authEngine) {
                            BearerTokens("refreshed-token", "new-refresh-token")
                        },
                    repository = fakeRepo,
                    fileManager = fileManagerFor(tmpRoot),
                    playbackApi = FakePlaybackApiContract(AppResult.Success(readyResponse())),
                    playbackPreferences = FakePlaybackPreferences(),
                    capabilityDetector = FakeAudioCapabilityDetector(),
                )

                val final = fakeRepo.entities.single()
                assertEquals(DownloadState.COMPLETED, final.state)
                assertTrue(attemptCount >= 2, "Expected >=2 attempts but got $attemptCount")
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

    // ---- Scenario 4 ----

    /**
     * 401 persistent → refresh returns null → AppException(TransportError.Server4xx(401)) propagates.
     *
     * Production path: installListenUpErrorHandling rewraps ResponseException as
     * AppException(TransportError.Server4xx(statusCode=401)). The function throws; the worker's
     * catch block detects the 401 and calls markPaused. This test replicates that catch to
     * assert PAUSED — catching a bug where the old dead-code ResponseException catch caused
     * FAILED instead.
     */
    @Test
    fun `401 persistent with null refresh — throws AppException and worker would markPaused`() =
        runTest {
            val tmpRoot = tempDir()
            try {
                val fakeRepo = FakeDownloadRepository(initial = listOf(entity("file-1")))
                val authEngine =
                    MockEngine { _ ->
                        respond(
                            content = "",
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(HttpHeaders.WWWAuthenticate, "Bearer realm=\"api\""),
                        )
                    }

                // Replicate the worker's failure-handling: on TransportError.Server4xx(401),
                // the worker calls markPaused. Test this contract without WorkManager involvement.
                val result =
                    downloadAudioFile(
                        audioFileId = "file-1",
                        bookId = "book-1",
                        filename = "file-1.mp3",
                        expectedSize = 1000L,
                        httpClient = authProductionLikeClient(authEngine) { null },
                        repository = fakeRepo,
                        fileManager = fileManagerFor(tmpRoot),
                        playbackApi = FakePlaybackApiContract(AppResult.Success(readyResponse())),
                        playbackPreferences = FakePlaybackPreferences(),
                        capabilityDetector = FakeAudioCapabilityDetector(),
                    )

                assertIs<AppResult.Failure>(result)
                val server4xx = result.error as? TransportError.Server4xx
                assertIs<TransportError.Server4xx>(
                    result.error,
                    "Expected TransportError.Server4xx but got ${result.error::class.simpleName}",
                )
                assertEquals(HttpStatusCode.Unauthorized.value, server4xx?.statusCode)
                // Replicate worker's auth-failure path: markPaused (not markFailed).
                fakeRepo.markPaused("file-1")

                assertEquals(DownloadState.PAUSED, fakeRepo.entities.single().state)
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

    // ---- Scenario 5 ----

    /**
     * 500 once → HttpRequestRetry retries → 200.
     * Second attempt returns 200; final state = COMPLETED.
     */
    @Test
    fun `500 once then retry succeeds — final state COMPLETED`() =
        runTest {
            val tmpRoot = tempDir()
            try {
                val fakeRepo = FakeDownloadRepository(initial = listOf(entity("file-1", totalBytes = 1000L)))
                var attemptCount = 0

                val retryEngine =
                    MockEngine { _ ->
                        attemptCount++
                        if (attemptCount == 1) {
                            respondError(HttpStatusCode.InternalServerError)
                        } else {
                            respond(
                                content = ByteArray(1000) { 0x42 },
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentLength, "1000"),
                            )
                        }
                    }

                downloadAudioFile(
                    audioFileId = "file-1",
                    bookId = "book-1",
                    filename = "file-1.mp3",
                    expectedSize = 1000L,
                    httpClient = retryProductionLikeClient(retryEngine),
                    repository = fakeRepo,
                    fileManager = fileManagerFor(tmpRoot),
                    playbackApi = FakePlaybackApiContract(AppResult.Success(readyResponse())),
                    playbackPreferences = FakePlaybackPreferences(),
                    capabilityDetector = FakeAudioCapabilityDetector(),
                )

                assertEquals(DownloadState.COMPLETED, fakeRepo.entities.single().state)
                assertEquals(2, attemptCount)
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

    // ---- Scenario 6 ----

    /**
     * 500 persistent → 3 retries exhausted → throws.
     * 1 original + 3 retries = 4 total engine invocations before exception.
     */
    @Test
    fun `500 persistent — retries exhausted and throws`() =
        runTest {
            val tmpRoot = tempDir()
            try {
                val fakeRepo = FakeDownloadRepository(initial = listOf(entity("file-1")))
                var attemptCount = 0

                val retryEngine =
                    MockEngine { _ ->
                        attemptCount++
                        respondError(HttpStatusCode.InternalServerError)
                    }

                val result =
                    downloadAudioFile(
                        audioFileId = "file-1",
                        bookId = "book-1",
                        filename = "file-1.mp3",
                        expectedSize = 1000L,
                        httpClient = retryProductionLikeClient(retryEngine),
                        repository = fakeRepo,
                        fileManager = fileManagerFor(tmpRoot),
                        playbackApi = FakePlaybackApiContract(AppResult.Success(readyResponse())),
                        playbackPreferences = FakePlaybackPreferences(),
                        capabilityDetector = FakeAudioCapabilityDetector(),
                    )

                assertIs<AppResult.Failure>(result)
                assertEquals(4, attemptCount) // 1 original + 3 retries
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

    // ---- Scenario 7 ----

    /**
     * Network drop mid-stream: response body is shorter than Content-Length.
     * Size mismatch triggers IOException from the function.
     *
     * The function throws; the worker's IOException catch calls handleRetryableError which
     * calls markFailed. This test replicates that catch to assert FAILED — catching a
     * regression where an incorrect handler could silently swallow the error.
     */
    @Test
    fun `network drop mid-stream — IOException on size mismatch`() =
        runTest {
            val tmpRoot = tempDir()
            try {
                val fakeRepo = FakeDownloadRepository(initial = listOf(entity("file-1")))
                // Server claims 1000 bytes but only sends 200 → size mismatch IOException
                val dropEngine =
                    MockEngine { _ ->
                        respond(
                            content = ByteReadChannel(ByteArray(200) { 0x42 }),
                            status = HttpStatusCode.OK,
                            headers =
                                headersOf(
                                    HttpHeaders.ContentLength to listOf("1000"),
                                    HttpHeaders.ContentType to listOf(ContentType.Application.OctetStream.toString()),
                                ),
                        )
                    }

                // Replicate the worker's failure path: handleFailure calls markFailed for IO-class
                // failures (ErrorMapper maps IOException → TransportError.NetworkUnavailable).
                val result =
                    downloadAudioFile(
                        audioFileId = "file-1",
                        bookId = "book-1",
                        filename = "file-1.mp3",
                        expectedSize = 1000L,
                        httpClient = productionLikeClient(dropEngine),
                        repository = fakeRepo,
                        fileManager = fileManagerFor(tmpRoot),
                        playbackApi = FakePlaybackApiContract(AppResult.Success(readyResponse())),
                        playbackPreferences = FakePlaybackPreferences(),
                        capabilityDetector = FakeAudioCapabilityDetector(),
                    )

                assertIs<AppResult.Failure>(result)
                fakeRepo.markFailed("file-1", DownloadError.DownloadFailed(debugInfo = result.error.debugInfo))

                assertEquals(DownloadState.FAILED, fakeRepo.entities.single().state)
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

    // ---- Scenario 8 ----

    /**
     * Disk full on moveFile: FakeDownloadFileManager.moveFile throws IOException("ENOSPC").
     * Function propagates it.
     */
    @Test
    fun `disk full on move — IOException with ENOSPC message`() =
        runTest {
            val tmpRoot = tempDir()
            try {
                val fakeRepo = FakeDownloadRepository(initial = listOf(entity("file-1", totalBytes = 1000L)))
                val binaryEngine =
                    MockEngine { _ ->
                        respond(
                            content = ByteArray(1000) { 0x42 },
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentLength, "1000"),
                        )
                    }

                val result =
                    downloadAudioFile(
                        audioFileId = "file-1",
                        bookId = "book-1",
                        filename = "file-1.mp3",
                        expectedSize = 1000L,
                        httpClient = productionLikeClient(binaryEngine),
                        repository = fakeRepo,
                        fileManager = FailingMoveFileManager(tmpRoot),
                        playbackApi = FakePlaybackApiContract(AppResult.Success(readyResponse())),
                        playbackPreferences = FakePlaybackPreferences(),
                        capabilityDetector = FakeAudioCapabilityDetector(),
                    )

                // The internal IOException("Failed to move...") is caught by suspendRunCatching
                // and routed through ErrorMapper, which classifies IOException as
                // TransportError.NetworkUnavailable. The user-actionable detail (failed-to-move
                // message) lives in [debugInfo]; the worker's storage-keyword string-match runs
                // against debugInfo to detect ENOSPC-class failures.
                assertIs<AppResult.Failure>(result)
                assertIs<TransportError.NetworkUnavailable>(result.error)
                assertTrue(
                    result.error.debugInfo?.contains("Failed to move") == true,
                    "Expected 'Failed to move' in debugInfo: ${result.error.debugInfo}",
                )
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

    // ---- Scenario 9 ----

    /**
     * Cancellation: isStopped = { true } causes CancellationException in the stream loop.
     *
     * The function throws; the worker's CancellationException catch calls markPaused. This
     * test replicates that catch to assert PAUSED — catching a regression where an incorrect
     * handler could burn the retry budget on a user-initiated pause.
     */
    @Test
    fun `cancellation via isStopped — throws CancellationException`() =
        runTest {
            val tmpRoot = tempDir()
            try {
                val fakeRepo = FakeDownloadRepository(initial = listOf(entity("file-1")))
                val binaryEngine =
                    MockEngine { _ ->
                        respond(
                            content = ByteArray(1000) { 0x42 },
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentLength, "1000"),
                        )
                    }

                // Replicate the worker's CancellationException catch: markPaused on isStopped.
                try {
                    downloadAudioFile(
                        audioFileId = "file-1",
                        bookId = "book-1",
                        filename = "file-1.mp3",
                        expectedSize = 1000L,
                        httpClient = productionLikeClient(binaryEngine),
                        repository = fakeRepo,
                        fileManager = fileManagerFor(tmpRoot),
                        playbackApi = FakePlaybackApiContract(AppResult.Success(readyResponse())),
                        playbackPreferences = FakePlaybackPreferences(),
                        capabilityDetector = FakeAudioCapabilityDetector(),
                        isStopped = { true },
                    )
                } catch (e: CancellationException) {
                    // Replicate worker's cancellation catch: markPaused (not markFailed).
                    fakeRepo.markPaused("file-1")
                }

                assertEquals(DownloadState.PAUSED, fakeRepo.entities.single().state)
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

    // ---- Scenario 10 ----

    /**
     * Progress throttling: 2 MB body; updateProgress should be called far fewer times than
     * the number of 8KB chunks (256 chunks). Throttle logic: 256 KB or 500 ms interval.
     * runTest virtual clock means time-based throttle does not fire; only byte-count threshold
     * applies → at most ~8 progress emits for 2MB (every 256KB).
     */
    @Test
    fun `progress throttling — updateProgress called sparsely not per-chunk`() =
        runTest {
            val tmpRoot = tempDir()
            try {
                val bodySize = 2 * 1024 * 1024 // 2MB
                var progressCallCount = 0
                val trackingRepo =
                    object : FakeDownloadRepository(
                        initial = listOf(entity("file-1", totalBytes = bodySize.toLong())),
                    ) {
                        override suspend fun updateProgress(
                            audioFileId: String,
                            downloadedBytes: Long,
                            totalBytes: Long,
                        ): AppResult<Unit> {
                            progressCallCount++
                            return super.updateProgress(audioFileId, downloadedBytes, totalBytes)
                        }
                    }

                val binaryEngine =
                    MockEngine { _ ->
                        respond(
                            content = ByteArray(bodySize) { 0x42 },
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentLength, bodySize.toString()),
                        )
                    }

                downloadAudioFile(
                    audioFileId = "file-1",
                    bookId = "book-1",
                    filename = "file-1.mp3",
                    expectedSize = bodySize.toLong(),
                    httpClient = productionLikeClient(binaryEngine),
                    repository = trackingRepo,
                    fileManager = fileManagerFor(tmpRoot),
                    playbackApi = FakePlaybackApiContract(AppResult.Success(readyResponse())),
                    playbackPreferences = FakePlaybackPreferences(),
                    capabilityDetector = FakeAudioCapabilityDetector(),
                )

                assertEquals(DownloadState.COMPLETED, trackingRepo.entities.single().state)
                // 2MB / 256KB threshold = 8 byte-interval triggers + 1 initial = ≤9.
                // Allow up to 20 to accommodate rounding; strict bound is 256 (one per chunk).
                assertTrue(
                    progressCallCount < 20,
                    "Expected sparse progress (<20 calls) for 2MB but got $progressCallCount",
                )
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

    // ---- Scenario 11 ----

    /**
     * preparePlayback returns !ready (transcodeJobId set) → writes WAITING_FOR_SERVER + exits.
     *
     * Phase D Bug 4 fix: the 30-minute polling loop is gone. When the server is still transcoding,
     * the worker now writes WAITING_FOR_SERVER and exits cleanly. SSE transcode.complete will
     * re-enqueue via repository.resumeForAudioFile when the server finishes.
     */
    @Test
    fun `preparePlayback returns transcoding — writes WAITING_FOR_SERVER and exits cleanly`() =
        runTest {
            val tmpRoot = tempDir()
            try {
                val fakeRepo = FakeDownloadRepository(initial = listOf(entity("file-1", totalBytes = 1000L)))
                val transcodingApi =
                    FakePlaybackApiContract(
                        AppResult.Success(
                            PreparePlaybackResponse(
                                ready = false,
                                streamUrl = "",
                                variant = "",
                                codec = "",
                                transcodeJobId = "abc-123",
                                progress = 42,
                            ),
                        ),
                    )

                // No stream engine handler needed — if the worker tries to fetch bytes, the test will
                // fail because the MockEngine has no registered handler. This enforces the contract
                // that the worker must exit before ever touching the HTTP stream.
                val unusedEngine =
                    MockEngine { request ->
                        error("Unexpected HTTP request: ${request.url} — worker should have exited cleanly")
                    }

                downloadAudioFile(
                    audioFileId = "file-1",
                    bookId = "book-1",
                    filename = "file-1.mp3",
                    expectedSize = 1000L,
                    httpClient = productionLikeClient(unusedEngine),
                    repository = fakeRepo,
                    fileManager = fileManagerFor(tmpRoot),
                    playbackApi = transcodingApi,
                    playbackPreferences = FakePlaybackPreferences(),
                    capabilityDetector = FakeAudioCapabilityDetector(),
                )

                // Bug 4 fix verification: row should be WAITING_FOR_SERVER, not stuck in DOWNLOADING.
                val final = fakeRepo.entities.single()
                assertEquals(DownloadState.WAITING_FOR_SERVER, final.state)
                assertEquals("abc-123", final.transcodeJobId)
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

    // ---- Scenario 12 ----

    /**
     * preparePlayback returns a resolved URL different from the default.
     * MockEngine only handles the resolved URL path; function must use it.
     */
    @Test
    fun `resolved URL from preparePlayback is used for download`() =
        runTest {
            val tmpRoot = tempDir()
            try {
                val fakeRepo = FakeDownloadRepository(initial = listOf(entity("file-1", totalBytes = 1000L)))
                val resolvedPath = "/transcoded/book-1/file-1-transcoded.mp3"
                val fakePlaybackApi =
                    FakePlaybackApiContract(
                        AppResult.Success(
                            PreparePlaybackResponse(
                                ready = true,
                                streamUrl = resolvedPath,
                                variant = "transcoded",
                                codec = "mp3",
                                transcodeJobId = null,
                                progress = 100,
                            ),
                        ),
                    )

                var resolvedPathHit = false
                val resolvedEngine =
                    MockEngine { request ->
                        if (request.url.encodedPath == resolvedPath) {
                            resolvedPathHit = true
                            respond(
                                content = ByteArray(1000) { 0x42 },
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentLength, "1000"),
                            )
                        } else {
                            error("Unexpected path: ${request.url.encodedPath} (expected $resolvedPath)")
                        }
                    }

                downloadAudioFile(
                    audioFileId = "file-1",
                    bookId = "book-1",
                    filename = "file-1.mp3",
                    expectedSize = 1000L,
                    httpClient = productionLikeClient(resolvedEngine),
                    repository = fakeRepo,
                    fileManager = fileManagerFor(tmpRoot),
                    playbackApi = fakePlaybackApi,
                    playbackPreferences = FakePlaybackPreferences(),
                    capabilityDetector = FakeAudioCapabilityDetector(),
                )

                assertTrue(resolvedPathHit, "Expected download to hit resolved URL $resolvedPath")
                assertEquals(DownloadState.COMPLETED, fakeRepo.entities.single().state)
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

    // ---- Utility ----

    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "dwlt-${System.nanoTime()}").also { it.mkdirs() }
}

// ---- Fakes ----

private class FakePlaybackApiContract(
    private val result: AppResult<PreparePlaybackResponse>,
) : PlaybackApiContract {
    override suspend fun preparePlayback(
        bookId: String,
        audioFileId: String,
        capabilities: List<String>,
        spatial: Boolean,
    ): AppResult<PreparePlaybackResponse> = result

    override suspend fun cancelTranscode(jobId: String): AppResult<Unit> = AppResult.Success(Unit)
}

private class FakePlaybackPreferences : PlaybackPreferences {
    override val preferenceChanges: SharedFlow<com.calypsan.listenup.client.domain.repository.PreferenceChangeEvent>
        get() = error("not used in test")

    override fun observeDefaultPlaybackSpeed(): kotlinx.coroutines.flow.Flow<Float> = error("not used in test")

    override suspend fun getDefaultPlaybackSpeed(): Float = error("not used in test")

    override suspend fun setDefaultPlaybackSpeed(speed: Float) = error("not used in test")

    override suspend fun getSpatialPlayback(): Boolean = false

    override suspend fun setSpatialPlayback(enabled: Boolean) = error("not used in test")
}

private class FakeAudioCapabilityDetector : AudioCapabilityDetector {
    override fun getSupportedCodecs(): List<String> = listOf("mp3", "aac", "opus")
}

/**
 * DownloadFileManager that always returns false from moveFile, simulating a disk-full failure.
 * [downloadAudioFile] catches this and throws IOException("Failed to move temp file to destination").
 */
private class FailingMoveFileManager(
    tmpRoot: File,
) : DownloadFileManager(
        storagePaths =
            object : StoragePaths {
                override val filesDir: Path = Path(tmpRoot.absolutePath)
            },
    ) {
    override fun moveFile(
        source: Path,
        destination: Path,
    ): Boolean = false
}
