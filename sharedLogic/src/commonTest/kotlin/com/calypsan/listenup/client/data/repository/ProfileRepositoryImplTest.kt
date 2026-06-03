package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.profile.Profile
import com.calypsan.listenup.api.error.ProfileError
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserEntity
import com.calypsan.listenup.client.data.local.db.UserProfileDao
import com.calypsan.listenup.client.data.local.db.UserProfileEntity
import com.calypsan.listenup.client.data.remote.ProfileApiContract
import com.calypsan.listenup.client.data.remote.ProfileRpcFactory
import com.calypsan.listenup.client.data.remote.model.FullProfileResponse
import com.calypsan.listenup.client.domain.repository.AvatarDownloadRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [ProfileRepositoryImpl] — own-profile fetch via [ProfileService] RPC
 * and resulting local Room cache updates.
 *
 * The [ProfileApiContract.getUserProfile] path (other-user fetch) is covered by a
 * dedicated test at the end of this suite.
 */
class ProfileRepositoryImplTest :
    FunSpec({

        val userId = "user-42"
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
                displayName = "Server Name",
                tagline = "Hello from server",
                avatarType = "auto",
                updatedAt = 2_000L,
            )

        val stubImageProfile =
            Profile(
                userId = UserId(userId),
                displayName = "Image User",
                tagline = null,
                avatarType = "image",
                updatedAt = 3_000L,
            )

        /** Minimal [ProfileApiContract] stub for tests that don't exercise the other-user path. */
        val unusedProfileApi = mock<ProfileApiContract>()

        fun repo(
            userDao: UserDao = mock(),
            userProfileDao: UserProfileDao = mock(),
            service: ProfileService = mock(),
            avatarDownloadRepository: AvatarDownloadRepository = mock(),
        ): ProfileRepositoryImpl {
            val rpcFactory = mock<ProfileRpcFactory>()
            everySuspend { rpcFactory.get() } returns service
            return ProfileRepositoryImpl(
                profileApi = unusedProfileApi,
                profileRpcFactory = rpcFactory,
                userDao = userDao,
                userProfileDao = userProfileDao,
                avatarDownloadRepository = avatarDownloadRepository,
            )
        }

        test("refreshMyProfile upserts UserProfileDao and updates UserDao with server profile fields on success") {
            runTest {
                val service = mock<ProfileService>()
                val userDao = mock<UserDao>()
                val userProfileDao = mock<UserProfileDao>()
                val avatarDownloadRepository = mock<AvatarDownloadRepository>()
                everySuspend { userDao.getCurrentUser() } returns userEntity
                everySuspend { service.getMyProfile() } returns WireAppResult.Success(stubProfile)
                everySuspend { userDao.updateTagline(any(), any(), any()) } returns Unit
                everySuspend { userDao.updateAvatar(any(), any(), any(), any(), any()) } returns Unit
                everySuspend { userProfileDao.upsert(any()) } returns Unit
                everySuspend { avatarDownloadRepository.deleteAvatar(any()) } returns Unit

                val result =
                    repo(
                        userDao = userDao,
                        userProfileDao = userProfileDao,
                        service = service,
                        avatarDownloadRepository = avatarDownloadRepository,
                    ).refreshMyProfile()

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend { service.getMyProfile() }
                verifySuspend { userDao.updateTagline(userId, "Hello from server", any()) }
                verifySuspend {
                    userProfileDao.upsert(
                        UserProfileEntity(
                            id = userId,
                            displayName = "Server Name",
                            avatarType = "auto",
                            avatarValue = null,
                            avatarColor = "#6B7280",
                            updatedAt = 2_000L,
                        ),
                    )
                }
            }
        }

        test("refreshMyProfile queues force-refresh avatar download when avatarType is image") {
            runTest {
                val service = mock<ProfileService>()
                val userDao = mock<UserDao>()
                val userProfileDao = mock<UserProfileDao>()
                val avatarDownloadRepository = mock<AvatarDownloadRepository>()
                everySuspend { userDao.getCurrentUser() } returns userEntity
                everySuspend { service.getMyProfile() } returns WireAppResult.Success(stubImageProfile)
                everySuspend { userDao.updateTagline(any(), any(), any()) } returns Unit
                everySuspend { userDao.updateAvatar(any(), any(), any(), any(), any()) } returns Unit
                everySuspend { userProfileDao.upsert(any()) } returns Unit
                every { avatarDownloadRepository.queueAvatarForceRefresh(any()) } returns Unit

                repo(
                    userDao = userDao,
                    userProfileDao = userProfileDao,
                    service = service,
                    avatarDownloadRepository = avatarDownloadRepository,
                ).refreshMyProfile()

                verify { avatarDownloadRepository.queueAvatarForceRefresh(userId) }
            }
        }

        test("refreshMyProfile deletes local avatar when avatarType is auto") {
            runTest {
                val service = mock<ProfileService>()
                val userDao = mock<UserDao>()
                val userProfileDao = mock<UserProfileDao>()
                val avatarDownloadRepository = mock<AvatarDownloadRepository>()
                everySuspend { userDao.getCurrentUser() } returns userEntity
                everySuspend { service.getMyProfile() } returns WireAppResult.Success(stubProfile)
                everySuspend { userDao.updateTagline(any(), any(), any()) } returns Unit
                everySuspend { userDao.updateAvatar(any(), any(), any(), any(), any()) } returns Unit
                everySuspend { userProfileDao.upsert(any()) } returns Unit
                everySuspend { avatarDownloadRepository.deleteAvatar(any()) } returns Unit

                repo(
                    userDao = userDao,
                    userProfileDao = userProfileDao,
                    service = service,
                    avatarDownloadRepository = avatarDownloadRepository,
                ).refreshMyProfile()

                verifySuspend { avatarDownloadRepository.deleteAvatar(userId) }
            }
        }

        test("refreshMyProfile returns Failure when service returns wire failure") {
            runTest {
                val service = mock<ProfileService>()
                val userDao = mock<UserDao>()
                everySuspend { userDao.getCurrentUser() } returns userEntity
                everySuspend { service.getMyProfile() } returns
                    WireAppResult.Failure(ProfileError.WrongPassword())

                val result =
                    repo(userDao = userDao, service = service).refreshMyProfile()

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.shouldBeInstanceOf<ProfileError.WrongPassword>()
            }
        }

        test("refreshMyProfile returns Failure when no current user") {
            runTest {
                val service = mock<ProfileService>()
                val userDao = mock<UserDao>()
                everySuspend { userDao.getCurrentUser() } returns null

                val result =
                    repo(userDao = userDao, service = service).refreshMyProfile()

                result.shouldBeInstanceOf<AppResult.Failure>()
            }
        }

        test("getUserProfile delegates to ProfileApiContract for other-user fetches") {
            runTest {
                val profileApi = mock<ProfileApiContract>()
                val rpcFactory = mock<ProfileRpcFactory>()
                val stubFullProfile =
                    FullProfileResponse(
                        userId = "other-1",
                        displayName = "Other User",
                        avatarType = "auto",
                        avatarValue = null,
                        avatarColor = "#000",
                        tagline = null,
                        totalListenTimeMs = 100L,
                        booksFinished = 2,
                        currentStreak = 1,
                        longestStreak = 3,
                        isOwnProfile = false,
                        recentBooks = emptyList(),
                        publicShelves = emptyList(),
                    )
                everySuspend { profileApi.getUserProfile("other-1") } returns
                    AppResult.Success(stubFullProfile)

                val repo =
                    ProfileRepositoryImpl(
                        profileApi = profileApi,
                        profileRpcFactory = rpcFactory,
                        userDao = mock(),
                        userProfileDao = mock(),
                        avatarDownloadRepository = mock(),
                    )

                val result = repo.getUserProfile("other-1")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                val profile = (result as AppResult.Success).data
                profile.userId shouldBe "other-1"
                profile.displayName shouldBe "Other User"
            }
        }
    })
