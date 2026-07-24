package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.profile.PasswordChange
import com.calypsan.listenup.api.dto.profile.Profile
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import com.calypsan.listenup.api.error.ProfileError
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Dao
import com.calypsan.listenup.client.data.local.db.PublicProfileDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserEntity
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [ProfileEditRepositoryImpl].
 *
 * Covers [ProfileEditRepositoryImpl.updateProfile] — offline-first for name/tagline (writes
 * Room + enqueues via [OfflineEditor], no inline RPC), fully online for a password-bearing
 * change (the consolidated [ProfileService.updateMyProfile] RPC call) — plus the avatar REST
 * transport helpers.
 */
class ProfileEditRepositoryImplTest :
    FunSpec({

        val userId = "user-1"
        val userEntity =
            UserEntity(
                id = UserId(userId),
                email = "test@example.com",
                displayName = "Test User",
                firstName = "Test",
                lastName = "User",
                isRoot = false,
                createdAt = Timestamp(0L),
                updatedAt = Timestamp(0L),
                tagline = null,
            )

        val stubProfile =
            Profile(
                userId = UserId(userId),
                displayName = "Test User",
                tagline = null,
                avatarType = "auto",
                updatedAt = 1_000L,
            )

        /** No-op [AvatarUploader] — avatar upload is not exercised in these unit tests. */
        val noOpUploader = AvatarUploader { _, _ -> AppResult.Success(0L) }

        /**
         * Builds a real [OfflineEditor] over a mocked [PendingOperationV2Dao] — no Room needed,
         * since [PendingOperationQueue] only calls the DAO interface directly. The queue's
         * [PendingOperationSender] is never exercised here (nothing drains synchronously in
         * these unit tests); the transaction runner runs [OfflineEditor]'s local write inline.
         */
        fun buildOfflineEditor(
            pendingDao: PendingOperationV2Dao =
                mock<PendingOperationV2Dao> {
                    everySuspend { insert(any()) } returns Unit
                    everySuspend { maxEnqueuedAtFor(any(), any()) } returns null
                },
            authSession: AuthSession = FakeAuthSession(userId = userId),
        ): OfflineEditor {
            val queue =
                PendingOperationQueue(
                    dao = pendingDao,
                    sender = PendingOperationSender { AppResult.Success(Unit) },
                )
            val txRunner =
                object : TransactionRunner {
                    override suspend fun <R> atomically(block: suspend () -> R): R = block()
                }
            return OfflineEditor(pendingQueue = queue, transactionRunner = txRunner, authSession = authSession)
        }

        fun repo(
            userDao: UserDao = mock(),
            publicProfileDao: PublicProfileDao = mock(),
            service: ProfileService = mock(),
            avatarUploader: AvatarUploader = noOpUploader,
            imageStorage: ImageStorage = mock<ImageStorage>(),
            offlineEditor: OfflineEditor = buildOfflineEditor(),
        ): ProfileEditRepositoryImpl {
            everySuspend { imageStorage.saveUserAvatar(any(), any()) } returns AppResult.Success(Unit)
            return ProfileEditRepositoryImpl(
                userDao = userDao,
                publicProfileDao = publicProfileDao,
                channel = RpcChannel.forTest(service),
                avatarUploader = avatarUploader,
                imageStorage = imageStorage,
                offlineEditor = offlineEditor,
            )
        }

        // ── updateProfile — tagline only (offline-first: no password) ────────

        test("updateProfile with tagline only and no password is offline-first: writes Room, enqueues, no RPC") {
            runTest {
                // service is a bare mock with no stub for updateMyProfile — if the offline path
                // regressed into an inline RPC call, the unstubbed invocation would throw.
                val service = mock<ProfileService>()
                val userDao = mock<UserDao>()
                val pendingDao = mock<PendingOperationV2Dao>()
                everySuspend { pendingDao.insert(any()) } returns Unit
                everySuspend { pendingDao.maxEnqueuedAtFor(any(), any()) } returns null
                everySuspend { userDao.getCurrentUser() } returns userEntity
                everySuspend { userDao.updateTagline(any(), any(), any()) } returns Unit

                val result =
                    repo(
                        userDao = userDao,
                        service = service,
                        offlineEditor = buildOfflineEditor(pendingDao = pendingDao),
                    ).updateProfile(
                        firstName = null,
                        lastName = null,
                        tagline = "Hi",
                        password = null,
                    )

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend { userDao.updateTagline(userId, "Hi", any()) }
                verifySuspend { pendingDao.insert(any()) }
            }
        }

        test("updateProfile with no signed-in user returns Failure without writing Room") {
            runTest {
                val userDao = mock<UserDao>()
                everySuspend { userDao.getCurrentUser() } returns userEntity

                val result =
                    repo(
                        userDao = userDao,
                        offlineEditor = buildOfflineEditor(authSession = FakeAuthSession(userId = null)),
                    ).updateProfile(
                        firstName = null,
                        lastName = null,
                        tagline = "Hi",
                        password = null,
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
            }
        }

        // ── updateProfile — name only (offline-first: no password) ───────────

        test("updateProfile with name only and no password is offline-first: writes Room and enqueues") {
            runTest {
                val userDao = mock<UserDao>()
                val pendingDao = mock<PendingOperationV2Dao>()
                everySuspend { pendingDao.insert(any()) } returns Unit
                everySuspend { pendingDao.maxEnqueuedAtFor(any(), any()) } returns null
                everySuspend { userDao.getCurrentUser() } returns userEntity
                everySuspend { userDao.updateName(any(), any(), any(), any(), any()) } returns Unit

                val result =
                    repo(
                        userDao = userDao,
                        offlineEditor = buildOfflineEditor(pendingDao = pendingDao),
                    ).updateProfile(
                        firstName = "Bob",
                        lastName = "Jones",
                        tagline = null,
                        password = null,
                    )

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend {
                    userDao.updateName(userId, "Bob", "Jones", "Bob Jones", any())
                }
                verifySuspend { pendingDao.insert(any()) }
            }
        }

        // ── updateProfile — password ──────────────────────────────────────────

        test("updateProfile with password sends PasswordChange and returns WrongPassword failure") {
            runTest {
                val service = mock<ProfileService>()
                val userDao = mock<UserDao>()
                everySuspend { userDao.getCurrentUser() } returns userEntity
                val expectedRequest =
                    UpdateProfileRequest(
                        password = PasswordChange(currentPassword = "cur12345", newPassword = "newpass12"),
                    )
                everySuspend {
                    service.updateMyProfile(expectedRequest)
                } returns WireAppResult.Failure(ProfileError.WrongPassword())

                val result =
                    repo(userDao = userDao, service = service).updateProfile(
                        firstName = null,
                        lastName = null,
                        tagline = null,
                        password = PasswordChange(currentPassword = "cur12345", newPassword = "newpass12"),
                    )

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<ProfileError.WrongPassword>()
            }
        }

        test("updateProfile with password success returns Success") {
            runTest {
                val service = mock<ProfileService>()
                val userDao = mock<UserDao>()
                everySuspend { userDao.getCurrentUser() } returns userEntity
                val expectedRequest =
                    UpdateProfileRequest(
                        password = PasswordChange(currentPassword = "cur12345", newPassword = "newpass12"),
                    )
                everySuspend {
                    service.updateMyProfile(expectedRequest)
                } returns WireAppResult.Success(stubProfile)

                val result =
                    repo(userDao = userDao, service = service).updateProfile(
                        firstName = null,
                        lastName = null,
                        tagline = null,
                        password = PasswordChange(currentPassword = "cur12345", newPassword = "newpass12"),
                    )

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend {
                    service.updateMyProfile(
                        UpdateProfileRequest(
                            password = PasswordChange(currentPassword = "cur12345", newPassword = "newpass12"),
                        ),
                    )
                }
            }
        }

        // ── Avatar upload (REST multipart, not RPC) ──────────────────────────────────────

        /*
         * The server returns 200 with { "avatarUpdatedAt": <ms> } on a successful upload. The
         * uploader parses that body and returns the server's avatar version, which the repository
         * writes verbatim into the observed public_profiles row (no client-clock guess).
         */
        test("avatarUploaderOf returns the server avatarUpdatedAt on a 200 response") {
            runTest {
                val engine =
                    MockEngine { _ ->
                        respond(
                            content = """{"avatarUpdatedAt":4242}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                val fakeClient = HttpClient(engine) { install(ContentNegotiation) { json(contractJson) } }
                val fakeFactory =
                    mock<ApiClientFactory> {
                        everySuspend { getClient() } returns fakeClient
                    }

                val result = avatarUploaderOf(fakeFactory).upload(ByteArray(4), "image/jpeg")

                result.shouldBeInstanceOf<AppResult.Success<Long>>()
                (result as AppResult.Success).data shouldBe 4242L
            }
        }

        test("uploadAvatar writes the self public_profiles row and caches the bytes locally on success") {
            runTest {
                val bytes = byteArrayOf(1, 2, 3)
                val serverTs = 7_000L
                val userDao = mock<UserDao>()
                val publicProfileDao = mock<PublicProfileDao>()
                val avatarUploader = mock<AvatarUploader>()
                val imageStorage = mock<ImageStorage>()

                everySuspend { userDao.getCurrentUser() } returns userEntity
                everySuspend { publicProfileDao.findById(userId) } returns null
                everySuspend { publicProfileDao.upsert(any()) } returns Unit
                everySuspend { avatarUploader.upload(bytes, "image/jpeg") } returns AppResult.Success(serverTs)
                everySuspend { imageStorage.saveUserAvatar(userId, bytes) } returns AppResult.Success(Unit)

                val result =
                    repo(
                        userDao = userDao,
                        publicProfileDao = publicProfileDao,
                        avatarUploader = avatarUploader,
                        imageStorage = imageStorage,
                    ).uploadAvatar(bytes, "image/jpeg")

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend { publicProfileDao.upsert(any()) }
                verifySuspend { imageStorage.saveUserAvatar(userId, bytes) }
            }
        }
    })
