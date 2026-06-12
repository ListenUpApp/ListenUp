package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.model.ServerSettings
import com.calypsan.listenup.client.domain.usecase.admin.LoadServerSettingsUseCase
import com.calypsan.listenup.client.domain.usecase.admin.UpdateServerSettingsUseCase
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.calypsan.listenup.core.error.ErrorBus

/**
 * Tests for AdminSettingsViewModel.
 *
 * Tests cover:
 * - Initial `Loading` state before the load completes
 * - `Ready` emission once settings are loaded (serverName + remoteUrl from getServerSettings)
 * - `Error` state when the initial load fails
 * - Edit-buffer mutations (`setServerName`, `setRemoteUrl`) update Ready and recompute `isDirty`
 * - `saveAll` happy path clears `isSaving` and resets `isDirty` for server name
 * - `saveAll` happy path clears `isSaving` and resets `isDirty` for remote URL
 * - `saveAll` failure surfaces as transient `error` on Ready
 * - `clearError` clears the transient error on Ready
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminSettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // ========== Test Fixtures ==========

    private class TestFixture {
        val loadServerSettingsUseCase: LoadServerSettingsUseCase = mock()
        val updateServerSettingsUseCase: UpdateServerSettingsUseCase = mock()

        fun build(): AdminSettingsViewModel =
            AdminSettingsViewModel(
                loadServerSettingsUseCase = loadServerSettingsUseCase,
                updateServerSettingsUseCase = updateServerSettingsUseCase,
                errorBus = ErrorBus(),
            )
    }

    private fun createFixture(settings: ServerSettings = createServerSettings()): TestFixture {
        val fixture = TestFixture()
        everySuspend { fixture.loadServerSettingsUseCase() } returns AppResult.Success(settings)
        return fixture
    }

    // ========== Test Data Factories ==========

    private fun createServerSettings(
        serverName: String = "My Server",
        remoteUrl: String? = null,
    ): ServerSettings =
        ServerSettings(
            serverName = serverName,
            remoteUrl = remoteUrl,
        )

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== Initial State ==========

    @Test
    fun `initial state is Loading`() =
        runTest {
            val fixture = createFixture()
            // Do not advance — we want to observe the state before init completes.
            val viewModel = fixture.build()

            assertIs<AdminSettingsUiState.Loading>(viewModel.state.value)
        }

    // ========== Load ==========

    @Test
    fun `load transitions to Ready with server settings`() =
        runTest {
            val fixture =
                createFixture(
                    settings = createServerSettings(serverName = "ListenUp Prod", remoteUrl = "https://audio.example.com"),
                )

            val viewModel = fixture.build()
            advanceUntilIdle()

            val ready = assertIs<AdminSettingsUiState.Ready>(viewModel.state.value)
            assertEquals("ListenUp Prod", ready.serverName)
            assertEquals("https://audio.example.com", ready.remoteUrl)
            assertFalse(ready.isDirty)
            assertFalse(ready.isSaving)
            assertNull(ready.error)
        }

    @Test
    fun `load failure transitions to Error`() =
        runTest {
            val fixture = TestFixture()
            // Body-level message convention: pass a typed AppError so the
            // user-facing message survives delegation to the ViewModel.
            everySuspend { fixture.loadServerSettingsUseCase() } returns
                AppResult.Failure(
                    com.calypsan.listenup.api.error
                        .ValidationError(message = "Network down"),
                )

            val viewModel = fixture.build()
            advanceUntilIdle()

            val error = assertIs<AdminSettingsUiState.Error>(viewModel.state.value)
            assertEquals("Network down", error.error.message)
        }

    // ========== Edit Buffer Mutations ==========

    @Test
    fun `setServerName updates Ready and marks dirty`() =
        runTest {
            val fixture = createFixture(settings = createServerSettings(serverName = "Old Name"))
            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.setServerName("New Name")

            val ready = assertIs<AdminSettingsUiState.Ready>(viewModel.state.value)
            assertEquals("New Name", ready.serverName)
            assertTrue(ready.isDirty)
        }

    // ========== Save ==========

    @Test
    fun `saveAll happy-path persists server name changes and resets dirty`() =
        runTest {
            val fixture = createFixture(settings = createServerSettings(serverName = "Original"))
            everySuspend { fixture.updateServerSettingsUseCase.updateServerName("Renamed") } returns
                AppResult.Success(createServerSettings(serverName = "Renamed"))
            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.setServerName("Renamed")
            viewModel.saveAll()
            advanceUntilIdle()

            val ready = assertIs<AdminSettingsUiState.Ready>(viewModel.state.value)
            assertEquals("Renamed", ready.serverName)
            assertFalse(ready.isSaving)
            assertFalse(ready.isDirty)
            assertNull(ready.error)
            verifySuspend(VerifyMode.atLeast(1)) {
                fixture.updateServerSettingsUseCase.updateServerName("Renamed")
            }
        }

    @Test
    fun `saveAll happy-path persists remote URL changes and resets dirty`() =
        runTest {
            val fixture = createFixture(settings = createServerSettings(remoteUrl = "https://old.example.com"))
            everySuspend { fixture.updateServerSettingsUseCase.updateRemoteUrl("https://new.example.com") } returns
                AppResult.Success(createServerSettings(remoteUrl = "https://new.example.com"))
            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.setRemoteUrl("https://new.example.com")
            viewModel.saveAll()
            advanceUntilIdle()

            val ready = assertIs<AdminSettingsUiState.Ready>(viewModel.state.value)
            assertFalse(ready.isSaving)
            assertFalse(ready.isDirty)
            assertNull(ready.error)
            verifySuspend(VerifyMode.atLeast(1)) {
                fixture.updateServerSettingsUseCase.updateRemoteUrl("https://new.example.com")
            }
        }

    @Test
    fun `saveAll failure surfaces as transient error on Ready`() =
        runTest {
            val fixture = createFixture(settings = createServerSettings(serverName = "Original"))
            // Body-level message convention: pass a typed AppError so the
            // "Forbidden" text surfaces directly as the typed Ready.error.
            everySuspend { fixture.updateServerSettingsUseCase.updateServerName("Renamed") } returns
                AppResult.Failure(
                    com.calypsan.listenup.api.error
                        .ValidationError(message = "Forbidden"),
                )
            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.setServerName("Renamed")
            viewModel.saveAll()
            advanceUntilIdle()

            val ready = assertIs<AdminSettingsUiState.Ready>(viewModel.state.value)
            assertFalse(ready.isSaving)
            assertTrue(ready.error?.message?.contains("Forbidden") == true)
            // Dirty remains true because buffer diverges from baseline after failed save.
            assertTrue(ready.isDirty)
        }

    // ========== Transient State Clearing ==========

    @Test
    fun `clearError clears Ready error to null`() =
        runTest {
            val fixture = createFixture(settings = createServerSettings(serverName = "Original"))
            everySuspend { fixture.updateServerSettingsUseCase.updateServerName("Renamed") } returns
                Failure(RuntimeException("boom"))
            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.setServerName("Renamed")
            viewModel.saveAll()
            advanceUntilIdle()
            val withError = assertIs<AdminSettingsUiState.Ready>(viewModel.state.value)
            assertTrue(withError.error != null)

            viewModel.clearError()

            val cleared = assertIs<AdminSettingsUiState.Ready>(viewModel.state.value)
            assertNull(cleared.error)
        }
}
