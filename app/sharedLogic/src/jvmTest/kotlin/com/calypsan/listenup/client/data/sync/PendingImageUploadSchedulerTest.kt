package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.image.ImageFrame
import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.images.CommonImageStorage
import com.calypsan.listenup.client.data.local.images.StoragePaths
import com.calypsan.listenup.client.data.remote.ImageApiContract
import com.calypsan.listenup.client.data.remote.ImageUploadResponse
import com.calypsan.listenup.client.data.repository.PendingImageUploadsImpl
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.PendingImageKind
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path as IoPath

/** A network the test switches by hand. */
private class SwitchableNetwork(
    online: Boolean,
) : NetworkMonitor {
    override val onlineFlow: MutableStateFlow<Boolean> = MutableStateFlow(online)
    override val onUnmeteredNetworkFlow: StateFlow<Boolean> = MutableStateFlow(true)

    override fun isOnline(): Boolean = onlineFlow.value
}

/** The image API as a server: the first upload completes [firstUpload]; [answer] decides the verdict. */
private class RecordingImageApi : ImageApiContract {
    val firstUpload = CompletableDeferred<String>()
    var uploads = 0
    var answer: AppResult<ImageUploadResponse> = AppResult.Success(ImageUploadResponse("https://server/image"))

    override suspend fun uploadBookCover(
        bookId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<ImageUploadResponse> {
        uploads++
        firstUpload.complete(bookId)
        return answer
    }

    override suspend fun uploadSeriesCover(
        seriesId: String,
        imageData: ByteArray,
        filename: String,
    ) = error("not used")

    override suspend fun uploadContributorImage(
        contributorId: String,
        imageData: ByteArray,
        filename: String,
        frame: ImageFrame?,
    ) = error("not used")

    override suspend fun downloadCover(bookId: BookId): AppResult<ByteArray> = error("not used")

    override suspend fun downloadContributorImage(contributorId: String): AppResult<ByteArray> = error("not used")

    override suspend fun downloadSeriesCover(seriesId: String): AppResult<ByteArray> = error("not used")

    override suspend fun downloadUserAvatar(userId: String): AppResult<ByteArray> = error("not used")
}

/**
 * [PendingImageUploadScheduler] decides WHEN a pending upload is tried: at app start when signed in and
 * online, when the network returns, and when the server connection comes up — never while offline or
 * signed out.
 */
class PendingImageUploadSchedulerTest :
    FunSpec({
        class Fixture(
            online: Boolean,
            scope: CoroutineScope,
        ) {
            val database = createInMemoryTestDatabase()
            val dir = Files.createTempDirectory("pending-image-scheduler")
            val api = RecordingImageApi()
            val auth = FakeAuthSession(userId = "u1")
            val network = SwitchableNetwork(online)
            val engineState = SyncEngineState()
            val errorBus = ErrorBus()
            val storage =
                CommonImageStorage(
                    object : StoragePaths {
                        override val filesDir: IoPath = IoPath(dir.toString())
                    },
                )
            val uploads =
                PendingImageUploadsImpl(
                    dao = database.pendingImageUploadDao(),
                    imageStorage = storage,
                    imageApi = api,
                    authSession = auth,
                    contributorDao = database.contributorDao(),
                )
            val scheduler =
                PendingImageUploadScheduler(
                    uploads = uploads,
                    authSession = auth,
                    networkMonitor = network,
                    syncEngineState = engineState,
                    errorBus = errorBus,
                    scope = scope,
                )

            suspend fun savedAndPending() {
                storage.saveCover(BookId("b1"), byteArrayOf(1, 2, 3))
                uploads.record(PendingImageKind.BookCover, "b1")
            }

            fun close() {
                database.close()
                dir.toFile().deleteRecursively()
            }
        }

        test("signed in and online at app start, a pending image is uploaded") {
            runTest {
                val f = Fixture(online = true, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
                try {
                    f.savedAndPending()

                    val job = f.scheduler.start()

                    f.api.firstUpload.await() shouldBe "b1"
                    job.cancel()
                } finally {
                    f.close()
                }
            }
        }

        test("offline at app start waits, and uploads when the network returns") {
            runTest {
                val f = Fixture(online = false, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
                try {
                    f.savedAndPending()

                    val job = f.scheduler.start()
                    f.api.uploads shouldBe 0
                    f.network.onlineFlow.value = true

                    f.api.firstUpload.await() shouldBe "b1"
                    job.cancel()
                } finally {
                    f.close()
                }
            }
        }

        test("signed out at app start waits, and uploads once signed in") {
            runTest {
                val f = Fixture(online = true, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
                try {
                    f.savedAndPending()
                    f.auth.authState.value = AuthState.NeedsLogin()

                    val job = f.scheduler.start()
                    f.api.uploads shouldBe 0
                    f.auth.authState.value = AuthState.Authenticated(UserId("u1"), SessionId("s1"))

                    f.api.firstUpload.await() shouldBe "b1"
                    job.cancel()
                } finally {
                    f.close()
                }
            }
        }

        test("the server connection coming up triggers a pass") {
            runTest {
                val f = Fixture(online = false, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
                try {
                    f.savedAndPending()

                    val job = f.scheduler.start()
                    f.api.uploads shouldBe 0
                    f.engineState.setConnection(ConnectionState.Connected(lastEventId = 1))

                    f.api.firstUpload.await() shouldBe "b1"
                    job.cancel()
                } finally {
                    f.close()
                }
            }
        }

        test("an image the server refuses is reported once on the error bus") {
            runTest {
                val f = Fixture(online = true, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
                try {
                    f.savedAndPending()
                    f.api.answer = AppResult.Failure(AuthError.PermissionDenied())

                    f.errorBus.errors.test {
                        val job = f.scheduler.start()
                        awaitItem().shouldBeInstanceOf<AuthError.PermissionDenied>()
                        job.cancel()
                    }
                } finally {
                    f.close()
                }
            }
        }
    })
