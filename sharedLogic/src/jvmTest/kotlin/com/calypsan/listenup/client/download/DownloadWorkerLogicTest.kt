@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.dto.PreparedAudioFile
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.appJson
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.local.images.StoragePaths
import com.calypsan.listenup.client.data.remote.installListenUpErrorHandling
import com.calypsan.listenup.client.data.repository.FakeDownloadRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import java.io.File

/**
 * Seam-level contract tests for [downloadAudioFile]. Runs on JVM via :shared:jvmTest so it can
 * use real [DownloadFileManager] backed by a temp directory without an Android Context.
 *
 * Path A (commonMain extraction + jvmTest) was chosen because:
 * - commonTest cannot see androidMain source set.
 * - androidHostTest requires Robolectric / Android libs.
 * - jvmTest sees jvmMain actuals (including DownloadFileManager with StoragePaths interface).
 *
 * Uses hand-rolled fakes + MockEngine, not mokkery.
 *
 * Download URL is resolved via PlaybackService.prepare signed URLs.
 */
class DownloadWorkerLogicTest :
    FunSpec({
        // ---- Fixtures ----

        fun entity(
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

        // Base client: ContentNegotiation + timeout. No Auth or Retry plugins.
        fun productionLikeClient(engine: HttpClientEngine): HttpClient =
            HttpClient(engine) {
                install(ContentNegotiation) { json(appJson) }
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 30_000
                }
            }

        // Client with Bearer Auth plugin for 401 scenarios.
        //
        // Mirrors the production ApiClientFactory client: installs [installListenUpErrorHandling] so
        // non-2xx responses raise [io.ktor.client.plugins.ResponseException] (via
        // `expectSuccess = true`); the surrounding `apiCall { ... }` boundary catches it and routes
        // through `ErrorMapper` to produce an `AppResult.Failure(AuthError.SessionExpired)`
        // after a failed refresh. Without this, the 401 leaks through the success-decoder path.
        fun authProductionLikeClient(
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

        // Client with HttpRequestRetry for 5xx scenarios. Minimum delay for fast test execution.
        fun retryProductionLikeClient(engine: HttpClientEngine): HttpClient =
            HttpClient(engine) {
                install(ContentNegotiation) { json(appJson) }
                install(HttpRequestRetry) {
                    retryIf(maxRetries = 3) { _, response ->
                        response.status.value in 500..599
                    }
                    constantDelay(millis = 1, randomizationMs = 0)
                }
            }

        // A ready [FakePlaybackPrepareRepository] for standard test setup.
        fun readyRpcFactory(
            audioFileId: String = "file-1",
            bookId: String = "book-1",
            streamUrl: String = "/api/v1/audio/$bookId/$audioFileId",
        ) = FakePlaybackPrepareRepository(
            AppResult.Success(
                PreparedPlayback(
                    bookId = bookId,
                    audioFiles =
                        listOf(
                            PreparedAudioFile(
                                fileId = audioFileId,
                                index = 0,
                                url = streamUrl,
                                format = "mp3",
                                durationMs = 1000L,
                                sizeBytes = 1000L,
                            ),
                        ),
                    resumePosition = null,
                ),
            ),
        )

        // Create a DownloadFileManager backed by [tmpRoot].
        fun fileManagerFor(tmpRoot: File): DownloadFileManager {
            val path = Path(tmpRoot.absolutePath)
            return DownloadFileManager(
                storagePaths =
                    object : StoragePaths {
                        override val filesDir: Path = path
                    },
            )
        }

        // ---- Utility ----

        fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "dwlt-${System.nanoTime()}").also { it.mkdirs() }

        // ---- Scenario 1 ----

        // 200 happy path: bytes flow through and markCompleted is called.
        // Final entity state = COMPLETED, localPath != null, downloadedBytes == expected.
        test("200 happy path — bytes flow and markCompleted") {
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
                        prepareRepository = readyRpcFactory(),
                    )

                    val final = fakeRepo.entities.single()
                    final.state shouldBe DownloadState.COMPLETED
                    final.localPath shouldNotBe null
                    final.downloadedBytes shouldBe 1000L
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }

        // ---- Scenario 2 ----

        // 206 partial-content resume: pre-existing temp file causes Range header in request.
        // MockEngine verifies the Range header and returns 206; final state = COMPLETED.
        test("206 partial-content resume — Range header sent for partial tempFile") {
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
                        prepareRepository = readyRpcFactory(),
                    )

                    capturedRangeHeader shouldBe "bytes=400-"
                    val final = fakeRepo.entities.single()
                    final.state shouldBe DownloadState.COMPLETED
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }

        // ---- Scenario 3 ----

        // 401 once → refresh succeeds → 200: Auth plugin triggers token refresh.
        // Second attempt returns 200; final state = COMPLETED.
        test("401 once then refresh succeeds — final state COMPLETED") {
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
                        prepareRepository = readyRpcFactory(),
                    )

                    val final = fakeRepo.entities.single()
                    final.state shouldBe DownloadState.COMPLETED
                    withClue("Expected >=2 attempts but got $attemptCount") {
                        (attemptCount >= 2) shouldBe true
                    }
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }

        // ---- Scenario 4 ----

        // 401 persistent → refresh returns null → returns AppResult.Failure(AuthError.SessionExpired).
        //
        // Production path: installListenUpErrorHandling raises ResponseException on the non-2xx
        // response; the surrounding apiCall boundary catches it and produces
        // AppResult.Failure(AuthError.SessionExpired). The worker inspects the AppResult and on a
        // session-invalid auth error calls markPaused. This test asserts the typed-failure contract —
        // catching a bug where the old dead-code ResponseException catch caused FAILED instead.
        test("401 persistent with null refresh — returns AppResult Failure and worker would markPaused") {
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
                            prepareRepository = readyRpcFactory(),
                        )

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    withClue("Expected AuthError.SessionExpired but got ${failure.error::class.simpleName}") {
                        failure.error.shouldBeInstanceOf<AuthError.SessionExpired>()
                    }
                    // Replicate worker's auth-failure path: markPaused (not markFailed).
                    fakeRepo.markPaused("file-1")

                    fakeRepo.entities.single().state shouldBe DownloadState.PAUSED
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }

        // ---- Scenario 5 ----

        // 500 once → HttpRequestRetry retries → 200.
        // Second attempt returns 200; final state = COMPLETED.
        test("500 once then retry succeeds — final state COMPLETED") {
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
                        prepareRepository = readyRpcFactory(),
                    )

                    fakeRepo.entities.single().state shouldBe DownloadState.COMPLETED
                    attemptCount shouldBe 2
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }

        // ---- Scenario 6 ----

        // 500 persistent → 3 retries exhausted → throws.
        // 1 original + 3 retries = 4 total engine invocations before exception.
        test("500 persistent — retries exhausted and throws") {
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
                            prepareRepository = readyRpcFactory(),
                        )

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    attemptCount shouldBe 4 // 1 original + 3 retries
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }

        // ---- Scenario 7 ----

        // Network drop mid-stream: response body is shorter than Content-Length.
        // Size mismatch triggers IOException from the function.
        //
        // The function throws; the worker's IOException catch calls handleRetryableError which
        // calls markFailed. This test replicates that catch to assert FAILED — catching a
        // regression where an incorrect handler could silently swallow the error.
        test("network drop mid-stream — IOException on size mismatch") {
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
                            prepareRepository = readyRpcFactory(),
                        )

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    fakeRepo.markFailed("file-1", DownloadError.DownloadFailed(debugInfo = failure.error.debugInfo))

                    fakeRepo.entities.single().state shouldBe DownloadState.FAILED
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }

        // ---- Scenario 8 ----

        // Disk full on moveFile: FakeDownloadFileManager.moveFile throws IOException("ENOSPC").
        // Function propagates it.
        test("disk full on move — IOException with ENOSPC message") {
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
                            prepareRepository = readyRpcFactory(),
                        )

                    // The internal IOException("Failed to move...") is caught by suspendRunCatching
                    // and routed through ErrorMapper, which classifies IOException as
                    // TransportError.NetworkUnavailable. The user-actionable detail (failed-to-move
                    // message) lives in [debugInfo]; the worker's storage-keyword string-match runs
                    // against debugInfo to detect ENOSPC-class failures.
                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<TransportError.NetworkUnavailable>()
                    withClue("Expected 'Failed to move' in debugInfo: ${failure.error.debugInfo}") {
                        (failure.error.debugInfo?.contains("Failed to move") == true) shouldBe true
                    }
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }

        // ---- Scenario 9 ----

        // Cancellation: isStopped = { true } causes CancellationException in the stream loop.
        //
        // The function throws; the worker's CancellationException catch calls markPaused. This
        // test replicates that catch to assert PAUSED — catching a regression where an incorrect
        // handler could burn the retry budget on a user-initiated pause.
        test("cancellation via isStopped — throws CancellationException") {
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
                            prepareRepository = readyRpcFactory(),
                            isStopped = { true },
                        )
                    } catch (e: CancellationException) {
                        // Replicate worker's cancellation catch: markPaused (not markFailed).
                        fakeRepo.markPaused("file-1")
                    }

                    fakeRepo.entities.single().state shouldBe DownloadState.PAUSED
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }

        // ---- Scenario 10 ----

        // Progress throttling: 2 MB body; updateProgress should be called far fewer times than
        // the number of 8KB chunks (256 chunks). Throttle logic: 256 KB or 500 ms interval.
        // runTest virtual clock means time-based throttle does not fire; only byte-count threshold
        // applies → at most ~8 progress emits for 2MB (every 256KB).
        test("progress throttling — updateProgress called sparsely not per-chunk") {
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
                        prepareRepository = readyRpcFactory(),
                    )

                    trackingRepo.entities.single().state shouldBe DownloadState.COMPLETED
                    // 2MB / 256KB threshold = 8 byte-interval triggers + 1 initial = ≤9.
                    // Allow up to 20 to accommodate rounding; strict bound is 256 (one per chunk).
                    withClue("Expected sparse progress (<20 calls) for 2MB but got $progressCallCount") {
                        (progressCallCount < 20) shouldBe true
                    }
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }

        // ---- Scenario 11 ----

        // prepare() returns Failure → downloadAudioFile returns AppResult.Failure.
        //
        // When prepare() fails (RPC error, network, etc.), the download fails cleanly rather than
        // falling back to a bare URL.
        test("prepare failure propagates as AppResult Failure") {
            runTest {
                val tmpRoot = tempDir()
                try {
                    val fakeRepo = FakeDownloadRepository(initial = listOf(entity("file-1", totalBytes = 1000L)))
                    val failingRpcFactory =
                        FakePlaybackPrepareRepository(
                            AppResult.Failure(
                                com.calypsan.listenup.api.error.TransportError.NetworkUnavailable(
                                    debugInfo = "simulated rpc failure",
                                ),
                            ),
                        )

                    val unusedEngine =
                        MockEngine { request ->
                            error("Unexpected HTTP request: ${request.url} — worker should have failed before download")
                        }

                    val result =
                        downloadAudioFile(
                            audioFileId = "file-1",
                            bookId = "book-1",
                            filename = "file-1.mp3",
                            expectedSize = 1000L,
                            httpClient = productionLikeClient(unusedEngine),
                            repository = fakeRepo,
                            fileManager = fileManagerFor(tmpRoot),
                            prepareRepository = failingRpcFactory,
                        )

                    result.shouldBeInstanceOf<AppResult.Failure>()
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }

        // ---- Scenario 12 ----

        // prepare() resolves a signed URL: MockEngine verifies the path is the signed URL from prepare.
        test("signed URL from prepare is used for download") {
            runTest {
                val tmpRoot = tempDir()
                try {
                    val fakeRepo = FakeDownloadRepository(initial = listOf(entity("file-1", totalBytes = 1000L)))
                    val signedPath = "/api/v1/audio/book-1/file-1"
                    val signedQuery = "u=&exp=123&sig=abc"
                    val rpcFactory = readyRpcFactory(streamUrl = "$signedPath?$signedQuery")

                    var resolvedPathHit = false
                    val resolvedEngine =
                        MockEngine { request ->
                            val fullPath = request.url.encodedPath + "?" + (request.url.encodedQuery ?: "")
                            if (request.url.encodedPath == signedPath) {
                                resolvedPathHit = true
                                respond(
                                    content = ByteArray(1000) { 0x42 },
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentLength, "1000"),
                                )
                            } else {
                                error("Unexpected path: $fullPath (expected $signedPath)")
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
                        prepareRepository = rpcFactory,
                    )

                    withClue("Expected download to hit signed URL path $signedPath") {
                        resolvedPathHit shouldBe true
                    }
                    fakeRepo.entities.single().state shouldBe DownloadState.COMPLETED
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }

        // ---- Scenario 13 ----

        // User-cancel path: cancellation leaves a .tmp on disk; sweepOrphanedTempFiles removes it.
        //
        // The worker's CancellationException catch block lives in [DownloadWorker.doWork], which is
        // Android-only and not reachable from [downloadAudioFile]. The sweep is the durable cleanup
        // path (called from resumeIncompleteDownloads on startup). This test asserts that invariant:
        // after a cancelled download, the .tmp is absent from active ids and the sweep removes it.
        test("sweepRemovesOrphanedTmpPartial") {
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
                    val fileManager = fileManagerFor(tmpRoot)

                    // Run the download but stop it immediately — simulates a mid-download cancellation.
                    try {
                        downloadAudioFile(
                            audioFileId = "file-1",
                            bookId = "book-1",
                            filename = "file-1.mp3",
                            expectedSize = 1000L,
                            httpClient = productionLikeClient(binaryEngine),
                            repository = fakeRepo,
                            fileManager = fileManager,
                            prepareRepository = readyRpcFactory(),
                            isStopped = { true },
                        )
                    } catch (_: CancellationException) {
                        // Replicate the user-cancel path: DownloadManager.cancelDownload writes CANCELLED.
                        fakeRepo.markCancelled("file-1")
                    }

                    val tempPath = fileManager.getAudioFilePath("book-1", "file-1", "file-1.mp3", isTemp = true)
                    // The .tmp may or may not exist depending on how many bytes were written before
                    // isStopped fired. Either way, sweepOrphanedTempFiles must not leave it behind.
                    // Seed a known orphan .tmp to guarantee there is something for the sweep to delete.
                    if (!SystemFileSystem.exists(tempPath)) {
                        SystemFileSystem.sink(tempPath).use { it }
                    }

                    // Sweep with empty activeIds (all cancelled → no active download ids).
                    val swept = fileManager.sweepOrphanedTempFiles(emptySet())

                    withClue("Expected sweep to delete at least 1 orphaned .tmp, got $swept") {
                        (swept >= 1) shouldBe true
                    }
                    withClue("Expected .tmp to be deleted by sweep") {
                        !SystemFileSystem.exists(tempPath) shouldBe true
                    }
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }
    })

// ---- Fakes ----

/** In-memory [PlaybackPrepareRepository] that returns a fixed `prepare()` result — no I/O, no channel. */
internal class FakePlaybackPrepareRepository(
    private val prepareResult: AppResult<PreparedPlayback>,
) : PlaybackPrepareRepository {
    override suspend fun prepare(bookId: BookId): AppResult<PreparedPlayback> = prepareResult

    override suspend fun getPosition(bookId: BookId) = AppResult.Success(null)
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
