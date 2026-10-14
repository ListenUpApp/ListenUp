package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.api.dto.image.ImageFraming
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.image.photoVersionOf
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.images.CommonImageStorage
import com.calypsan.listenup.client.data.local.images.StoragePaths
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path as IoPath

private class ContributorFrameRig {
    val db: ListenUpDatabase = createInMemoryTestDatabase()
    val filesDir: java.nio.file.Path = Files.createTempDirectory("contributor-frame")
    val storage =
        CommonImageStorage(
            object : StoragePaths {
                override val filesDir: IoPath = IoPath(this@ContributorFrameRig.filesDir.toString())
            },
        )
    val repo =
        ContributorEditRepositoryImpl(
            channel = RpcChannel.forTest(mock<ContributorService>()),
            contributorDao = db.contributorDao(),
            offlineEditor =
                OfflineEditor(
                    pendingQueue = PendingOperationQueue(db.pendingOperationV2Dao(), PendingOperationSender { AppResult.Success(Unit) }),
                    transactionRunner =
                        object : TransactionRunner {
                            override suspend fun <R> atomically(block: suspend () -> R): R = block()
                        },
                    authSession = FakeAuthSession(userId = "u1"),
                ),
            pendingImageUploadDao = db.pendingImageUploadDao(),
            imageStorage = storage,
        )

    suspend fun contributor(imagePath: String?) =
        db.contributorDao().upsert(
            ContributorEntity(
                id = ContributorId("c1"),
                name = "Ray Porter",
                description = null,
                imagePath = imagePath,
                createdAt = Timestamp(0L),
                updatedAt = Timestamp(0L),
            ),
        )

    suspend fun queuedPayloads() = db.pendingOperationV2Dao().nextDispatchable(maxAttempts = 5).map { it.payload }

    fun close() {
        db.close()
        filesDir.toFile().deleteRecursively()
    }
}

class ContributorImageFrameOfflineTest :
    FunSpec({
        val frame = ImageFrame(0.5, 0.2, 1.5)

        test("framing a photo writes Room first, then queues the patch with the photo's version") {
            runTest {
                val rig = ContributorFrameRig()
                try {
                    rig.contributor("contributors/abc.jpg")

                    rig.repo.setImageFrame(ContributorId("c1"), frame) shouldBe AppResult.Success(Unit)

                    rig.db
                        .contributorDao()
                        .getById("c1")!!
                        .imageFrame shouldBe ImageFraming("abc", frame)
                    rig.queuedPayloads().single() shouldContain """"imageVersion":"abc""""
                } finally {
                    rig.close()
                }
            }
        }

        test("a contributor with no photo cannot be framed, and nothing is queued") {
            runTest {
                val rig = ContributorFrameRig()
                try {
                    rig.contributor(imagePath = null)

                    val result = rig.repo.setImageFrame(ContributorId("c1"), frame)

                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ValidationError>()
                        .field shouldBe "imageFrame"
                    rig.queuedPayloads().shouldBeEmpty()
                } finally {
                    rig.close()
                }
            }
        }

        test("while the user's own new photo waits to upload, framing it uses that file's hash") {
            runTest {
                val rig = ContributorFrameRig()
                try {
                    rig.contributor("contributors/abc.jpg")
                    val newPhoto = byteArrayOf(9, 8, 7)
                    rig.storage.saveContributorImage("c1", newPhoto)
                    rig.db.pendingImageUploadDao().record("ContributorImage", "c1", "u1", 1L)

                    rig.repo.setImageFrame(ContributorId("c1"), frame)

                    rig.db
                        .contributorDao()
                        .getById("c1")!!
                        .imageFrame shouldBe ImageFraming(photoVersionOf(newPhoto), frame)
                } finally {
                    rig.close()
                }
            }
        }

        test("a new photo's frame is recorded for its bytes; no frame records none") {
            runTest {
                val rig = ContributorFrameRig()
                try {
                    rig.contributor("contributors/abc.jpg")
                    val newPhoto = byteArrayOf(1, 2, 3)

                    rig.repo.recordNewPhotoFrame(ContributorId("c1"), newPhoto, frame)
                    rig.db
                        .contributorDao()
                        .getById("c1")!!
                        .imageFrame shouldBe ImageFraming(photoVersionOf(newPhoto), frame)
                    rig.queuedPayloads().shouldBeEmpty() // the frame travels with the upload, not the outbox

                    rig.repo.recordNewPhotoFrame(ContributorId("c1"), newPhoto, frame = null)
                    rig.db
                        .contributorDao()
                        .getById("c1")!!
                        .imageFrame
                        .shouldBeNull()
                } finally {
                    rig.close()
                }
            }
        }

        test("a stamped frame's version wins: the next adjustment frames the photo this device shows") {
            runTest {
                val rig = ContributorFrameRig()
                try {
                    rig.contributor("contributors/abc.jpg")
                    rig.repo.recordNewPhotoFrame(ContributorId("c1"), byteArrayOf(4, 5), ImageFrame.Centered)

                    rig.repo.setImageFrame(ContributorId("c1"), frame)

                    rig.db
                        .contributorDao()
                        .getById("c1")
                        .shouldNotBeNull()
                        .imageFrame
                        ?.imageVersion shouldBe
                        photoVersionOf(byteArrayOf(4, 5))
                } finally {
                    rig.close()
                }
            }
        }
    })
