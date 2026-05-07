package com.calypsan.listenup.client.presentation.startup

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.data.remote.LibraryStatusResponse
import com.calypsan.listenup.client.data.remote.SetupApiContract
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.UserRepository
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for AppStartupViewModel.
 *
 * Tests cover:
 * - Initial state and library setup check
 * - onAppBackgrounded() records timestamp
 * - onAppForegrounded() behavior for short vs long background periods
 * - Threshold constant value
 *
 * Uses Mokkery for mocking and follows Given-When-Then style.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppStartupViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== Test Data Factories ==========

    private fun createMockUserRepository(): UserRepository = mock<UserRepository>()

    private fun createMockSetupApi(): SetupApiContract = mock<SetupApiContract>()

    private fun createTestUser(
        id: String = "user-001",
        isAdmin: Boolean = false,
    ): User =
        User(
            id =
                UserId(id),
            email = "test@example.com",
            displayName = "Test User",
            firstName = null,
            lastName = null,
            isAdmin = isAdmin,
            avatarType = "auto",
            avatarValue = null,
            avatarColor = "#3B82F6",
            tagline = null,
            createdAtMs = 1704067200000L,
            updatedAtMs = 1704153600000L,
        )

    // ========== Threshold Constant Tests ==========

    @Test
    fun `BACKGROUND_THRESHOLD_MS is 30 minutes`() {
        // Given/When/Then
        val expectedMs = 30 * 60 * 1000L
        assertEquals(expectedMs, AppStartupViewModel.BACKGROUND_THRESHOLD_MS)
    }

    // ========== Initial State Tests ==========

    @Test
    fun `initial state has isChecking true`() =
        runTest {
            // Given
            val userRepository = createMockUserRepository()
            val setupApi = createMockSetupApi()
            everySuspend { userRepository.refreshCurrentUser() } returns null
            everySuspend { userRepository.getCurrentUser() } returns null

            // When
            val viewModel = AppStartupViewModel(userRepository, setupApi)

            // Then - initial state before coroutine completes
            assertTrue(viewModel.state.value.isChecking)
        }

    @Test
    fun `initial check completes and sets isChecking false for non-admin user`() =
        runTest {
            // Given
            val userRepository = createMockUserRepository()
            val setupApi = createMockSetupApi()
            val regularUser = createTestUser(isAdmin = false)
            everySuspend { userRepository.refreshCurrentUser() } returns regularUser
            everySuspend { userRepository.getCurrentUser() } returns regularUser

            // When
            val viewModel = AppStartupViewModel(userRepository, setupApi)
            advanceUntilIdle()

            // Then
            assertFalse(viewModel.state.value.isChecking)
            assertFalse(viewModel.state.value.needsLibrarySetup)
        }

    @Test
    fun `initial check completes and sets needsLibrarySetup true for admin when library needs setup`() =
        runTest {
            // Given
            val userRepository = createMockUserRepository()
            val setupApi = createMockSetupApi()
            val adminUser = createTestUser(isAdmin = true)
            everySuspend { userRepository.refreshCurrentUser() } returns adminUser
            everySuspend { userRepository.getCurrentUser() } returns adminUser
            everySuspend { setupApi.getLibraryStatus() } returns
                AppResult.Success(
                    LibraryStatusResponse(
                        exists = false,
                        library = null,
                        needsSetup = true,
                        bookCount = 0,
                        isScanning = false,
                    ),
                )

            // When
            val viewModel = AppStartupViewModel(userRepository, setupApi)
            advanceUntilIdle()

            // Then
            assertFalse(viewModel.state.value.isChecking)
            assertTrue(viewModel.state.value.needsLibrarySetup)
        }

    // ========== onAppBackgrounded Tests ==========

    @Test
    fun `onAppBackgrounded records timestamp`() =
        runTest {
            // Given
            val userRepository = createMockUserRepository()
            val setupApi = createMockSetupApi()
            everySuspend { userRepository.refreshCurrentUser() } returns null
            everySuspend { userRepository.getCurrentUser() } returns null
            val viewModel = AppStartupViewModel(userRepository, setupApi)
            advanceUntilIdle()

            // When
            viewModel.onAppBackgrounded()

            // Then
            assertNotNull(viewModel.state.value.backgroundedAtMs)
        }

    // ========== onAppForegrounded Tests ==========

    @Test
    fun `onAppForegrounded does nothing when backgroundedAtMs is null`() =
        runTest {
            // Given
            val userRepository = createMockUserRepository()
            val setupApi = createMockSetupApi()
            everySuspend { userRepository.refreshCurrentUser() } returns null
            everySuspend { userRepository.getCurrentUser() } returns null
            val viewModel = AppStartupViewModel(userRepository, setupApi)
            advanceUntilIdle()

            // Verify backgroundedAtMs is null before the call
            assertNull(viewModel.state.value.backgroundedAtMs)
            val isCheckingBefore = viewModel.state.value.isChecking

            // When
            viewModel.onAppForegrounded()
            advanceUntilIdle()

            // Then - state unchanged
            assertEquals(isCheckingBefore, viewModel.state.value.isChecking)
        }

    @Test
    fun `onAppForegrounded does NOT reset isChecking for short background period`() =
        runTest {
            // Given
            val userRepository = createMockUserRepository()
            val setupApi = createMockSetupApi()
            everySuspend { userRepository.refreshCurrentUser() } returns null
            everySuspend { userRepository.getCurrentUser() } returns null
            val viewModel = AppStartupViewModel(userRepository, setupApi)
            advanceUntilIdle()

            // Initial check is complete, isChecking should be false
            assertFalse(viewModel.state.value.isChecking)

            // Simulate backgrounding and immediate foregrounding (short period)
            viewModel.onAppBackgrounded()

            // When - foreground immediately (elapsed time ~0ms, well under 30 min threshold)
            viewModel.onAppForegrounded()
            advanceUntilIdle()

            // Then - isChecking should still be false (no re-check triggered)
            assertFalse(viewModel.state.value.isChecking)
        }

    @Test
    fun `onAppForegrounded preserves needsLibrarySetup state for short background period`() =
        runTest {
            // Given - admin user with library needing setup
            val userRepository = createMockUserRepository()
            val setupApi = createMockSetupApi()
            val adminUser = createTestUser(isAdmin = true)
            everySuspend { userRepository.refreshCurrentUser() } returns adminUser
            everySuspend { userRepository.getCurrentUser() } returns adminUser
            everySuspend { setupApi.getLibraryStatus() } returns
                AppResult.Success(
                    LibraryStatusResponse(
                        exists = false,
                        library = null,
                        needsSetup = true,
                        bookCount = 0,
                        isScanning = false,
                    ),
                )

            val viewModel = AppStartupViewModel(userRepository, setupApi)
            advanceUntilIdle()

            // Verify initial state
            assertFalse(viewModel.state.value.isChecking)
            assertTrue(viewModel.state.value.needsLibrarySetup)

            // Simulate short background period
            viewModel.onAppBackgrounded()

            // When - foreground after short period
            viewModel.onAppForegrounded()
            advanceUntilIdle()

            // Then - state preserved, no re-check
            assertFalse(viewModel.state.value.isChecking)
            assertTrue(viewModel.state.value.needsLibrarySetup)
        }
}
