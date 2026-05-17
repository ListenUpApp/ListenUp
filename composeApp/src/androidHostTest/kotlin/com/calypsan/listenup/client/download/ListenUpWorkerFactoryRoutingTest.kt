package com.calypsan.listenup.client.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.FileSource
import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.client.data.remote.ABSImportApiContract
import com.calypsan.listenup.client.data.remote.ABSImportBook
import com.calypsan.listenup.client.data.remote.ABSImportResponse
import com.calypsan.listenup.client.data.remote.ABSImportSummary
import com.calypsan.listenup.client.data.remote.ABSImportUser
import com.calypsan.listenup.client.data.remote.ABSSessionsResponse
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.data.remote.ImportSessionsResult
import com.calypsan.listenup.client.data.remote.MappingFilter
import com.calypsan.listenup.client.data.remote.SessionStatusFilter
import com.calypsan.listenup.client.data.remote.UploadABSBackupResponse
import com.calypsan.listenup.client.data.remote.UserSearchResult
import com.calypsan.listenup.client.data.remote.model.AnalysisStatusResponse
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSRequest
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSResponse
import com.calypsan.listenup.client.data.remote.model.AsyncAnalyzeResponse
import com.calypsan.listenup.client.data.remote.model.BackupResponse
import com.calypsan.listenup.client.data.remote.model.ImportABSRequest
import com.calypsan.listenup.client.data.remote.model.ImportABSResponse
import com.calypsan.listenup.client.data.remote.model.RebuildProgressResponse
import com.calypsan.listenup.client.data.remote.model.RestoreRequest
import com.calypsan.listenup.client.data.remote.model.RestoreResponse
import com.calypsan.listenup.client.data.remote.model.ValidationResponse
import com.calypsan.listenup.client.upload.ABSUploadWorker
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Routing coverage for [ListenUpWorkerFactory.createWorker].
 *
 * Verifies the `when (workerClassName)` dispatch: each known class name routes to
 * the correct [ListenableWorker] subtype, and an unknown name produces `null`.
 *
 * [WorkerParameters] is an Android framework type that requires Robolectric to
 * instantiate properly; this test uses [RobolectricTestRunner] + JUnit4, consistent
 * with [PlaybackErrorHandlerTest] and [DeepLinkParserTest]. The `junit-vintage-engine`
 * on the classpath keeps these discoverable alongside Kotest specs.
 *
 * Coverage gap — [DownloadWorker] branch:
 * `createWorker` for DownloadWorker calls `runBlocking { apiClientFactory.value.getClient() }`,
 * which invokes [com.calypsan.listenup.client.data.remote.ApiClientFactory.createClient].
 * [ApiClientFactory] is a concrete class (not an interface) in `:shared`, so it cannot
 * be subclassed here. Instantiating it requires faking
 * [com.calypsan.listenup.client.domain.repository.AuthSession] and
 * [com.calypsan.listenup.client.domain.repository.ServerConfig] — both large interfaces — plus
 * an OkHttp engine on the classpath. Rather than importing `ktor-client-mock` (not currently
 * in androidHostTest) or adding another wide fake, this branch is left for a follow-up that
 * adds `ktor-client-mock` to androidHostTest.
 *
 * WorkerParameters extraction:
 * [WorkerParameters] is a framework-internal type with no public constructor. To obtain a valid
 * instance we build a [FakeWorker] via [TestListenableWorkerBuilder], then read the
 * `mWorkerParams` field declared on [ListenableWorker] (the abstract superclass) via reflection.
 * We look up the field on [ListenableWorker] directly — not on [FakeWorker] — because
 * [Class.getDeclaredField] does not traverse the class hierarchy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListenUpWorkerFactoryRoutingTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ─────────────────────── unknown class name → null ───────────────────────

    @Test
    fun `createWorker returns null for an unknown worker class name`() {
        val factory = buildFactoryWithSentinelDeps()
        val params = fakeWorkerParams()
        val result = factory.createWorker(context, "com.example.ThisWorkerDoesNotExist", params)
        result shouldBe null
    }

    // ─────────────────────── ABSUploadWorker routing ─────────────────────────

    @Test
    fun `createWorker routes ABSUploadWorker class name to an ABSUploadWorker instance`() {
        val factory = buildFactoryWithABSApiDeps()
        val params = fakeWorkerParams()
        val result =
            factory.createWorker(
                context,
                ABSUploadWorker::class.java.name,
                params,
            )
        result shouldNotBe null
        result.shouldBeInstanceOf<ABSUploadWorker>()
    }

    // ─────────────────────── factory builders ────────────────────────────────

    /**
     * Factory where ALL dependencies are sentinel lazies that throw on access.
     *
     * Used for the `null` routing branch, which returns without touching any dep.
     * If any dep is accidentally accessed the sentinel fires and the test fails.
     */
    private fun buildFactoryWithSentinelDeps(): ListenUpWorkerFactory =
        ListenUpWorkerFactory(
            downloadRepository = lazy { error("downloadRepository should not be accessed") },
            fileManager = lazy { error("fileManager should not be accessed") },
            apiClientFactory = lazy { error("apiClientFactory should not be accessed") },
            playbackPreferences = lazy { error("playbackPreferences should not be accessed") },
            playbackApi = lazy { error("playbackApi should not be accessed") },
            capabilityDetector = lazy { error("capabilityDetector should not be accessed") },
            backupApi = lazy { error("backupApi should not be accessed") },
            absImportApi = lazy { error("absImportApi should not be accessed") },
            errorBus = lazy { error("errorBus should not be accessed") },
        )

    /**
     * Factory where [backupApi] and [absImportApi] are wired with stub implementations.
     * [ABSUploadWorker]'s constructor only accepts those two API deps plus the standard
     * WorkManager (context, params) — no other lazy is touched.
     */
    private fun buildFactoryWithABSApiDeps(): ListenUpWorkerFactory =
        ListenUpWorkerFactory(
            downloadRepository = lazy { error("downloadRepository should not be accessed") },
            fileManager = lazy { error("fileManager should not be accessed") },
            apiClientFactory = lazy { error("apiClientFactory should not be accessed") },
            playbackPreferences = lazy { error("playbackPreferences should not be accessed") },
            playbackApi = lazy { error("playbackApi should not be accessed") },
            capabilityDetector = lazy { error("capabilityDetector should not be accessed") },
            backupApi = lazy { StubBackupApi() },
            absImportApi = lazy { StubABSImportApi() },
            errorBus = lazy { ErrorBus() },
        )

    /**
     * Builds a [FakeWorker] via [TestListenableWorkerBuilder] and extracts its
     * [WorkerParameters] from the `mWorkerParams` field declared on [ListenableWorker].
     *
     * [Class.getDeclaredField] does not traverse superclasses, so we look up the field
     * on [ListenableWorker] (the declaring class) — not on the concrete [FakeWorker] class.
     */
    private fun fakeWorkerParams(): WorkerParameters {
        val worker = TestListenableWorkerBuilder.from(context, FakeWorker::class.java).build()
        return ListenableWorker::class.java
            .getDeclaredField("mWorkerParams")
            .also { it.isAccessible = true }
            .get(worker) as WorkerParameters
    }

    /** Stub worker used solely to obtain a valid [WorkerParameters] via [TestListenableWorkerBuilder]. */
    class FakeWorker(
        context: Context,
        params: WorkerParameters,
    ) : androidx.work.CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = Result.success()
    }
}

