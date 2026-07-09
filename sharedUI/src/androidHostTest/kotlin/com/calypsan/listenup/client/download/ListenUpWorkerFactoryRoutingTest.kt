package com.calypsan.listenup.client.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.kotest.matchers.shouldBe
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Routing coverage for [ListenUpWorkerFactory.createWorker].
 *
 * Verifies the `when (workerClassName)` dispatch: an unknown name produces `null` without
 * touching any dependency.
 *
 * [WorkerParameters] is an Android framework type that requires Robolectric to
 * instantiate properly; this test uses [RobolectricTestRunner] + JUnit4, consistent
 * with [PlaybackErrorHandlerTest] and [DeepLinkParserTest]. The `junit-vintage-engine`
 * on the classpath keeps these discoverable alongside Kotest specs.
 *
 * Coverage gap — [DownloadWorker] branch:
 * `createWorker` for DownloadWorker resolves the `AudioFileDownloader` lazy and constructs a
 * [DownloadWorker]. The `AudioFileDownloader` domain seam owns the HTTP transport inside
 * `:sharedLogic`, so exercising the download branch would require a fake downloader; this
 * routing test only covers the unknown-class `null` branch and leaves the download branch for
 * a follow-up.
 *
 * WorkerParameters extraction:
 * [WorkerParameters] is a framework-internal type with no public constructor. To obtain a valid
 * instance we build a [FakeWorker] via [TestListenableWorkerBuilder], then read the
 * `mWorkerParams` field declared on [ListenableWorker] (the abstract superclass) via reflection.
 * We look up the field on [ListenableWorker] directly — not on [FakeWorker] — because
 * [Class.getDeclaredField] does not traverse the class hierarchy.
 */
@RunWith(RobolectricTestRunner::class)
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
            audioFileDownloader = lazy { error("audioFileDownloader should not be accessed") },
            errorBus = lazy { error("errorBus should not be accessed") },
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
