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
 * Unit tests for [ProfileEditRepositoryImpl] — RPC-dispatched mutations + local Room updates.
 *
 * Verifies that each edit method calls [ProfileService] with the correct request, maps the
 * contract-layer [WireAppResult] to the client [AppResult], and updates local Room on success.
 * [uploadAvatar] relies on [AvatarUploader] (tested via integration with the REST endpoint);
 * the RPC-path methods are unit-tested here.
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

        /** No-op [AvatarUploader] — upload is not exercised in these unit tests. */
        val noOpUploader = AvatarUploader { _, _ -> AppResult.Success(Unit) }

        fun repo(
            userDao: UserDao = mock(),
            service: ProfileService = mock(),
        ): ProfileEditRepositoryImpl {
            val rpcFactory: ProfileRpcFactory = mock()
            everySuspend { rpcFactory.get() } returns service
            return ProfileEditRepositoryImpl(
                userDao = userDao,
                profileRpcFactory = rpcFactory,
                avatarUploader = noOpUploader,
            )
        }

        test("updateTagline calls ProfileService with correct request and updates local Room on success") {
            runTest {
                val service = mock<ProfileService>()
                val userDao = mock<UserDao>()
                everySuspend { userDao.getCurrentUser() } returns userEntity
                everySuspend {
                    service.updateMyProfile(UpdateProfileRequest(tagline = "Hi"))
                } returns WireAppResult.Success(stubProfile)
                everySuspend { userDao.updateTagline(any(), any(), any()) } returns Unit

                val result = repo(userDao = userDao, service = service).updateTagline("Hi")

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend { service.updateMyProfile(UpdateProfileRequest(tagline = "Hi")) }
                verifySuspend { userDao.updateTagline(userId, "Hi", any()) }
            }
        }

        test("updateTagline returns Failure when service returns wire failure") {
            runTest {
                val service = mock<ProfileService>()
                val userDao = mock<UserDao>()
                everySuspend { userDao.getCurrentUser() } returns userEntity
                everySuspend {
                    service.updateMyProfile(UpdateProfileRequest(tagline = "Hi"))
                } returns WireAppResult.Failure(ProfileError.WrongPassword())

                val result = repo(userDao = userDao, service = service).updateTagline("Hi")

                result.shouldBeInstanceOf<AppResult.Failure>()
            }
        }

        test("changePassword calls service with PasswordChange and maps WrongPassword failure to core Failure") {
            runTest {
                val service = mock<ProfileService>()
                val userDao = mock<UserDao>()
                everySuspend { userDao.getCurrentUser() } returns userEntity
                val expectedRequest =
                    UpdateProfileRequest(password = PasswordChange("cur12345", "newpass12"))
                everySuspend {
                    service.updateMyProfile(expectedRequest)
                } returns WireAppResult.Failure(ProfileError.WrongPassword())

                val result = repo(userDao = userDao, service = service).changePassword("cur12345", "newpass12")

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<ProfileError.WrongPassword>()
            }
        }

        test("changePassword success calls service and returns Success") {
            runTest {
                val service = mock<ProfileService>()
                val userDao = mock<UserDao>()
                everySuspend { userDao.getCurrentUser() } returns userEntity
                val expectedRequest =
                    UpdateProfileRequest(password = PasswordChange("cur12345", "newpass12"))
                everySuspend {
                    service.updateMyProfile(expectedRequest)
                } returns WireAppResult.Success(stubProfile)

                val result = repo(userDao = userDao, service = service).changePassword("cur12345", "newpass12")

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend {
                    service.updateMyProfile(UpdateProfileRequest(password = PasswordChange("cur12345", "newpass12")))
                }
            }
        }

        // ── Avatar upload (REST multipart, not RPC) ──────────────────────────────────────

        /**
         * Regression guard for #592: the server returns 204 No Content on a successful avatar
         * upload. The original [avatarUploaderOf] called `.body<ApiResponse<Unit>>()` on the
         * response, which tried to deserialize an empty body as JSON and threw, causing the
         * upload to report failure even though the server had accepted the image.
         *
         * This test drives [avatarUploaderOf] against a mock engine that returns 204 with an
         * empty body. The fix is to check [HttpResponse.status.isSuccess()] instead of
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
    })