// ────────────────────────────── Stub API fakes ────────────────────────────────

/**
 * Stub [BackupApiContract] whose methods throw if called.
 * Passed to [ABSUploadWorker] which holds it by reference but doesn't call it
 * during construction.
 */
private class StubBackupApi : BackupApiContract {
    override suspend fun createBackup(
        includeImages: Boolean,
        includeEvents: Boolean,
    ): AppResult<BackupResponse> = notCalled()

    override suspend fun listBackups(): AppResult<List<BackupResponse>> = notCalled()

    override suspend fun getBackup(id: String): AppResult<BackupResponse> = notCalled()

    override suspend fun deleteBackup(id: String): AppResult<Unit> = notCalled()

    override suspend fun validateBackup(backupId: String): AppResult<ValidationResponse> = notCalled()

    override suspend fun restore(request: RestoreRequest): AppResult<RestoreResponse> = notCalled()

    override suspend fun rebuildProgress(): AppResult<RebuildProgressResponse> = notCalled()

    override suspend fun browseFilesystem(path: String): AppResult<com.calypsan.listenup.client.data.remote.BrowseFilesystemResponse> = notCalled()

    override suspend fun uploadABSBackup(fileSource: FileSource): AppResult<UploadABSBackupResponse> = notCalled()

    override suspend fun analyzeABSBackup(request: AnalyzeABSRequest): AppResult<AnalyzeABSResponse> = notCalled()

