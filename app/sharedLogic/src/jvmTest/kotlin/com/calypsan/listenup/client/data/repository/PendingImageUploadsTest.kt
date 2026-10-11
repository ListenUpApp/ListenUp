package com.calypsan.listenup.client.data.repository

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.images.CommonImageStorage
import com.calypsan.listenup.client.data.local.images.StoragePaths
import com.calypsan.listenup.client.data.remote.ImageApiContract
import com.calypsan.listenup.client.data.remote.ImageUploadResponse
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.PendingImageKind
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.dto.auth.SessionId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.io.files.Path as IoPath

/** One upload the fake image API received. */
private data class SentImage(
    val kind: PendingImageKind,
    val entityId: String,
    val bytes: List<Byte>,
)

/**
 * The image API as the server would answer it: whatever [answer] says, and a record of what arrived.
 * [gate], when set, holds every upload open until it completes, so a test can act mid-upload.
 */
private class FakeImageApi : ImageApiContract {
    val sent = mutableListOf<SentImage>()
    var answer: AppResult<ImageUploadResponse> = AppResult.Success(ImageUploadResponse("https://server/image"))
    var gate: CompletableDeferred<Unit>? = null
    val started = CompletableDeferred<Unit>()

    private suspend fun upload(
        kind: PendingImageKind,
        entityId: String,
        bytes: ByteArray,
    ): AppResult<ImageUploadResponse> {
        started.complete(Unit)
        gate?.await()
        sent += SentImage(kind, entityId, bytes.toList())
        return answer
    }

    override suspend fun uploadBookCover(
        bookId: String,
        imageData: ByteArray,
        filename: String,
    ) = upload(PendingImageKind.BookCover, bookId, imageData)

    override suspend fun uploadSeriesCover(
        seriesId: String,
        imageData: ByteArray,
        filename: String,
    ) = upload(PendingImageKind.SeriesCover, seriesId, imageData)

    override suspend fun uploadContributorImage(
        contributorId: String,
        imageData: ByteArray,
        filename: String,
    ) = upload(PendingImageKind.ContributorImage, contributorId, imageData)

    override suspend fun downloadCover(bookId: BookId): AppResult<ByteArray> = error("not used")

    override suspend fun downloadContributorImage(contributorId: String): AppResult<ByteArray> = error("not used")

    override suspend fun downloadSeriesCover(seriesId: String): AppResult<ByteArray> = error("not used")

    override suspend fun downloadUserAvatar(userId: String): AppResult<ByteArray> = error("not used")
}

private fun imageStorageIn(directory: Path) =
    CommonImageStorage(
        object : StoragePaths {
            override val filesDir: IoPath = IoPath(directory.toString())
        },
    )

/**
 * [PendingImageUploadsImpl] against a real in-memory Room and real image files: an upload that failed
 * is remembered, retried with the file as it is then, and forgotten once the server has answered.
 */
