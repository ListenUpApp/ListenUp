package com.calypsan.listenup.client.data.repository

import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.collections.shouldBeEmpty
import com.calypsan.listenup.client.data.local.db.PublicProfileEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.dto.image.ImageFraming
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.profile.PasswordChange
import com.calypsan.listenup.api.dto.profile.Profile
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class ProfileEditOfflineTest :
    FunSpec({
        suspend fun seedUser(userDao: UserDao) {
            userDao.upsert(
                UserEntity(
                    id = UserId("u1"),
                    email = "a@b.c",
                    displayName = "Old Name",
                    firstName = "Old",
                    lastName = "Name",
                    isRoot = false,
                    createdAt = Timestamp(0L),
                    updatedAt = Timestamp(0L),
                ),
            )
        }

        test("a name/tagline change enqueues a profile op and writes Room, no inline RPC") {
            runTest {
                val db = createInMemoryTestDatabase()
                seedUser(db.userDao())
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val txRunner =
                    object : TransactionRunner {
                        override suspend fun <R> atomically(block: suspend () -> R): R = block()
                    }
                val offlineEditor =
                    OfflineEditor(
                        pendingQueue = queue,
                        transactionRunner = txRunner,
                        authSession = FakeAuthSession(userId = "u1"),
                    )

                // The channel wraps a bare mock service: if updateProfile tried to push inline, the
                // unstubbed call would fold to a Failure and the Success assertion below would fail.
                val repo =
                    ProfileEditRepositoryImpl(
                        userDao = db.userDao(),
                        publicProfileDao = db.publicProfileDao(),
                        channel = RpcChannel.forTest(mock<ProfileService>()),
                        avatarUploader = mock(),
                        imageStorage = mock<ImageStorage>(),
                        offlineEditor = offlineEditor,
                    )

                val result = repo.updateProfile(firstName = "New", lastName = null, tagline = "Hi", password = null)

                result shouldBe AppResult.Success(Unit)
                db.userDao().getCurrentUser()?.firstName shouldBe "New"
                db.userDao().getCurrentUser()?.tagline shouldBe "Hi"
                db
                    .pendingOperationV2Dao()
                    .nextDispatchable(maxAttempts = 5)
                    .firstOrNull()
                    ?.domainName shouldBe "profile"
                db.close()
            }
        }

        test("a password change is not queued (stays online)") {
            runTest {
                val db = createInMemoryTestDatabase()
                seedUser(db.userDao())
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val txRunner =
                    object : TransactionRunner {
                        override suspend fun <R> atomically(block: suspend () -> R): R = block()
                    }
                val offlineEditor =
                    OfflineEditor(
                        pendingQueue = queue,
                        transactionRunner = txRunner,
                        authSession = FakeAuthSession(userId = "u1"),
                    )

                val service = mock<ProfileService>()
                everySuspend { service.updateMyProfile(any()) } returns
                    AppResult.Success(
                        Profile(
                            userId = UserId("u1"),
                            displayName = "Old Name",
                            tagline = null,
                            avatarType = "auto",
                            updatedAt = 1_000L,
                        ),
                    )

                val repo =
                    ProfileEditRepositoryImpl(
                        userDao = db.userDao(),
                        publicProfileDao = db.publicProfileDao(),
                        channel = RpcChannel.forTest(service),
                        avatarUploader = mock(),
                        imageStorage = mock<ImageStorage>(),
                        offlineEditor = offlineEditor,
                    )

                val result =
                    repo.updateProfile(
                        firstName = null,
                        lastName = null,
                        tagline = null,
                        password = PasswordChange(currentPassword = "old12345", newPassword = "newpass123"),
                    )

                result shouldBe AppResult.Success(Unit)
                db.pendingOperationV2Dao().nextDispatchable(maxAttempts = 5).firstOrNull() shouldBe null
                db.close()
            }
        }

        test("framing the avatar writes the own row first and queues a profile patch bound to the stored avatar") {
            runTest {
                val db = createInMemoryTestDatabase()
                seedUser(db.userDao())
                db.publicProfileDao().upsert(selfProfile(avatarType = "image", avatarUpdatedAt = 7))
                val repo = offlineRepo(db)
                val frame = ImageFrame(0.5, 0.3, 2.0)

                repo.setAvatarFrame(frame) shouldBe AppResult.Success(Unit)

                db.publicProfileDao().findById("u1")!!.avatarFrame shouldBe ImageFraming("7", frame)
                val op = db.pendingOperationV2Dao().nextDispatchable(maxAttempts = 5).single()
                op.domainName shouldBe "profile"
                op.payload shouldContain "\"imageVersion\":\"7\""
                db.close()
            }
        }

        test("an auto avatar cannot be framed, and nothing is queued") {
            runTest {
                val db = createInMemoryTestDatabase()
                seedUser(db.userDao())
                db.publicProfileDao().upsert(selfProfile(avatarType = "auto", avatarUpdatedAt = 7))

                val result = offlineRepo(db).setAvatarFrame(ImageFrame.Centered)

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<ValidationError>()
                    .field shouldBe "imageFrame"
                db.pendingOperationV2Dao().nextDispatchable(maxAttempts = 5).shouldBeEmpty()
                db.close()
            }
        }
    })

/** The caller's own synced `public_profiles` row, with zeroed stats. */
private fun selfProfile(
    avatarType: String,
    avatarUpdatedAt: Long,
) = PublicProfileEntity(
    id = "u1",
    displayName = "Old Name",
    avatarType = avatarType,
    totalSecondsAllTime = 0,
    totalSecondsLast7Days = 0,
    totalSecondsLast30Days = 0,
    totalSecondsLast365Days = 0,
    booksFinished = 0,
    currentStreakDays = 0,
    longestStreakDays = 0,
    avatarUpdatedAt = avatarUpdatedAt,
    revision = 1,
)

/** A [ProfileEditRepositoryImpl] over [db] with a real outbox; the network is never reached. */
private fun offlineRepo(db: ListenUpDatabase): ProfileEditRepositoryImpl =
    ProfileEditRepositoryImpl(
        userDao = db.userDao(),
        publicProfileDao = db.publicProfileDao(),
        channel = RpcChannel.forTest(mock<ProfileService>()),
        avatarUploader = mock(),
        imageStorage = mock<ImageStorage>(),
        offlineEditor =
            OfflineEditor(
                pendingQueue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    ),
                transactionRunner =
                    object : TransactionRunner {
                        override suspend fun <R> atomically(block: suspend () -> R): R = block()
                    },
                authSession = FakeAuthSession(userId = "u1"),
            ),
    )