    override suspend fun analyzeABSBackupAsync(request: AnalyzeABSRequest): AppResult<AsyncAnalyzeResponse> = notCalled()

    override suspend fun getAnalysisStatus(analysisId: String): AppResult<AnalysisStatusResponse> = notCalled()

    override suspend fun importABSBackup(request: ImportABSRequest): AppResult<ImportABSResponse> = notCalled()
}

/**
 * Stub [ABSImportApiContract] whose methods throw if called.
 * Passed to [ABSUploadWorker] which holds it by reference but doesn't call it
 * during construction.
 */
private class StubABSImportApi : ABSImportApiContract {
    override suspend fun createImport(
        fileSource: FileSource,
        name: String,
    ): AppResult<ABSImportResponse> = notCalled()

    override suspend fun createImportFromPath(
        backupPath: String,
        name: String,
    ): AppResult<ABSImportResponse> = notCalled()

    override suspend fun listImports(): AppResult<List<ABSImportSummary>> = notCalled()

    override suspend fun getImport(importId: String): AppResult<ABSImportResponse> = notCalled()

    override suspend fun deleteImport(importId: String): AppResult<Unit> = notCalled()

    override suspend fun listImportUsers(
        importId: String,
        filter: MappingFilter,
    ): AppResult<List<ABSImportUser>> = notCalled()

    override suspend fun mapUser(
        importId: String,
        absUserId: String,
        listenUpId: String,
    ): AppResult<ABSImportUser> = notCalled()

    override suspend fun clearUserMapping(
        importId: String,
        absUserId: String,
    ): AppResult<ABSImportUser> = notCalled()

    override suspend fun searchUsers(
        query: String,
        limit: Int,
    ): AppResult<List<UserSearchResult>> = notCalled()

    override suspend fun listImportBooks(
        importId: String,
        filter: MappingFilter,
    ): AppResult<List<ABSImportBook>> = notCalled()

    override suspend fun mapBook(
        importId: String,
        absMediaId: String,
        listenUpId: String,
    ): AppResult<ABSImportBook> = notCalled()

    override suspend fun clearBookMapping(
        importId: String,
        absMediaId: String,
    ): AppResult<ABSImportBook> = notCalled()

    override suspend fun listSessions(
        importId: String,
        status: SessionStatusFilter,
    ): AppResult<ABSSessionsResponse> = notCalled()

    override suspend fun importReadySessions(importId: String): AppResult<ImportSessionsResult> = notCalled()

    override suspend fun skipSession(
        importId: String,
        sessionId: String,
        reason: String?,
    ): AppResult<Unit> = notCalled()
}

@Suppress("NOTHING_TO_INLINE")
private inline fun <T> notCalled(): T = error("This stub method should not be called during worker construction")
