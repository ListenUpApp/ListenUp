package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.profile.PasswordChange
import com.calypsan.listenup.api.dto.profile.Profile
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserEntity
import com.calypsan.listenup.client.data.remote.ProfileRpcFactory
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

                // profileRpcFactory is a bare mock: if updateProfile tried to push inline, the
                // unstubbed call would throw.
                val repo =
                    ProfileEditRepositoryImpl(
                        userDao = db.userDao(),
                        publicProfileDao = db.publicProfileDao(),
                        profileRpcFactory = mock<ProfileRpcFactory>(),
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

                val profileRpcFactory: ProfileRpcFactory = mock()
                val service = mock<ProfileService>()
                everySuspend { profileRpcFactory.get() } returns service
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
                        profileRpcFactory = profileRpcFactory,
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
    })
