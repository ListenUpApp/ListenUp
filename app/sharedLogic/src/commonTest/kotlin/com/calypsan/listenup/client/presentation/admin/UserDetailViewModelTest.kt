package com.calypsan.listenup.client.presentation.admin

import app.cash.turbine.test
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AccessLabel
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.UserPermissions
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.test.fake.FakeInstanceRepository
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class UserDetailViewModelTest :
    FunSpec({
        beforeTest { Dispatchers.setMain(StandardTestDispatcher()) }
        afterTest { Dispatchers.resetMain() }

        fun createUser(permissions: UserPermissions = UserPermissions(canEditMetadata = true)) =
            AdminUserInfo(
                id = "user-1",
                email = "test@example.com",
                displayName = "Test User",
                firstName = "Test",
                lastName = "User",
                isRoot = false,
                role = "member",
                status = "active",
                permissions = permissions,
                createdAt = "2024-01-01T00:00:00Z",
            )

        fun viewModel(
            adminRepository: AdminRepository,
            advertised: Set<String> = setOf("canEdit", "canCurateLibrary"),
        ) = UserDetailViewModel(
            userId = "user-1",
            adminRepository = adminRepository,
            instanceRepository = FakeInstanceRepository(advertised),
            errorBus = ErrorBus(),
        )

        test("the label follows the mirrored user, so a permissions save shows on return") {
            runTest {
                val mirrored = MutableStateFlow<AdminUserInfo?>(createUser())
                val adminRepository: AdminRepository = mock { every { observeUser("user-1") } returns mirrored }

                viewModel(adminRepository).state.test {
                    awaitItem().shouldBeInstanceOf<UserDetailUiState.Loading>()
                    awaitItem()
                        .shouldBeInstanceOf<UserDetailUiState.Ready>()
                        .user.access shouldBe AccessLabel.CONTRIBUTOR

                    // The permissions screen saves; the server's roster frame lands in Room.
                    mirrored.value = createUser(UserPermissions(canEditMetadata = true, canCurateLibrary = true))

                    awaitItem()
                        .shouldBeInstanceOf<UserDetailUiState.Ready>()
                        .user.access shouldBe AccessLabel.LIBRARIAN
                }
                verifySuspend(VerifyMode.not) { adminRepository.getUser("user-1") }
            }
        }

        test("a user the mirror has not synced yet is fetched from the server") {
            runTest {
                val user = createUser(UserPermissions(canEditMetadata = false))
                val adminRepository: AdminRepository =
                    mock {
                        every { observeUser("user-1") } returns flowOf(null)
                        everySuspend { getUser("user-1") } returns AppResult.Success(user)
                    }

                viewModel(adminRepository).state.test {
                    awaitItem().shouldBeInstanceOf<UserDetailUiState.Loading>()
                    awaitItem().shouldBeInstanceOf<UserDetailUiState.Ready>().user shouldBe
                        user.copy(access = AccessLabel.LISTENER)
                }
            }
        }

        test("an unsynced user whose fetch fails is the Error state") {
            runTest {
                val adminRepository: AdminRepository =
                    mock {
                        every { observeUser("user-1") } returns flowOf(null)
                        everySuspend { getUser("user-1") } returns AppResult.Failure(TransportError.NetworkUnavailable())
                    }

                viewModel(adminRepository).state.test {
                    awaitItem().shouldBeInstanceOf<UserDetailUiState.Loading>()
                    awaitItem().shouldBeInstanceOf<UserDetailUiState.Error>()
                }
            }
        }

        test("against an older server the label is the role, not a preset") {
            runTest {
                val adminRepository: AdminRepository = mock { every { observeUser("user-1") } returns flowOf(createUser()) }

                viewModel(adminRepository, advertised = setOf("canEdit")).state.test {
                    awaitItem().shouldBeInstanceOf<UserDetailUiState.Loading>()
                    awaitItem()
                        .shouldBeInstanceOf<UserDetailUiState.Ready>()
                        .user.access shouldBe AccessLabel.MEMBER
                }
            }
        }
    })
