package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.BackupRoutePaths
import com.calypsan.listenup.api.BackupService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.error.BackupError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.core.FileSource
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString

/**
 * Unit tests for [BackupRepositoryImpl].
 *
 * Uses mokkery to mock [BackupService] — matching the pattern in [AuthRepositoryImplTest].
 * Each test verifies that:
 *  - suspend methods forward to the service and convert [WireAppResult] → [AppResult], and
 *  - [observeProgress] unwraps [RpcEvent.Data] into bare [BackupEvent]s while silently
 *    dropping [RpcEvent.Error] and [RpcEvent.Complete].
 *
 * [uploadBackup] is tested via a [MockEngine] that captures the outgoing request: confirms
 * the call targets [BackupRoutePaths.UPLOAD] and deserialises the raw (un-enveloped)
 * [BackupSummary] from the response body.
 */
class BackupRepositoryImplTest :
    FunSpec({

        // ── helpers ──────────────────────────────────────────────────────────────

        fun stubSummary(id: String = "bk-1") =
            BackupSummary(
                id = BackupId(id),
                createdAt = 1_000L,
                sizeBytes = 42_000L,
                includesImages = true,
                schemaVersion = "1",
                appVersion = "0.1.0",
                bookCount = 10,
                userCount = 1,
            )

        fun stubRestoreResult(id: String = "bk-1") =
            RestoreResult(
                restoredFrom = BackupId(id),
                includedImages = true,
                schemaMigratedFrom = "1",
                schemaMigratedTo = "1",
            )

        /**
         * Minimal [ApiClientFactory] whose [getClient] returns a [HttpClient] backed by the
         * supplied [MockEngine]. All other methods are not needed for the upload path.
         */
        class FakeApiClientFactory(
            private val engine: MockEngine,
        ) : ApiClientFactory {
            override suspend fun getClient(): HttpClient =
                HttpClient(engine) {
                    install(ContentNegotiation) { json(contractJson) }
                }

            override suspend fun warmUp() = Unit

            override suspend fun invalidate() = Unit

            override suspend fun invalidateRequestClientOnly() = Unit
        }

        /**
         * Variant of [FakeApiClientFactory] that installs [HttpTimeout] so the download
         * path's `timeout { }` block resolves without throwing [IllegalStateException].
         * Kept separate from [FakeApiClientFactory] to avoid breaking the upload tests,
         * which use [io.ktor.client.request.forms.ChannelProvider] in a way that makes
         * [HttpTimeout] race against the [MockEngine] response time.
         */
        class FakeApiClientFactoryWithTimeout(
            private val engine: MockEngine,
        ) : ApiClientFactory {
            override suspend fun getClient(): HttpClient =
                HttpClient(engine) {
                    install(ContentNegotiation) { json(contractJson) }
                    install(HttpTimeout)
                }

            override suspend fun warmUp() = Unit

            override suspend fun invalidate() = Unit

            override suspend fun invalidateRequestClientOnly() = Unit
        }

        fun buildRepo(service: BackupService): BackupRepositoryImpl =
            BackupRepositoryImpl(
                channel = RpcChannel.forTest(service),
                clientFactory = mock(MockMode.autofill),
            )

        fun buildRepoWithEngine(
            service: BackupService,
            engine: MockEngine,
        ): BackupRepositoryImpl =
            BackupRepositoryImpl(
                channel = RpcChannel.forTest(service),
                clientFactory = FakeApiClientFactory(engine),
            )

        fun buildRepoWithTimeoutEngine(
            service: BackupService,
            engine: MockEngine,
        ): BackupRepositoryImpl =
            BackupRepositoryImpl(
                channel = RpcChannel.forTest(service),
                clientFactory = FakeApiClientFactoryWithTimeout(engine),
            )

        /** Minimal in-memory [FileSource] with known bytes — never hits the filesystem. */
        fun fakeFileSource(bytes: ByteArray = ByteArray(8) { it.toByte() }): FileSource =
            object : FileSource {
                override val filename = "test.listenup.zip"
                override val size: Long = bytes.size.toLong()

                override fun openChannel(): ByteReadChannel = ByteReadChannel(bytes)
            }

        // ── uploadBackup ──────────────────────────────────────────────────────

        test("uploadBackup POSTs to BackupRoutePaths.UPLOAD and returns the deserialized BackupSummary") {
            runTest {
                val expected = stubSummary("upload-1")
                val responseJson = contractJson.encodeToString(expected)

                var capturedPath: String? = null
                var capturedMethod: HttpMethod? = null

                val engine =
                    MockEngine { request ->
                        capturedPath = request.url.encodedPath
                        capturedMethod = request.method
                        respond(
                            content = responseJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }

                val svc = mock<BackupService>()
                val result = buildRepoWithEngine(svc, engine).uploadBackup(fakeFileSource())

                capturedPath shouldBe BackupRoutePaths.UPLOAD
                capturedMethod shouldBe HttpMethod.Post
                result.shouldBeInstanceOf<AppResult.Success<BackupSummary>>()
                result.data shouldBe expected
            }
        }

        test("uploadBackup sends a multipart/form-data request body") {
            runTest {
                val expected = stubSummary()
                val responseJson = contractJson.encodeToString(expected)

                var capturedContentType: String? = null

                val engine =
                    MockEngine { request ->
                        capturedContentType = request.body.contentType?.toString()
                        respond(
                            content = responseJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }

                val svc = mock<BackupService>()
                buildRepoWithEngine(svc, engine).uploadBackup(fakeFileSource())

                // Ktor's submitFormWithBinaryData always sends multipart/form-data.
                capturedContentType shouldContain "multipart/form-data"
            }
        }

        // ── downloadBackup ────────────────────────────────────────────────────

        test("downloadBackup GETs BackupRoutePaths.downloadFor(id) and streams the body into the sink") {
            runTest {
                val payload = ByteArray(4096) { (it % 256).toByte() }
                var capturedPath: String? = null
                var capturedMethod: HttpMethod? = null

                val engine =
                    MockEngine { request ->
                        capturedPath = request.url.encodedPath
                        capturedMethod = request.method
                        respond(
                            content = payload,
                            status = HttpStatusCode.OK,
                            headers =
                                headersOf(
                                    HttpHeaders.ContentType,
                                    ContentType.Application.OctetStream.toString(),
                                ),
                        )
                    }

                val svc = mock<BackupService>()
                val sink = Buffer()
                val result = buildRepoWithTimeoutEngine(svc, engine).downloadBackup(BackupId("bk-7"), sink)

                capturedMethod shouldBe HttpMethod.Get
                capturedPath shouldBe BackupRoutePaths.downloadFor("bk-7")
                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                sink.readByteArray() shouldBe payload
            }
        }

        // ── createBackup ──────────────────────────────────────────────────────

        test("createBackup returns Success wrapping the BackupSummary on wire success") {
            runTest {
                val summary = stubSummary()
                val svc = mock<BackupService>()
                everySuspend { svc.createBackup(true) } returns WireAppResult.Success(summary)

                val result = buildRepo(svc).createBackup(includeImages = true)

                result.shouldBeInstanceOf<AppResult.Success<BackupSummary>>()
                result.data shouldBe summary
            }
        }

        test("createBackup returns Failure on wire failure") {
            runTest {
                val svc = mock<BackupService>()
                val error = BackupError.SnapshotFailed()
                everySuspend { svc.createBackup(false) } returns WireAppResult.Failure(error)

                val result = buildRepo(svc).createBackup(includeImages = false)

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error shouldBe error
            }
        }

        // ── listBackups ───────────────────────────────────────────────────────

        test("listBackups returns Success wrapping the list on wire success") {
            runTest {
                val summaries = listOf(stubSummary("bk-1"), stubSummary("bk-2"))
                val svc = mock<BackupService>()
                everySuspend { svc.listBackups() } returns WireAppResult.Success(summaries)

                val result = buildRepo(svc).listBackups()

                result.shouldBeInstanceOf<AppResult.Success<List<BackupSummary>>>()
                result.data shouldBe summaries
            }
        }

        // ── deleteBackup ──────────────────────────────────────────────────────

        test("deleteBackup returns AppResult.Success(Unit) on wire success") {
            runTest {
                val svc = mock<BackupService>()
                everySuspend { svc.deleteBackup(BackupId("bk-1")) } returns WireAppResult.Success(Unit)

                val result = buildRepo(svc).deleteBackup(BackupId("bk-1"))

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("deleteBackup returns Failure on wire failure") {
            runTest {
                val svc = mock<BackupService>()
                val error = BackupError.BackupNotFound()
                everySuspend { svc.deleteBackup(BackupId("bk-missing")) } returns WireAppResult.Failure(error)

                val result = buildRepo(svc).deleteBackup(BackupId("bk-missing"))

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error shouldBe error
            }
        }

        // ── restoreBackup ─────────────────────────────────────────────────────

        test("restoreBackup returns Success wrapping RestoreResult on wire success") {
            runTest {
                val restoreResult = stubRestoreResult()
                val svc = mock<BackupService>()
                everySuspend { svc.restoreBackup(BackupId("bk-1")) } returns WireAppResult.Success(restoreResult)

                val result = buildRepo(svc).restoreBackup(BackupId("bk-1"))

                result.shouldBeInstanceOf<AppResult.Success<RestoreResult>>()
                result.data shouldBe restoreResult
            }
        }

        // ── observeProgress ───────────────────────────────────────────────────

        test("observeProgress unwraps RpcEvent.Data into bare BackupEvents") {
            runTest {
                val hotFlow = MutableSharedFlow<RpcEvent<BackupEvent>>()
                val svc = mock<BackupService>()
                every { svc.observeProgress() } returns hotFlow

                buildRepo(svc).observeProgress().test {
                    hotFlow.emit(RpcEvent.Data(BackupEvent.DbSnapshotting))
                    awaitItem() shouldBe BackupEvent.DbSnapshotting

                    hotFlow.emit(RpcEvent.Data(BackupEvent.Finalizing))
                    awaitItem() shouldBe BackupEvent.Finalizing

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeProgress silently drops RpcEvent.Error and RpcEvent.Complete") {
            runTest {
                val progressFlow =
                    flow {
                        emit(RpcEvent.Data<BackupEvent>(BackupEvent.DbSnapshotting))
                        emit(RpcEvent.Error(InternalError()))
                        emit(RpcEvent.Complete)
                        emit(RpcEvent.Data(BackupEvent.Finalizing))
                    }

                val svc = mock<BackupService>()
                every { svc.observeProgress() } returns progressFlow

                val events = mutableListOf<BackupEvent>()
                buildRepo(svc).observeProgress().collect { events.add(it) }

                events shouldBe listOf(BackupEvent.DbSnapshotting, BackupEvent.Finalizing)
            }
        }
    })
