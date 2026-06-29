package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.profile.PasswordChange
import com.calypsan.listenup.api.dto.profile.Profile
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import com.calypsan.listenup.api.error.ProfileError
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserEntity
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.ProfileRpcFactory
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [ProfileEditRepositoryImpl].
 *
 * Covers [ProfileEditRepositoryImpl.updateProfile] — the consolidated RPC call for all
 * text-field changes — plus the avatar REST transport helpers. Each test verifies that the
 * correct [ProfileService.updateMyProfile] request is sent and that Room is updated on success.
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
                avatarType = "auto",
                avatarValue = null,
                avatarColor = "#6B7280",
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

        /** No-op [AvatarUploader] — avatar upload is not exercised in unit tests. */
        val noOpUploader = AvatarUploader { _, _ -> AppResult.Success(Unit) }

        fun repo(
            userDao: UserDao = mock(),
            service: ProfileService = mock(),
            avatarUploader: AvatarUploader = noOpUploader,
            imageStorage: ImageStorage = mock<ImageStorage>(),
        ): ProfileEditRepositoryImpl {
            val rpcFactory: ProfileRpcFactory = mock()
            everySuspend { rpcFactory.get() } returns service
            everySuspend { imageStorage.saveUserAvatar(any(), any()) } returns AppResult.Success(Unit)
            return ProfileEditRepositoryImpl(
                userDao = userDao,
                profileRpcFactory = rpcFactory,
                avatarUploader = avatarUploader,
                imageStorage = imageStorage,
            )
        }

        // ── updateProfile — tagline only ──────────────────────────────────────

        test("updateProfile sends only tagline when name and password are null") {
            runTest {
                val service = mock<ProfileService>()
                val userDao = mock<UserDao>()
                everySuspend { userDao.getCurrentUser() } returns userEntity
                everySuspend {
                    service.updateMyProfile(UpdateProfileRequest(tagline = "Hi"))
                } returns WireAppResult.Success(stubProfile)
                everySuspend { userDao.updateTagline(any(), any(), any()) } returns Unit

                val result =
                    repo(userDao = userDao, service = service).updateProfile(
                        firstName = null,
                        lastName = null,
                        tagline = "Hi",
                        password = null,
                    )

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend { service.updateMyProfile(UpdateProfileRequest(tagline = "Hi")) }
                verifySuspend { userDao.updateTagline(userId, "Hi", any()) }
            }
        }

        test("updateProfile returns Failure when service returns wire failure") {
            runTest {
                val service = mock<ProfileService>()
                val userDao = mock<UserDao>()
                everySuspend { userDao.getCurrentUser() } returns userEntity
                everySuspend {
                    service.updateMyProfile(UpdateProfileRequest(tagline = "Hi"))
                } returns WireAppResult.Failure(ProfileError.WrongPassword())

                val result =
                    repo(userDao = userDao, service = service).updateProfile(
                        firstName = null,
                        lastName = null,
                        tagline = "Hi",
                        password = null,
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
            }
        }

        // ── updateProfile — name only ─────────────────────────────────────────

        test("updateProfile sends displayName computed from firstName+lastName and updates Room") {
            runTest {
                val service = mock<ProfileService>()
                val userDao = mock<UserDao>()
                everySuspend { userDao.getCurrentUser() } returns userEntity
                everySuspend {
                    service.updateMyProfile(UpdateProfileRequest(displayName = "Bob Jones"))
                } returns WireAppResult.Success(stubProfile)
                everySuspend { userDao.updateName(any(), any(), any(), any(), any()) } returns Unit

                val result =
                    repo(userDao = userDao, service = service).updateProfile(
                        firstName = "Bob",
                        lastName = "Jones",
                        tagline = null,
                        password = null,
                    )

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend {
                    service.updateMyProfile(UpdateProfileRequest(displayName = "Bob Jones"))
                }
                verifySuspend {
                    userDao.updateName(userId, "Bob", "Jones", "Bob Jones", any())
                }
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
         * Regression guard: the server returns 204 No Content on a successful avatar
         * upload. The original avatarUploaderOf called `.body<ApiResponse<Unit>>()` on the
         * response, which tried to deserialize an empty body as JSON and threw, causing the
         * upload to report failure even though the server had accepted the image.
         *
         * This test drives avatarUploaderOf against a mock engine that returns 204 with an
         * empty body. The fix is to check HttpResponse.status.isSuccess() instead of
         * deserializing the body.
         */
        test("avatarUploaderOf returns Success when server responds 204 No Content") {
            runTest {
                val engine =
                    MockEngine { _ ->
                        respond(content = "", status = HttpStatusCode.NoContent)
                    }
                val fakeClient = HttpClient(engine)
                val fakeFactory =
                    mock<ApiClientFactory> {
                        everySuspend { getClient() } returns fakeClient
                    }

                val result = avatarUploaderOf(fakeFactory).upload(ByteArray(4), "image/jpeg")

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("uploadAvatar caches the uploaded bytes locally on success") {
            runTest {
                val bytes = byteArrayOf(1, 2, 3)
                val userDao = mock<UserDao>()
                val avatarUploader = mock<AvatarUploader>()
                val imageStorage = mock<ImageStorage>()

                everySuspend { userDao.getCurrentUser() } returns userEntity
                everySuspend { userDao.updateAvatar(any(), any(), any(), any(), any()) } returns Unit
                everySuspend { avatarUploader.upload(bytes, "image/jpeg") } returns AppResult.Success(Unit)
                everySuspend { imageStorage.saveUserAvatar(userId, bytes) } returns AppResult.Success(Unit)

                val result =
                    repo(
                        userDao = userDao,
                        avatarUploader = avatarUploader,
                        imageStorage = imageStorage,
                    ).uploadAvatar(bytes, "image/jpeg")

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend { imageStorage.saveUserAvatar(userId, bytes) }
            }
        }
    })
