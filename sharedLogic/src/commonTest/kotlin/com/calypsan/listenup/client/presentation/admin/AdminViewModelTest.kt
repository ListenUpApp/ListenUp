package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.model.AdminEvent
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.Instance
import com.calypsan.listenup.client.domain.model.InstanceId
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.domain.repository.EventStreamRepository
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.usecase.admin.ApproveUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DeleteUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DenyUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadInvitesUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadPendingUsersUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadUsersUseCase
import com.calypsan.listenup.client.domain.usecase.admin.RevokeInviteUseCase
import com.calypsan.listenup.client.domain.usecase.admin.SetOpenRegistrationUseCase
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.calypsan.listenup.core.Timestamp

@OptIn(ExperimentalCoroutinesApi::class)
class AdminViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private fun createMockInstance(openRegistration: Boolean = false) =
        Instance(
            id = InstanceId("test-instance"),
            name = "Test Server",
            version = "1.0.0",
            localUrl = "http://localhost:8080",
            remoteUrl = null,
            openRegistration = openRegistration,
            setupRequired = false,
            createdAt = Timestamp(0L),
            updatedAt = Timestamp(0L),
        )

    private fun createMockInstanceRepository(openRegistration: Boolean = false): InstanceRepository {
        val instanceRepo: InstanceRepository = mock()
        everySuspend { instanceRepo.getInstance() } returns AppResult.Success(createMockInstance(openRegistration))
        return instanceRepo
    }

    private fun createMockEventStreamRepository(): EventStreamRepository {
        val eventStreamRepo: EventStreamRepository = mock()
        val adminEvents = MutableSharedFlow<AdminEvent>()
        every { eventStreamRepo.adminEvents } returns adminEvents
        return eventStreamRepo
    }

    private fun createUser(
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

    private fun createInvite(
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

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Loading`() =
        runTest {
            val instanceRepo = createMockInstanceRepository()
            val loadUsersUseCase: LoadUsersUseCase = mock()
            val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
            val loadInvitesUseCase: LoadInvitesUseCase = mock()
            val deleteUserUseCase: DeleteUserUseCase = mock()
            val revokeInviteUseCase: RevokeInviteUseCase = mock()
            val approveUserUseCase: ApproveUserUseCase = mock()
            val denyUserUseCase: DenyUserUseCase = mock()
            val setOpenRegistrationUseCase: SetOpenRegistrationUseCase = mock()

            everySuspend { loadUsersUseCase() } returns AppResult.Success(emptyList())
            everySuspend { loadPendingUsersUseCase() } returns AppResult.Success(emptyList())
            everySuspend { loadInvitesUseCase() } returns AppResult.Success(emptyList())

            val viewModel =
                AdminViewModel(
                    instanceRepository = instanceRepo,
                    loadUsersUseCase = loadUsersUseCase,
                    loadPendingUsersUseCase = loadPendingUsersUseCase,
                    loadInvitesUseCase = loadInvitesUseCase,
                    deleteUserUseCase = deleteUserUseCase,
                    revokeInviteUseCase = revokeInviteUseCase,
                    approveUserUseCase = approveUserUseCase,
                    denyUserUseCase = denyUserUseCase,
                    setOpenRegistrationUseCase = setOpenRegistrationUseCase,
                    eventStreamRepository = createMockEventStreamRepository(),
                )

            assertIs<AdminUiState.Loading>(viewModel.state.value)
        }

    @Test
    fun `loadData transitions to Ready with users and invites`() =
        runTest {
            val instanceRepo = createMockInstanceRepository()
            val loadUsersUseCase: LoadUsersUseCase = mock()
            val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
            val loadInvitesUseCase: LoadInvitesUseCase = mock()
            val deleteUserUseCase: DeleteUserUseCase = mock()
            val revokeInviteUseCase: RevokeInviteUseCase = mock()
            val approveUserUseCase: ApproveUserUseCase = mock()
            val denyUserUseCase: DenyUserUseCase = mock()
            val setOpenRegistrationUseCase: SetOpenRegistrationUseCase = mock()

            val users = listOf(createUser("user-1"), createUser("user-2"))
            val invites = listOf(createInvite("invite-1"))
            everySuspend { loadUsersUseCase() } returns AppResult.Success(users)
            everySuspend { loadPendingUsersUseCase() } returns AppResult.Success(emptyList())
            everySuspend { loadInvitesUseCase() } returns AppResult.Success(invites)

            val viewModel =
                AdminViewModel(
                    instanceRepository = instanceRepo,
                    loadUsersUseCase = loadUsersUseCase,
                    loadPendingUsersUseCase = loadPendingUsersUseCase,
                    loadInvitesUseCase = loadInvitesUseCase,
                    deleteUserUseCase = deleteUserUseCase,
                    revokeInviteUseCase = revokeInviteUseCase,
                    approveUserUseCase = approveUserUseCase,
                    denyUserUseCase = denyUserUseCase,
                    setOpenRegistrationUseCase = setOpenRegistrationUseCase,
                    eventStreamRepository = createMockEventStreamRepository(),
                )
            advanceUntilIdle()

            val ready = assertIs<AdminUiState.Ready>(viewModel.state.value)
            assertEquals(2, ready.users.size)
            assertEquals(1, ready.pendingInvites.size)
        }

    @Test
    fun `loadData filters out claimed invites`() =
        runTest {
            val instanceRepo = createMockInstanceRepository()
            val loadUsersUseCase: LoadUsersUseCase = mock()
            val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
            val loadInvitesUseCase: LoadInvitesUseCase = mock()
            val deleteUserUseCase: DeleteUserUseCase = mock()
            val revokeInviteUseCase: RevokeInviteUseCase = mock()
            val approveUserUseCase: ApproveUserUseCase = mock()
            val denyUserUseCase: DenyUserUseCase = mock()
            val setOpenRegistrationUseCase: SetOpenRegistrationUseCase = mock()

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
                    instanceRepository = instanceRepo,
                    loadUsersUseCase = loadUsersUseCase,
                    loadPendingUsersUseCase = loadPendingUsersUseCase,
                    loadInvitesUseCase = loadInvitesUseCase,
                    deleteUserUseCase = deleteUserUseCase,
                    revokeInviteUseCase = revokeInviteUseCase,
                    approveUserUseCase = approveUserUseCase,
                    denyUserUseCase = denyUserUseCase,
                    setOpenRegistrationUseCase = setOpenRegistrationUseCase,
                    eventStreamRepository = createMockEventStreamRepository(),
                )
            advanceUntilIdle()

            val ready = assertIs<AdminUiState.Ready>(viewModel.state.value)
            assertEquals(1, ready.pendingInvites.size)
            assertEquals("pending", ready.pendingInvites[0].id)
        }

    @Test
    fun `loadData initial users failure transitions to Error`() =
        runTest {
            val instanceRepo = createMockInstanceRepository()
            val loadUsersUseCase: LoadUsersUseCase = mock()
            val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
            val loadInvitesUseCase: LoadInvitesUseCase = mock()
            val deleteUserUseCase: DeleteUserUseCase = mock()
            val revokeInviteUseCase: RevokeInviteUseCase = mock()
            val approveUserUseCase: ApproveUserUseCase = mock()
            val denyUserUseCase: DenyUserUseCase = mock()
            val setOpenRegistrationUseCase: SetOpenRegistrationUseCase = mock()

            everySuspend { loadUsersUseCase() } returns Failure(RuntimeException("Network error"))
            everySuspend { loadPendingUsersUseCase() } returns AppResult.Success(emptyList())
            everySuspend { loadInvitesUseCase() } returns AppResult.Success(emptyList())

            val viewModel =
                AdminViewModel(
                    instanceRepository = instanceRepo,
                    loadUsersUseCase = loadUsersUseCase,
                    loadPendingUsersUseCase = loadPendingUsersUseCase,
                    loadInvitesUseCase = loadInvitesUseCase,
                    deleteUserUseCase = deleteUserUseCase,
                    revokeInviteUseCase = revokeInviteUseCase,
                    approveUserUseCase = approveUserUseCase,
                    denyUserUseCase = denyUserUseCase,
                    setOpenRegistrationUseCase = setOpenRegistrationUseCase,
                    eventStreamRepository = createMockEventStreamRepository(),
                )
            advanceUntilIdle()

            val error = assertIs<AdminUiState.Error>(viewModel.state.value)
            assertTrue(error.message.contains("users"))
        }

    @Test
    fun `deleteUser removes user from list`() =
        runTest {
            val instanceRepo = createMockInstanceRepository()
            val loadUsersUseCase: LoadUsersUseCase = mock()
            val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
            val loadInvitesUseCase: LoadInvitesUseCase = mock()
            val deleteUserUseCase: DeleteUserUseCase = mock()
            val revokeInviteUseCase: RevokeInviteUseCase = mock()
            val approveUserUseCase: ApproveUserUseCase = mock()
            val denyUserUseCase: DenyUserUseCase = mock()
            val setOpenRegistrationUseCase: SetOpenRegistrationUseCase = mock()

            val users = listOf(createUser("user-1"), createUser("user-2"))
            everySuspend { loadUsersUseCase() } returns AppResult.Success(users)
            everySuspend { loadPendingUsersUseCase() } returns AppResult.Success(emptyList())
            everySuspend { loadInvitesUseCase() } returns AppResult.Success(emptyList())
            everySuspend { deleteUserUseCase("user-1") } returns AppResult.Success(Unit)

            val viewModel =
                AdminViewModel(
                    instanceRepository = instanceRepo,
                    loadUsersUseCase = loadUsersUseCase,
                    loadPendingUsersUseCase = loadPendingUsersUseCase,
                    loadInvitesUseCase = loadInvitesUseCase,
                    deleteUserUseCase = deleteUserUseCase,
                    revokeInviteUseCase = revokeInviteUseCase,
                    approveUserUseCase = approveUserUseCase,
                    denyUserUseCase = denyUserUseCase,
                    setOpenRegistrationUseCase = setOpenRegistrationUseCase,
                    eventStreamRepository = createMockEventStreamRepository(),
                )
            advanceUntilIdle()
            val afterLoad = assertIs<AdminUiState.Ready>(viewModel.state.value)
            assertEquals(2, afterLoad.users.size)

            viewModel.deleteUser("user-1")
            advanceUntilIdle()

            val ready = assertIs<AdminUiState.Ready>(viewModel.state.value)
            assertEquals(1, ready.users.size)
            assertEquals("user-2", ready.users[0].id)
        }

    @Test
    fun `revokeInvite removes invite from list`() =
        runTest {
            val instanceRepo = createMockInstanceRepository()
            val loadUsersUseCase: LoadUsersUseCase = mock()
            val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
            val loadInvitesUseCase: LoadInvitesUseCase = mock()
            val deleteUserUseCase: DeleteUserUseCase = mock()
            val revokeInviteUseCase: RevokeInviteUseCase = mock()
            val approveUserUseCase: ApproveUserUseCase = mock()
            val denyUserUseCase: DenyUserUseCase = mock()
            val setOpenRegistrationUseCase: SetOpenRegistrationUseCase = mock()

            val invites = listOf(createInvite("invite-1"), createInvite("invite-2"))
            everySuspend { loadUsersUseCase() } returns AppResult.Success(emptyList())
            everySuspend { loadPendingUsersUseCase() } returns AppResult.Success(emptyList())
            everySuspend { loadInvitesUseCase() } returns AppResult.Success(invites)
            everySuspend { revokeInviteUseCase("invite-1") } returns AppResult.Success(Unit)

            val viewModel =
                AdminViewModel(
                    instanceRepository = instanceRepo,
                    loadUsersUseCase = loadUsersUseCase,
                    loadPendingUsersUseCase = loadPendingUsersUseCase,
                    loadInvitesUseCase = loadInvitesUseCase,
                    deleteUserUseCase = deleteUserUseCase,
                    revokeInviteUseCase = revokeInviteUseCase,
                    approveUserUseCase = approveUserUseCase,
                    denyUserUseCase = denyUserUseCase,
                    setOpenRegistrationUseCase = setOpenRegistrationUseCase,
                    eventStreamRepository = createMockEventStreamRepository(),
                )
            advanceUntilIdle()

            viewModel.revokeInvite("invite-1")
            advanceUntilIdle()

            val ready = assertIs<AdminUiState.Ready>(viewModel.state.value)
            assertEquals(1, ready.pendingInvites.size)
            assertEquals("invite-2", ready.pendingInvites[0].id)
        }

    @Test
    fun `clearError clears error state`() =
        runTest {
            val instanceRepo = createMockInstanceRepository()
            val loadUsersUseCase: LoadUsersUseCase = mock()
            val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
            val loadInvitesUseCase: LoadInvitesUseCase = mock()
            val deleteUserUseCase: DeleteUserUseCase = mock()
            val revokeInviteUseCase: RevokeInviteUseCase = mock()
            val approveUserUseCase: ApproveUserUseCase = mock()
            val denyUserUseCase: DenyUserUseCase = mock()
            val setOpenRegistrationUseCase: SetOpenRegistrationUseCase = mock()

            // Users succeeds to land in Ready; invites fails to surface transient error on Ready.
            everySuspend { loadUsersUseCase() } returns AppResult.Success(emptyList())
            everySuspend { loadPendingUsersUseCase() } returns AppResult.Success(emptyList())
            everySuspend { loadInvitesUseCase() } returns Failure(RuntimeException("Invites error"))

            val viewModel =
                AdminViewModel(
                    instanceRepository = instanceRepo,
                    loadUsersUseCase = loadUsersUseCase,
                    loadPendingUsersUseCase = loadPendingUsersUseCase,
                    loadInvitesUseCase = loadInvitesUseCase,
                    deleteUserUseCase = deleteUserUseCase,
                    revokeInviteUseCase = revokeInviteUseCase,
                    approveUserUseCase = approveUserUseCase,
                    denyUserUseCase = denyUserUseCase,
                    setOpenRegistrationUseCase = setOpenRegistrationUseCase,
                    eventStreamRepository = createMockEventStreamRepository(),
                )
            advanceUntilIdle()
            val beforeClear = assertIs<AdminUiState.Ready>(viewModel.state.value)
            assertTrue(beforeClear.error != null)

            viewModel.clearError()

            val afterClear = assertIs<AdminUiState.Ready>(viewModel.state.value)
            assertNull(afterClear.error)
        }

    @Test
    fun `loadData fetches all data in parallel`() =
        runTest {
            val instanceRepo: InstanceRepository = mock()
            everySuspend { instanceRepo.getInstance() } calls {
                delay(100)
                AppResult.Success(createMockInstance())
            }

            val loadUsersUseCase: LoadUsersUseCase = mock()
            val loadPendingUsersUseCase: LoadPendingUsersUseCase = mock()
            val loadInvitesUseCase: LoadInvitesUseCase = mock()
            val deleteUserUseCase: DeleteUserUseCase = mock()
            val revokeInviteUseCase: RevokeInviteUseCase = mock()
            val approveUserUseCase: ApproveUserUseCase = mock()
            val denyUserUseCase: DenyUserUseCase = mock()
            val setOpenRegistrationUseCase: SetOpenRegistrationUseCase = mock()

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
                    instanceRepository = instanceRepo,
                    loadUsersUseCase = loadUsersUseCase,
                    loadPendingUsersUseCase = loadPendingUsersUseCase,
                    loadInvitesUseCase = loadInvitesUseCase,
                    deleteUserUseCase = deleteUserUseCase,
                    revokeInviteUseCase = revokeInviteUseCase,
                    approveUserUseCase = approveUserUseCase,
                    denyUserUseCase = denyUserUseCase,
                    setOpenRegistrationUseCase = setOpenRegistrationUseCase,
                    eventStreamRepository = createMockEventStreamRepository(),
                )

            // If parallel, all 4 calls start at t=0 and complete at t=100ms.
            // If sequential, they'd complete at t=400ms.
            // Advance 150ms — enough for parallel, not enough for sequential.
            advanceTimeBy(150)

            val ready = assertIs<AdminUiState.Ready>(viewModel.state.value)
            assertEquals(1, ready.users.size)
            assertEquals(1, ready.pendingInvites.size)
        }
}
