package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.usecase.admin.ApproveUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DeleteUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DenyUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.GetRegistrationPolicyUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadInvitesUseCase
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
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

        /**
         * A fake-ish [AdminRepository] whose only wired behaviour is [AdminRepository.observeRoster]
         * — the only method [AdminViewModel] calls on it. [rosterFlow] is exposed to the caller so
         * a test can push new roster snapshots and assert the ViewModel reacts without a poll.
         */
        fun createMockAdminRepository(
            rosterFlow: MutableStateFlow<List<AdminUserInfo>> = MutableStateFlow(emptyList()),
        ): AdminRepository {
            val repo: AdminRepository = mock()
            every { repo.observeRoster() } returns rosterFlow
            return repo
        }

        fun createUser(
            id: String = "user-1",
            email: String = "test@example.com",
            status: String = "ACTIVE",
        ) = AdminUserInfo(
            id = id,
            email = email,
            displayName = "Test User",
            firstName = "Test",
            lastName = "User",
            isRoot = false,
            role = "user",
            status = status,
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
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val deleteUserUseCase: DeleteUserUseCase = mock()
                val revokeInviteUseCase: RevokeInviteUseCase = mock()
                val approveUserUseCase: ApproveUserUseCase = mock()
                val denyUserUseCase: DenyUserUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()

                everySuspend { loadInvitesUseCase() } returns AppResult.Success(emptyList())

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = deleteUserUseCase,
                        revokeInviteUseCase = revokeInviteUseCase,
                        approveUserUseCase = approveUserUseCase,
                        denyUserUseCase = denyUserUseCase,
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        adminRepository = createMockAdminRepository(),
                    )

                viewModel.state.value.shouldBeInstanceOf<AdminUiState.Loading>()
            }
        }

        test("loadData transitions to Ready with invites, and the roster populates users") {
            runTest {
                val getRegistrationPolicyUseCase = createMockGetRegistrationPolicyUseCase()
                val loadInvitesUseCase: LoadInvitesUseCase = mock()

                val invites = listOf(createInvite("invite-1"))
                everySuspend { loadInvitesUseCase() } returns AppResult.Success(invites)
                val rosterFlow = MutableStateFlow(listOf(createUser("user-1"), createUser("user-2")))

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = mock(),
                        revokeInviteUseCase = mock(),
                        approveUserUseCase = mock(),
                        denyUserUseCase = mock(),
                        setRegistrationPolicyUseCase = mock(),
                        adminRepository = createMockAdminRepository(rosterFlow),
                    )
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Ready>()
                ready.users.size shouldBe 2
                ready.pendingInvites.size shouldBe 1
            }
        }

        test("the roster splits into ACTIVE users and PENDING_APPROVAL pendingUsers") {
            runTest {
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                everySuspend { loadInvitesUseCase() } returns AppResult.Success(emptyList())

                val rosterFlow =
                    MutableStateFlow(
                        listOf(
                            createUser(id = "active-1", status = "ACTIVE"),
                            createUser(id = "pending-1", status = "PENDING_APPROVAL"),
                        ),
                    )

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = createMockGetRegistrationPolicyUseCase(),
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = mock(),
                        revokeInviteUseCase = mock(),
                        approveUserUseCase = mock(),
                        denyUserUseCase = mock(),
                        setRegistrationPolicyUseCase = mock(),
                        adminRepository = createMockAdminRepository(rosterFlow),
                    )
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Ready>()
                ready.users.map { it.id } shouldBe listOf("active-1")
                ready.pendingUsers.map { it.id } shouldBe listOf("pending-1")
            }
        }

        test("pushing a new ACTIVE user onto the roster flow updates users with no time advance") {
            runTest {
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                everySuspend { loadInvitesUseCase() } returns AppResult.Success(emptyList())
                val rosterFlow = MutableStateFlow<List<AdminUserInfo>>(emptyList())

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = createMockGetRegistrationPolicyUseCase(),
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = mock(),
                        revokeInviteUseCase = mock(),
                        approveUserUseCase = mock(),
                        denyUserUseCase = mock(),
                        setRegistrationPolicyUseCase = mock(),
                        adminRepository = createMockAdminRepository(rosterFlow),
                    )
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<AdminUiState.Ready>()
                    .users
                    .shouldBeEmpty()

                // Simulate the sync engine landing a claimed-invite user in Room. No delay/poll —
                // just letting the already-queued collector coroutine run — proves this is
                // event-driven, not polled.
                rosterFlow.value = listOf(createUser(id = "u1", status = "ACTIVE"))
                runCurrent()

                viewModel.state.value
                    .shouldBeInstanceOf<AdminUiState.Ready>()
                    .users
                    .map { it.id } shouldBe listOf("u1")
            }
        }

        test("setRegistrationPolicy(APPROVAL_QUEUE) round-trips to the approval-queue state on success") {
            runTest {
                val getRegistrationPolicyUseCase = createMockGetRegistrationPolicyUseCase(RegistrationPolicy.OPEN)
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()
                everySuspend { loadInvitesUseCase() } returns AppResult.Success(emptyList())
                everySuspend { setRegistrationPolicyUseCase(any()) } returns AppResult.Success(Unit)

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = mock(),
                        revokeInviteUseCase = mock(),
                        approveUserUseCase = mock(),
                        denyUserUseCase = mock(),
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        adminRepository = createMockAdminRepository(),
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
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()
                everySuspend { loadInvitesUseCase() } returns AppResult.Success(emptyList())
                everySuspend { setRegistrationPolicyUseCase(any()) } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .InternalError(),
                    )

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = mock(),
                        revokeInviteUseCase = mock(),
                        approveUserUseCase = mock(),
                        denyUserUseCase = mock(),
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        adminRepository = createMockAdminRepository(),
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
                everySuspend { loadInvitesUseCase() } returns AppResult.Success(invites)

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = deleteUserUseCase,
                        revokeInviteUseCase = revokeInviteUseCase,
                        approveUserUseCase = approveUserUseCase,
                        denyUserUseCase = denyUserUseCase,
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        adminRepository = createMockAdminRepository(),
                    )
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Ready>()
                ready.pendingInvites.size shouldBe 1
                ready.pendingInvites[0].id shouldBe "pending"
            }
        }

        test("loadData invites failure degrades to Ready and keeps the registration-policy control usable (#620)") {
            runTest {
                val getRegistrationPolicyUseCase = createMockGetRegistrationPolicyUseCase(RegistrationPolicy.OPEN)
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val deleteUserUseCase: DeleteUserUseCase = mock()
                val revokeInviteUseCase: RevokeInviteUseCase = mock()
                val approveUserUseCase: ApproveUserUseCase = mock()
                val denyUserUseCase: DenyUserUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()

                everySuspend { loadInvitesUseCase() } returns Failure(RuntimeException("Network error"))
                everySuspend { setRegistrationPolicyUseCase(any()) } returns AppResult.Success(Unit)

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = deleteUserUseCase,
                        revokeInviteUseCase = revokeInviteUseCase,
                        approveUserUseCase = approveUserUseCase,
                        denyUserUseCase = denyUserUseCase,
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        adminRepository = createMockAdminRepository(),
                    )
                advanceUntilIdle()

                // An invites-load failure must NOT black out the page. It degrades to Ready —
                // policy loaded, invites empty, and the failure surfaced honestly — so the
                // (independent) registration-policy control stays reachable.
                val ready = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Ready>()
                ready.registrationPolicy shouldBe RegistrationPolicy.OPEN
                ready.pendingInvites.shouldBeEmpty()
                ready.error.shouldBeInstanceOf<String>() shouldContain "invites"

                // The control now actually works: changing the policy takes effect.
                viewModel.setRegistrationPolicy(RegistrationPolicy.CLOSED)
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<AdminUiState.Ready>()
                    .registrationPolicy shouldBe RegistrationPolicy.CLOSED
            }
        }

        test("deleteUser removes user from list") {
            runTest {
                val getRegistrationPolicyUseCase = createMockGetRegistrationPolicyUseCase()
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val deleteUserUseCase: DeleteUserUseCase = mock()
                val revokeInviteUseCase: RevokeInviteUseCase = mock()
                val approveUserUseCase: ApproveUserUseCase = mock()
                val denyUserUseCase: DenyUserUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()

                val rosterFlow = MutableStateFlow(listOf(createUser("user-1"), createUser("user-2")))
                everySuspend { loadInvitesUseCase() } returns AppResult.Success(emptyList())
                everySuspend { deleteUserUseCase("user-1") } returns AppResult.Success(Unit)

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = deleteUserUseCase,
                        revokeInviteUseCase = revokeInviteUseCase,
                        approveUserUseCase = approveUserUseCase,
                        denyUserUseCase = denyUserUseCase,
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        adminRepository = createMockAdminRepository(rosterFlow),
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
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val deleteUserUseCase: DeleteUserUseCase = mock()
                val revokeInviteUseCase: RevokeInviteUseCase = mock()
                val approveUserUseCase: ApproveUserUseCase = mock()
                val denyUserUseCase: DenyUserUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()

                val invites = listOf(createInvite("invite-1"), createInvite("invite-2"))
                everySuspend { loadInvitesUseCase() } returns AppResult.Success(invites)
                everySuspend { revokeInviteUseCase("invite-1") } returns AppResult.Success(Unit)

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = deleteUserUseCase,
                        revokeInviteUseCase = revokeInviteUseCase,
                        approveUserUseCase = approveUserUseCase,
                        denyUserUseCase = denyUserUseCase,
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        adminRepository = createMockAdminRepository(),
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
                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val deleteUserUseCase: DeleteUserUseCase = mock()
                val revokeInviteUseCase: RevokeInviteUseCase = mock()
                val approveUserUseCase: ApproveUserUseCase = mock()
                val denyUserUseCase: DenyUserUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()

                // Invites fails to surface a transient error on Ready.
                everySuspend { loadInvitesUseCase() } returns Failure(RuntimeException("Invites error"))

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = deleteUserUseCase,
                        revokeInviteUseCase = revokeInviteUseCase,
                        approveUserUseCase = approveUserUseCase,
                        denyUserUseCase = denyUserUseCase,
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        adminRepository = createMockAdminRepository(),
                    )
                advanceUntilIdle()
                val beforeClear = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Ready>()
                (beforeClear.error != null) shouldBe true

                viewModel.clearError()

                val afterClear = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Ready>()
                afterClear.error shouldBe null
            }
        }

        test("loadData fetches registrationPolicy and invites in parallel") {
            runTest {
                val getRegistrationPolicyUseCase: GetRegistrationPolicyUseCase = mock()
                everySuspend { getRegistrationPolicyUseCase() } calls {
                    delay(100)
                    AppResult.Success(RegistrationPolicy.CLOSED)
                }

                val loadInvitesUseCase: LoadInvitesUseCase = mock()
                val deleteUserUseCase: DeleteUserUseCase = mock()
                val revokeInviteUseCase: RevokeInviteUseCase = mock()
                val approveUserUseCase: ApproveUserUseCase = mock()
                val denyUserUseCase: DenyUserUseCase = mock()
                val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase = mock()

                everySuspend { loadInvitesUseCase() } calls {
                    delay(100)
                    AppResult.Success(listOf(createInvite()))
                }

                val viewModel =
                    AdminViewModel(
                        getRegistrationPolicyUseCase = getRegistrationPolicyUseCase,
                        loadInvitesUseCase = loadInvitesUseCase,
                        deleteUserUseCase = deleteUserUseCase,
                        revokeInviteUseCase = revokeInviteUseCase,
                        approveUserUseCase = approveUserUseCase,
                        denyUserUseCase = denyUserUseCase,
                        setRegistrationPolicyUseCase = setRegistrationPolicyUseCase,
                        adminRepository = createMockAdminRepository(),
                    )

                // If parallel, both calls start at t=0 and complete at t=100ms.
                // If sequential, they'd complete at t=200ms.
                // Advance 150ms — enough for parallel, not enough for sequential.
                advanceTimeBy(150)

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminUiState.Ready>()
                ready.pendingInvites.size shouldBe 1
            }
        }
    })
