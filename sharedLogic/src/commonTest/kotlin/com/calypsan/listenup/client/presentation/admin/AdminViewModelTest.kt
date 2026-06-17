package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.model.AdminEvent
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.domain.repository.EventStreamRepository
import com.calypsan.listenup.client.domain.usecase.admin.ApproveUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DeleteUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DenyUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.GetRegistrationPolicyUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadInvitesUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadPendingUsersUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadUsersUseCase
import com.calypsan.listenup.client.domain.usecase.admin.RevokeInviteUseCase
import com.calypsan.listenup.client.domain.usecase.admin.SetRegistrationPolicyUseCase
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.matcher.any
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class AdminViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        fun createMockGetRegistrationPolicyUseCase(
            policy: RegistrationPolicy = RegistrationPolicy.CLOSED,
        ): GetRegistrationPolicyUseCase {
            val useCase: GetRegistrationPolicyUseCase = mock()
            everySuspend { useCase() } returns AppResult.Success(policy)
            return useCase
        }

        fun createMockEventStreamRepository(): EventStreamRepository {
            val eventStreamRepo: EventStreamRepository = mock()
            val adminEvents = MutableSharedFlow<AdminEvent>()
            every { eventStreamRepo.adminEvents } returns adminEvents
            return eventStreamRepo
        }

        fun createUser(
            id: String = "user-1",
            email: String = "test@example.com",
        ) = AdminUserInfo(
            id = id,
            email = email,
            displayName = "Test User",
            firstName = "Test",
            lastName = "User",
            isRoot = false,
            role = "user",
            status = "active",
            createdAt = "2024-01-01T00:00:00Z",
        )

        fun createInvite(
            id: String = "invite-1",
            claimedAt: String? = null,
        ) = InviteInfo(
            id = id,
            code = "ABC123",
            name = "Invited User",
            email = "invited@example.com",
            role = "user",
            expiresAt = "2024-02-01T00:00:00Z",
            claimedAt = claimedAt,
            url = "https://example.com/invite/ABC123",
            createdAt = "2024-01-01T00:00:00Z",
        )

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        test("initial state is Loading") {
            runTest {
                val getRegistrationPolicyUseCase = createMockGetRegistrationPolicyUseCase()
                val loadUsersUseCase: LoadUsersUseCase = mock()
                val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val deleteUserUseCase: DeleteUserUseCase = mock()
                val revokeInviteUseCase: RevokeInviteUseCase = mock()
                val approveUserUseCase: ApproveUserUseCase = mock()
                val denyUserUseCase: DenyUserUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()

                everySuspend { loadUsersUseCase() } returns AppResult.Success(emptyList())
                everySuspend { loadPendingUsersUseCase() } returns AppResult.Success(emptyList())
                everySuspend { loadInvitesUseCase() } returns AppResult.Success(emptyList())

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadUsersUseCase = loadUsersUseCase,
                        loadPendingUsersUseCase = loadPendingUsersUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = deleteUserUseCase,
                        revokeInviteUseCase = revokeInviteUseCase,
                        approveUserUseCase = approveUserUseCase,
                        denyUserUseCase = denyUserUseCase,
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        eventStreamRepository = createMockEventStreamRepository(),
                    )

                viewModel.state.value.shouldBeInstanceOf<AdminUiState.Loading>()
            }
        }

        test("loadData transitions to Ready with users and invites") {
            runTest {
                val getRegistrationPolicyUseCase = createMockGetRegistrationPolicyUseCase()
                val loadUsersUseCase: LoadUsersUseCase = mock()
                val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val deleteUserUseCase: DeleteUserUseCase = mock()
                val revokeInviteUseCase: RevokeInviteUseCase = mock()
                val approveUserUseCase: ApproveUserUseCase = mock()
                val denyUserUseCase: DenyUserUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()

                val users = listOf(createUser("user-1"), createUser("user-2"))
                val invites = listOf(createInvite("invite-1"))
                everySuspend { loadUsersUseCase() } returns AppResult.Success(users)
                everySuspend { loadPendingUsersUseCase() } returns AppResult.Success(emptyList())
                everySuspend { loadInvitesUseCase() } returns AppResult.Success(invites)

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadUsersUseCase = loadUsersUseCase,
                        loadPendingUsersUseCase = loadPendingUsersUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = deleteUserUseCase,
                        revokeInviteUseCase = revokeInviteUseCase,
                        approveUserUseCase = approveUserUseCase,
                        denyUserUseCase = denyUserUseCase,
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        eventStreamRepository = createMockEventStreamRepository(),
                    )
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Ready>()
                ready.users.size shouldBe 2
                ready.pendingInvites.size shouldBe 1
            }
        }

        test("setRegistrationPolicy(APPROVAL_QUEUE) round-trips to the approval-queue state on success") {
            runTest {
                val getRegistrationPolicyUseCase = createMockGetRegistrationPolicyUseCase(RegistrationPolicy.OPEN)
                val loadUsersUseCase: LoadUsersUseCase = mock()
                val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()
                everySuspend { loadUsersUseCase() } returns AppResult.Success(listOf(createUser()))
                everySuspend { loadPendingUsersUseCase() } returns AppResult.Success(emptyList())
                everySuspend { loadInvitesUseCase() } returns AppResult.Success(emptyList())
                everySuspend { setRegistrationPolicyUseCase(any()) } returns AppResult.Success(Unit)

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadUsersUseCase = loadUsersUseCase,
                        loadPendingUsersUseCase = loadPendingUsersUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = mock(),
                        revokeInviteUseCase = mock(),
                        approveUserUseCase = mock(),
                        denyUserUseCase = mock(),
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        eventStreamRepository = createMockEventStreamRepository(),
                    )
                advanceUntilIdle()

                viewModel.state.value
                    .shouldBeInstanceOf<AdminUiState.Ready>()
                    .registrationPolicy shouldBe RegistrationPolicy.OPEN

                viewModel.setRegistrationPolicy(RegistrationPolicy.APPROVAL_QUEUE)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Ready>()
                ready.registrationPolicy shouldBe RegistrationPolicy.APPROVAL_QUEUE
                ready.isTogglingRegistrationPolicy shouldBe false
            }
        }

        test("setRegistrationPolicy failure surfaces an error and leaves the policy unchanged") {
            runTest {
                val getRegistrationPolicyUseCase = createMockGetRegistrationPolicyUseCase(RegistrationPolicy.OPEN)
                val loadUsersUseCase: LoadUsersUseCase = mock()
                val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()
                everySuspend { loadUsersUseCase() } returns AppResult.Success(listOf(createUser()))
                everySuspend { loadPendingUsersUseCase() } returns AppResult.Success(emptyList())
                everySuspend { loadInvitesUseCase() } returns AppResult.Success(emptyList())
                everySuspend { setRegistrationPolicyUseCase(any()) } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .InternalError(),
                    )

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadUsersUseCase = loadUsersUseCase,
                        loadPendingUsersUseCase = loadPendingUsersUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = mock(),
                        revokeInviteUseCase = mock(),
                        approveUserUseCase = mock(),
                        denyUserUseCase = mock(),
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        eventStreamRepository = createMockEventStreamRepository(),
                    )
                advanceUntilIdle()

                viewModel.setRegistrationPolicy(RegistrationPolicy.CLOSED)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Ready>()
                ready.registrationPolicy shouldBe RegistrationPolicy.OPEN
                ready.error shouldBe
                    com.calypsan.listenup.api.error
                        .InternalError()
                        .message
                ready.isTogglingRegistrationPolicy shouldBe false
            }
        }

        test("loadData filters out claimed invites") {
            runTest {
                val getRegistrationPolicyUseCase = createMockGetRegistrationPolicyUseCase()
                val loadUsersUseCase: LoadUsersUseCase = mock()
                val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val deleteUserUseCase: DeleteUserUseCase = mock()
                val revokeInviteUseCase: RevokeInviteUseCase = mock()
                val approveUserUseCase: ApproveUserUseCase = mock()
                val denyUserUseCase: DenyUserUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()

                val invites =
                    listOf(
                        createInvite("pending", claimedAt = null),
                        createInvite("claimed", claimedAt = "2024-01-15T00:00:00Z"),
                    )
                everySuspend { loadUsersUseCase() } returns AppResult.Success(emptyList())
                everySuspend { loadPendingUsersUseCase() } returns AppResult.Success(emptyList())
                everySuspend { loadInvitesUseCase() } returns AppResult.Success(invites)

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadUsersUseCase = loadUsersUseCase,
                        loadPendingUsersUseCase = loadPendingUsersUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = deleteUserUseCase,
                        revokeInviteUseCase = revokeInviteUseCase,
                        approveUserUseCase = approveUserUseCase,
                        denyUserUseCase = denyUserUseCase,
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        eventStreamRepository = createMockEventStreamRepository(),
                    )
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Ready>()
                ready.pendingInvites.size shouldBe 1
                ready.pendingInvites[0].id shouldBe "pending"
            }
        }

        test("loadData initial users failure transitions to Error") {
            runTest {
                val getRegistrationPolicyUseCase = createMockGetRegistrationPolicyUseCase()
                val loadUsersUseCase: LoadUsersUseCase = mock()
                val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val deleteUserUseCase: DeleteUserUseCase = mock()
                val revokeInviteUseCase: RevokeInviteUseCase = mock()
                val approveUserUseCase: ApproveUserUseCase = mock()
                val denyUserUseCase: DenyUserUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()

                everySuspend { loadUsersUseCase() } returns Failure(RuntimeException("Network error"))
                everySuspend { loadPendingUsersUseCase() } returns AppResult.Success(emptyList())
                everySuspend { loadInvitesUseCase() } returns AppResult.Success(emptyList())

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadUsersUseCase = loadUsersUseCase,
                        loadPendingUsersUseCase = loadPendingUsersUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = deleteUserUseCase,
                        revokeInviteUseCase = revokeInviteUseCase,
                        approveUserUseCase = approveUserUseCase,
                        denyUserUseCase = denyUserUseCase,
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        eventStreamRepository = createMockEventStreamRepository(),
                    )
                advanceUntilIdle()

                val error = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Error>()
                error.message shouldContain "users"
            }
        }

        test("deleteUser removes user from list") {
            runTest {
                val getRegistrationPolicyUseCase = createMockGetRegistrationPolicyUseCase()
                val loadUsersUseCase: LoadUsersUseCase = mock()
                val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val deleteUserUseCase: DeleteUserUseCase = mock()
                val revokeInviteUseCase: RevokeInviteUseCase = mock()
                val approveUserUseCase: ApproveUserUseCase = mock()
                val denyUserUseCase: DenyUserUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()

                val users = listOf(createUser("user-1"), createUser("user-2"))
                everySuspend { loadUsersUseCase() } returns AppResult.Success(users)
                everySuspend { loadPendingUsersUseCase() } returns AppResult.Success(emptyList())
                everySuspend { loadInvitesUseCase() } returns AppResult.Success(emptyList())
                everySuspend { deleteUserUseCase("user-1") } returns AppResult.Success(Unit)

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadUsersUseCase = loadUsersUseCase,
                        loadPendingUsersUseCase = loadPendingUsersUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = deleteUserUseCase,
                        revokeInviteUseCase = revokeInviteUseCase,
                        approveUserUseCase = approveUserUseCase,
                        denyUserUseCase = denyUserUseCase,
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        eventStreamRepository = createMockEventStreamRepository(),
                    )
                advanceUntilIdle()
                val afterLoad = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Ready>()
                afterLoad.users.size shouldBe 2

                viewModel.deleteUser("user-1")
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Ready>()
                ready.users.size shouldBe 1
                ready.users[0].id shouldBe "user-2"
            }
        }

        test("revokeInvite removes invite from list") {
            runTest {
                val getRegistrationPolicyUseCase = createMockGetRegistrationPolicyUseCase()
                val loadUsersUseCase: LoadUsersUseCase = mock()
                val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val deleteUserUseCase: DeleteUserUseCase = mock()
                val revokeInviteUseCase: RevokeInviteUseCase = mock()
                val approveUserUseCase: ApproveUserUseCase = mock()
                val denyUserUseCase: DenyUserUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()

                val invites = listOf(createInvite("invite-1"), createInvite("invite-2"))
                everySuspend { loadUsersUseCase() } returns AppResult.Success(emptyList())
                everySuspend { loadPendingUsersUseCase() } returns AppResult.Success(emptyList())
                everySuspend { loadInvitesUseCase() } returns AppResult.Success(invites)
                everySuspend { revokeInviteUseCase("invite-1") } returns AppResult.Success(Unit)

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadUsersUseCase = loadUsersUseCase,
                        loadPendingUsersUseCase = loadPendingUsersUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = deleteUserUseCase,
                        revokeInviteUseCase = revokeInviteUseCase,
                        approveUserUseCase = approveUserUseCase,
                        denyUserUseCase = denyUserUseCase,
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        eventStreamRepository = createMockEventStreamRepository(),
                    )
                advanceUntilIdle()

                viewModel.revokeInvite("invite-1")
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Ready>()
                ready.pendingInvites.size shouldBe 1
                ready.pendingInvites[0].id shouldBe "invite-2"
            }
        }

        test("clearError clears error state") {
            runTest {
                val getRegistrationPolicyUseCase = createMockGetRegistrationPolicyUseCase()
                val loadUsersUseCase: LoadUsersUseCase = mock()
                val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val deleteUserUseCase: DeleteUserUseCase = mock()
                val revokeInviteUseCase: RevokeInviteUseCase = mock()
                val approveUserUseCase: ApproveUserUseCase = mock()
                val denyUserUseCase: DenyUserUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()

                // Users succeeds to land in Ready; invites fails to surface transient error on Ready.
                everySuspend { loadUsersUseCase() } returns AppResult.Success(emptyList())
                everySuspend { loadPendingUsersUseCase() } returns AppResult.Success(emptyList())
                everySuspend { loadInvitesUseCase() } returns Failure(RuntimeException("Invites error"))

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadUsersUseCase = loadUsersUseCase,
                        loadPendingUsersUseCase = loadPendingUsersUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = deleteUserUseCase,
                        revokeInviteUseCase = revokeInviteUseCase,
                        approveUserUseCase = approveUserUseCase,
                        denyUserUseCase = denyUserUseCase,
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        eventStreamRepository = createMockEventStreamRepository(),
                    )
                advanceUntilIdle()
                val beforeClear = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Ready>()
                (beforeClear.error != null) shouldBe true

                viewModel.clearError()

                val afterClear = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Ready>()
                afterClear.error shouldBe null
            }
        }

        test("loadData fetches all data in parallel") {
            runTest {
                val getRegistrationPolicyUseCase: GetRegistrationPolicyUseCase = mock()
                everySuspend { getRegistrationPolicyUseCase() } calls {
                    delay(100)
                    AppResult.Success(RegistrationPolicy.CLOSED)
                }

                val loadUsersUseCase: LoadUsersUseCase = mock()
                val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val deleteUserUseCase: DeleteUserUseCase = mock()
                val revokeInviteUseCase: RevokeInviteUseCase = mock()
                val approveUserUseCase: ApproveUserUseCase = mock()
                val denyUserUseCase: DenyUserUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()

                everySuspend { loadUsersUseCase() } calls {
                    delay(100)
                    AppResult.Success(listOf(createUser()))
                }
                everySuspend { loadPendingUsersUseCase() } calls {
                    delay(100)
                    AppResult.Success(emptyList())
                }
                everySuspend { loadInvitesUseCase() } calls {
                    delay(100)
                    AppResult.Success(listOf(createInvite()))
                }

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadUsersUseCase = loadUsersUseCase,
                        loadPendingUsersUseCase = loadPendingUsersUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = deleteUserUseCase,
                        revokeInviteUseCase = revokeInviteUseCase,
                        approveUserUseCase = approveUserUseCase,
                        denyUserUseCase = denyUserUseCase,
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        eventStreamRepository = createMockEventStreamRepository(),
                    )

                // If parallel, all 4 calls start at t=0 and complete at t=100ms.
                // If sequential, they'd complete at t=400ms.
                // Advance 150ms — enough for parallel, not enough for sequential.
                advanceTimeBy(150)

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Ready>()
                ready.users.size shouldBe 1
                ready.pendingInvites.size shouldBe 1
            }
        }

        test("the pending list refreshes on a poll tick while the screen is observed") {
            runTest {
                // Server-side pending list, flipped mid-test to simulate a new registration arriving.
                var pending: List<AdminUserInfo> = emptyList()
                val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
                everySuspend { loadPendingUsersUseCase() } calls { AppResult.Success(pending) }
                val loadUsersUseCase: LoadUsersUseCase = mock()
                everySuspend { loadUsersUseCase() } returns AppResult.Success(emptyList())
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                everySuspend { loadInvitesUseCase() } returns AppResult.Success(emptyList())

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase =
                            createMockGetRegistrationPolicyUseCase(RegistrationPolicy.APPROVAL_QUEUE),
                        loadUsersUseCase = loadUsersUseCase,
                        loadPendingUsersUseCase = loadPendingUsersUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = mock(),
                        revokeInviteUseCase = mock(),
                        approveUserUseCase = mock(),
                        denyUserUseCase = mock(),
                        setRegistrationPolicyUseCase = mock(),
                        eventStreamRepository = createMockEventStreamRepository(),
                    )

                // Observe state so the subscription-gated poll runs (auto-cancelled at test end).
                backgroundScope.launch { viewModel.state.collect {} }
                advanceTimeBy(200) // initial load settles → Ready, empty pending
                viewModel.state.value
                    .shouldBeInstanceOf<AdminUiState.Ready>()
                    .pendingUsers
                    .shouldBeEmpty()

                pending = listOf(createUser(id = "p1"))
                advanceTimeBy(11_000) // crosses the 10s poll cadence

                viewModel.state.value
                    .shouldBeInstanceOf<AdminUiState.Ready>()
                    .pendingUsers
                    .map { it.id } shouldBe listOf("p1")
            }
        }
    })