class PendingImageUploadsTest :
    FunSpec({
        class Fixture(
            val database: ListenUpDatabase = createInMemoryTestDatabase(),
            val filesDir: Path = Files.createTempDirectory("pending-image-uploads"),
        ) {
            val api = FakeImageApi()
            val auth = FakeAuthSession(userId = "u1")
            val storage = imageStorageIn(filesDir)
            var clock = 1_000L
            val uploads =
                PendingImageUploadsImpl(
                    dao = database.pendingImageUploadDao(),
                    imageStorage = storage,
                    imageApi = api,
                    authSession = auth,
                    now = { clock },
                )

            fun close() {
                database.close()
                filesDir.toFile().deleteRecursively()
            }
        }

        fun bytesOf(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

        test("a recorded image is pending, and recording it twice leaves one row") {
            val f = Fixture()
            try {
                f.uploads.record(PendingImageKind.BookCover, "b1") shouldBe true
                f.uploads.record(PendingImageKind.BookCover, "b1") shouldBe true

                f.uploads.isPending(PendingImageKind.BookCover, "b1") shouldBe true
                f.database.pendingImageUploadDao().pendingFor("u1") shouldHaveSize 1
            } finally {
                f.close()
            }
        }

        test("on a platform that keeps no image file nothing is queued") {
            val f = Fixture()
            try {
                val noFiles =
                    object : ImageStorage by f.storage {
                        override val keepsImageFiles: Boolean = false
                    }
                val uploads =
                    PendingImageUploadsImpl(
                        dao = f.database.pendingImageUploadDao(),
                        imageStorage = noFiles,
                        imageApi = f.api,
                        authSession = f.auth,
                    )

                uploads.record(PendingImageKind.BookCover, "b1") shouldBe false
                f.database
                    .pendingImageUploadDao()
                    .pendingFor("u1")
                    .shouldBeEmpty()
            } finally {
                f.close()
            }
        }

        test("a book and a series sharing an id are two pending images") {
            val f = Fixture()
            try {
                f.uploads.record(PendingImageKind.BookCover, "same-id")
                f.uploads.record(PendingImageKind.SeriesCover, "same-id")

                f.database.pendingImageUploadDao().pendingFor("u1") shouldHaveSize 2
                f.uploads.forget(PendingImageKind.BookCover, "same-id")
                f.uploads.isPending(PendingImageKind.SeriesCover, "same-id") shouldBe true
                f.uploads.isPending(PendingImageKind.BookCover, "same-id") shouldBe false
            } finally {
                f.close()
            }
        }

        test("a drain that still cannot reach the server leaves the image pending") {
            runTest {
                val f = Fixture()
                try {
                    f.storage.saveCover(BookId("b1"), bytesOf(1, 2, 3))
                    f.uploads.record(PendingImageKind.BookCover, "b1")
                    f.api.answer = AppResult.Failure(TransportError.NetworkUnavailable())

                    val outcome = f.uploads.drain()

                    outcome.uploaded shouldBe 0
                    outcome.stillPending shouldBe 1
                    outcome.refused.shouldBeEmpty()
                    f.uploads.isPending(PendingImageKind.BookCover, "b1") shouldBe true
                    f.database
                        .pendingImageUploadDao()
                        .get("BookCover", "b1")
                        ?.attempts shouldBe 1
                } finally {
                    f.close()
                }
            }
        }

        test("a drain once the server answers uploads the local file and forgets the image") {
            runTest {
                val f = Fixture()
                try {
                    f.storage.saveCover(BookId("b1"), bytesOf(1, 2, 3))
                    f.uploads.record(PendingImageKind.BookCover, "b1")

                    val outcome = f.uploads.drain()

                    outcome.uploaded shouldBe 1
                    outcome.stillPending shouldBe 0
                    f.api.sent shouldBe listOf(SentImage(PendingImageKind.BookCover, "b1", listOf<Byte>(1, 2, 3)))
                    f.uploads.isPending(PendingImageKind.BookCover, "b1") shouldBe false
                } finally {
                    f.close()
                }
            }
        }

        test("a series cover and a contributor photo are uploaded from their own files") {
            runTest {
                val f = Fixture()
                try {
                    f.storage.saveSeriesCover("s1", bytesOf(4))
                    f.storage.saveContributorImage("c1", bytesOf(5))
                    f.uploads.record(PendingImageKind.SeriesCover, "s1")
                    f.uploads.record(PendingImageKind.ContributorImage, "c1")

                    f.uploads.drain().uploaded shouldBe 2

                    f.api.sent.toSet() shouldBe
                        setOf(
                            SentImage(PendingImageKind.SeriesCover, "s1", listOf<Byte>(4)),
                            SentImage(PendingImageKind.ContributorImage, "c1", listOf<Byte>(5)),
                        )
                } finally {
                    f.close()
                }
            }
        }

        test("a refusal removes the image and is reported once") {
            runTest {
                val f = Fixture()
                try {
                    f.storage.saveCover(BookId("b1"), bytesOf(1))
                    f.uploads.record(PendingImageKind.BookCover, "b1")
                    f.api.answer = AppResult.Failure(AuthError.PermissionDenied())

                    val first = f.uploads.drain()
                    val second = f.uploads.drain()

                    first.refused shouldHaveSize 1
                    first.refused.single().kind shouldBe PendingImageKind.BookCover
                    first.refused.single().entityId shouldBe "b1"
                    first.refused
                        .single()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    f.uploads.isPending(PendingImageKind.BookCover, "b1") shouldBe false
                    second.refused.shouldBeEmpty()
                    f.api.sent shouldHaveSize 1
                } finally {
                    f.close()
                }
            }
        }

        test("a lapsed session and an unknown outcome are kept for the next drain, not refused") {
            runTest {
                val f = Fixture()
                try {
                    f.storage.saveCover(BookId("b1"), bytesOf(1))
                    f.uploads.record(PendingImageKind.BookCover, "b1")

                    f.api.answer = AppResult.Failure(AuthError.SessionExpired())
                    f.uploads
                        .drain()
                        .refused
                        .shouldBeEmpty()
                    f.api.answer = AppResult.Failure(TransportError.OutcomeUnknown())
                    f.uploads
                        .drain()
                        .refused
                        .shouldBeEmpty()

                    f.uploads.isPending(PendingImageKind.BookCover, "b1") shouldBe true
                } finally {
                    f.close()
                }
            }
        }

        test("two saves while pending upload the latest file once") {
            runTest {
                val f = Fixture()
                try {
                    f.storage.saveCover(BookId("b1"), bytesOf(1))
                    f.uploads.record(PendingImageKind.BookCover, "b1")
                    f.storage.saveCover(BookId("b1"), bytesOf(9, 9))
                    f.uploads.record(PendingImageKind.BookCover, "b1")

                    f.uploads.drain().uploaded shouldBe 1

                    f.api.sent shouldBe listOf(SentImage(PendingImageKind.BookCover, "b1", listOf<Byte>(9, 9)))
                } finally {
                    f.close()
                }
            }
        }

        test("a save made while an upload is in flight is not lost when that upload finishes") {
            runTest {
                val f = Fixture()
                try {
                    f.storage.saveCover(BookId("b1"), bytesOf(1))
                    f.uploads.record(PendingImageKind.BookCover, "b1")
                    f.api.gate = CompletableDeferred()

                    val draining = launch { f.uploads.drain() }
                    f.api.started.await()
                    f.storage.saveCover(BookId("b1"), bytesOf(2))
                    f.uploads.record(PendingImageKind.BookCover, "b1")
                    f.api.gate?.complete(Unit)
                    draining.join()

                    f.uploads.isPending(PendingImageKind.BookCover, "b1") shouldBe true
                    f.api.gate = null
                    f.uploads.drain().uploaded shouldBe 1
                    f.api.sent
                        .last()
                        .bytes shouldBe listOf<Byte>(2)
                } finally {
                    f.close()
                }
            }
        }

        test("an image whose local file is gone is dropped without an upload or an error") {
            runTest {
                val f = Fixture()
                try {
                    f.uploads.record(PendingImageKind.BookCover, "b1")

                    val first = f.uploads.drain()
                    val second = f.uploads.drain()

                    first.refused.shouldBeEmpty()
                    first.uploaded shouldBe 0
                    second.stillPending shouldBe 0
                    f.api.sent.shouldBeEmpty()
                    f.uploads.isPending(PendingImageKind.BookCover, "b1") shouldBe false
                } finally {
                    f.close()
                }
            }
        }

        test("another user's pending images are dropped, never uploaded") {
            runTest {
                val f = Fixture()
                try {
                    f.storage.saveCover(BookId("b1"), bytesOf(1))
                    f.uploads.record(PendingImageKind.BookCover, "b1")
                    f.auth.authState.value = AuthState.Authenticated(UserId("u2"), SessionId("s2"))

                    val outcome = f.uploads.drain()

                    outcome.uploaded shouldBe 0
                    f.api.sent.shouldBeEmpty()
                    f.uploads.isPending(PendingImageKind.BookCover, "b1") shouldBe false
                } finally {
                    f.close()
                }
            }
        }

        test("pending images survive closing and reopening the database") {
            runTest {
                val dir = Files.createTempDirectory("pending-image-db")
                val dbPath = dir.resolve("listenup.db").toString()

                fun open() =
                    Room
                        .databaseBuilder<ListenUpDatabase>(name = dbPath)
                        .setDriver(BundledSQLiteDriver())
                        .build()
                try {
                    val first = open()
                    PendingImageUploadsImpl(
                        dao = first.pendingImageUploadDao(),
                        imageStorage = imageStorageIn(dir),
                        imageApi = FakeImageApi(),
                        authSession = FakeAuthSession(userId = "u1"),
                    ).record(PendingImageKind.ContributorImage, "c1")
                    first.close()

                    val reopened = open()
                    try {
                        reopened.pendingImageUploadDao().pendingFor("u1").map { it.kind to it.entityId } shouldBe
                            listOf("ContributorImage" to "c1")
                    } finally {
                        reopened.close()
                    }
                } finally {
                    dir.toFile().deleteRecursively()
                }
            }
        }
    })
